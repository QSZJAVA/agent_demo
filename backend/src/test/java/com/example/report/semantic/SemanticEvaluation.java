package com.example.report.semantic;

import com.example.report.catalog.*;
import com.example.report.common.*;
import com.example.report.config.AgentProperties;
import com.example.report.dispatch.RecordKey;
import com.example.report.support.TestCatalog;
import java.util.*;
import static com.example.report.semantic.SemanticIntent.Target.*;
import static com.example.report.support.TestCatalog.*;
import static com.example.report.support.DispatchHarness.candidate;

/** 用合成候选事实校验解析后的范围和排除状态，供离线与真实模型回放共用；不调用业务网关或执行派单。 */
final class SemanticEvaluation {
    record Evaluation(int expectedTurns,List<Map<String,Object>> results) {
        long failed() { return results.stream().filter(r->!Boolean.TRUE.equals(r.get("passed"))).count(); }
    }
    /** 每个场景可声明独立客户与记录，避免真实模型只能通过固定客户和单条记录样本。 */
    static List<com.example.report.rule.Candidate> scenarioRows(com.fasterxml.jackson.databind.JsonNode scenario) {
        if(scenario.has("records")) {
            List<com.example.report.rule.Candidate> rows=new ArrayList<>();
            for(var r:scenario.get("records")) {
                var base=candidate(r.get("reportId").asText(),r.get("id").asText(),r.get("docNo").asText(),r.path("company").asText("A"),r.get("label").asText());
                rows.add(withCustomer(base,r.has("customer")?JsonUtil.MAPPER.convertValue(r.get("customer"),com.example.report.rule.CounterpartyRef.class):null));
            }
            return List.copyOf(rows);
        }
        return List.of(candidate(SALES,"1","SO2026001","A","第一笔"),candidate(SALES,"2","SO2026002","A","云服务"),
                candidate(SALES,"3","900000000000000001","A","数字单据"),
                withCustomer(candidate(RECEIVABLE,"7","INV-2026-0007","A","天津某某贸易有限公司"),new com.example.report.rule.CounterpartyRef("customer-7","天津某某贸易有限公司",List.of())),
                withCustomer(candidate(RECEIVABLE,"8","INV-2026-0008","A","北京某某咨询有限公司"),new com.example.report.rule.CounterpartyRef("customer-8","北京某某咨询有限公司",List.of())));
    }
    static com.example.report.rule.Candidate withCustomer(com.example.report.rule.Candidate row,com.example.report.rule.CounterpartyRef customer) {
        return new com.example.report.rule.Candidate(row.reportId(),row.reportName(),row.recordId(),row.docNo(),row.companyCode(),row.label(),
                row.amount(),row.date(),row.ruleId(),row.ruleName(),row.ruleVersion(),row.ruleDescription(),row.catalogVersion(),customer);
    }
    static Evaluation run(IntentParser parser,AgentProperties props) throws Exception {
        return run(parser,props,List.of("replay-corpus.json","business-corpus-v2.json"));
    }
    static Evaluation run(IntentParser parser,AgentProperties props,List<String> resources) throws Exception {
        var catalog=new ReportCatalogService(new TestCatalog().catalog(),props);
        var planner=new SemanticPlanner(catalog);
        List<Map<String,Object>> results=new ArrayList<>(); int expected=0;
        for(String resource:resources) {
            com.fasterxml.jackson.databind.JsonNode scenarios;
            try(var input=SemanticEvaluation.class.getResourceAsStream("/semantic/"+resource)) {scenarios=JsonUtil.MAPPER.readTree(input);}
            for(var scenario:scenarios) {
                var state=new DialogueState();expected+=scenario.get("turns").size();
                for(var test:scenario.get("turns")) {
                    String message=test.get("message").asText();long start=System.nanoTime();
                    Map<String,Object> result=new LinkedHashMap<>();result.put("scenario",scenario.get("name").asText());result.put("message",message);
                    result.put("passed",false);
                    try {
                        var mentions=planner.mentions(USER1,message);
                        var interpreted=parser.interpret(message,new IntentParser.Context(state,catalog.dispatchableReports(USER1).stream().map(CatalogEntry::ref).toList(),mentions,SemanticCapabilities.selectors(catalog.dispatchableReports(USER1)),draft -> planner.validateModelDraft(USER1,state,draft),SemanticCapabilities.fields(catalog.dispatchableReports(USER1))));
                        var intent=interpreted.intent();new IntentCodec().validate(intent,message);
                        result.put("parserSource",interpreted.source().name());result.put("intent",intent);
                        String outcome="READY";
                        try {
                            planner.requireCoverage(state,intent,mentions);planner.merge(USER1,state,intent);
                            if (Set.of(SemanticIntent.Action.PREVIEW,SemanticIntent.Action.PREPARE_DISPATCH,SemanticIntent.Action.EXPLAIN_RULES).contains(intent.action())) {
                            planner.validate(USER1,state);
                            // Synthetic source rows for selection checks; the live model never queries or dispatches business data.
                            var rows=scenarioRows(scenario).stream()
                                    .filter(r -> USER1.companies().contains(r.companyCode()) && catalog.dispatchableIds(USER1).contains(r.reportId()))
                                    .filter(r -> state.getDesired().companyCode()==null || state.getDesired().companyCode().equals(r.companyCode()))
                                    .filter(r -> state.getDesired().allReports() || state.getDesired().reportIds().contains(r.reportId())).toList();
                            List<RecordKey> selected=state.getExcludedRecords();
                            if (state.isUnresolvedRecords() && !intent.changes(RECORDS)) throw new ApiException(422,"排除记录尚未确定");
                            if (intent.changes(RECORDS)) state.setUnresolvedRecords(true);
                            for(var change:intent.scopeChanges()) if(change.target()==RECORDS)
                                selected=SelectionResolver.apply(rows,selected,change,catalog,USER1);
                            SelectionResolver.validate(rows,selected);
                            state.setUnresolvedRecords(false);state.setUnresolvedRequest(false);state.setExcludedRecords(selected);state.setEffective(state.getDesired());
                            state.setPhase(intent.action()==SemanticIntent.Action.PREPARE_DISPATCH?DialogueState.Phase.PLAN_READY:DialogueState.Phase.READY);
                            }
                        } catch(ApiException e) {outcome=e.getCode()==422?"CLARIFY":"REJECTED";state.setPhase(DialogueState.Phase.valueOf(outcome));state.setLastReason(e.getMessage());}
                        state.setPendingIntent(intent);state.setRecentUserMessages(List.of(message));
                        result.put("expected",test);
                        boolean passed=intent.action().name().equals(test.get("action").asText())
                                && Objects.equals(state.getDesired().companyCode(),test.get("company").isNull()?null:test.get("company").asText())
                                && outcome.equals(test.get("outcome").asText());
                        // Legacy corpus still describes one operation per target; V2 cases assert final state instead.
                        for(var target:List.of(COMPANY,REPORTS,RECORDS)) {
                            String key=target==COMPANY?"companyOperation":target==REPORTS?"reportsOperation":"exclusionsOperation";
                            if(test.has(key)) {
                                // 范围按最终集合验收，允许等价归并；没有最终状态断言的旧用例才保留操作级检查。
                                if(target==COMPANY || (target==REPORTS && (test.has("reportIds") || test.has("allReports")))) continue;
                                var changes=intent.changesFor(target);
                                passed &= changes.size()<=1 && (changes.isEmpty()?"KEEP":changes.get(0).operation().name()).equals(test.get(key).asText());
                            }
                        }
                        if(test.has("reportIds")) passed &= new HashSet<>(state.getDesired().reportIds()).equals(JsonUtil.MAPPER.convertValue(test.get("reportIds"),new com.fasterxml.jackson.core.type.TypeReference<Set<String>>(){}));
                        if(test.has("allReports")) passed &= state.getDesired().allReports()==test.get("allReports").asBoolean();
                        if(test.has("excludedIds")) passed &= new HashSet<>(state.getExcludedRecords().stream().map(RecordKey::recordId).toList()).equals(JsonUtil.MAPPER.convertValue(test.get("excludedIds"),new com.fasterxml.jackson.core.type.TypeReference<Set<String>>(){}));
                        if(test.has("forbiddenActions")) for(var action:test.get("forbiddenActions")) passed &= intent.forbids(SemanticIntent.Action.valueOf(action.asText()));
                        result.put("passed",passed);result.put("outcome",outcome);result.put("reason",state.getLastReason());result.put("scope",state.getDesired());result.put("excludedRecords",state.getExcludedRecords());
                    } catch(Exception e) {
                        result.put("error",e.getClass().getSimpleName());
                        if(e instanceof IntentCodec.InvalidOutput invalid) result.put("invalidOutput",invalid.output());
                        // No external exception bodies (which can contain credentials) are written to reports.
                        state.setUnresolvedRequest(true);state.setPhase(DialogueState.Phase.CLARIFY);
                    }
                    result.put("latencyMs",(System.nanoTime()-start)/1_000_000);results.add(result);
                    if ("true".equals(System.getenv("SEMANTIC_LIVE_EVAL")))
                        System.out.println("Semantic evaluation turn "+results.size()+": "+(Boolean.TRUE.equals(result.get("passed"))?"PASS":"FAIL")+" ("+result.get("latencyMs")+" ms)");
                }
            }
        }
        return new Evaluation(expected,List.copyOf(results));
    }
}
