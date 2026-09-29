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

/** Shared offline/live oracle: verifies business state, not just valid JSON. Never calls a business gateway. */
final class SemanticEvaluation {
    record Evaluation(int expectedTurns,List<Map<String,Object>> results) {
        long failed() { return results.stream().filter(r->!Boolean.TRUE.equals(r.get("passed"))).count(); }
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
                        var interpreted=parser.interpret(message,new IntentParser.Context(state,catalog.dispatchableReports(USER1).stream().map(CatalogEntry::ref).toList(),mentions));
                        var intent=interpreted.intent();new IntentCodec().validate(intent,message);
                        result.put("parserSource",interpreted.source().name());result.put("intent",intent);
                        String outcome="READY";
                        try {
                            planner.requireCoverage(state,intent,mentions);planner.merge(USER1,state,intent);
                            if (Set.of(SemanticIntent.Action.PREVIEW,SemanticIntent.Action.PREPARE_DISPATCH,SemanticIntent.Action.EXPLAIN_RULES).contains(intent.action())) {
                            planner.validate(USER1,state);
                            // Synthetic source rows for selection checks; the live model never queries or dispatches business data.
                            var rows=List.of(candidate(SALES,"1","SO2026001","A","第一笔"),candidate(SALES,"2","SO2026002","A","云服务"),
                                    candidate(SALES,"3","900000000000000001","A","数字单据")).stream()
                                    .filter(r -> state.getDesired().allReports() || state.getDesired().reportIds().contains(r.reportId())).toList();
                            List<RecordKey> selected=state.getExcludedRecords();
                            if (state.isUnresolvedRecords() && !intent.changes(RECORDS)) throw new ApiException(422,"排除记录尚未确定");
                            if (intent.changes(RECORDS)) state.setUnresolvedRecords(true);
                            for(var change:intent.changesFor(RECORDS)) selected=SelectionResolver.apply(rows,selected,change);
                            SelectionResolver.validate(rows,selected);
                            state.setUnresolvedRecords(false);state.setExcludedRecords(selected);state.setEffective(state.getDesired());
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
                                var changes=intent.changesFor(target);
                                passed &= changes.size()<=1 && (changes.isEmpty()?"KEEP":changes.get(0).operation().name()).equals(test.get(key).asText());
                            }
                        }
                        if(test.has("reportIds")) passed &= new HashSet<>(state.getDesired().reportIds()).equals(JsonUtil.MAPPER.convertValue(test.get("reportIds"),new com.fasterxml.jackson.core.type.TypeReference<Set<String>>(){}));
                        if(test.has("allReports")) passed &= state.getDesired().allReports()==test.get("allReports").asBoolean();
                        if(test.has("excludedIds")) passed &= new HashSet<>(state.getExcludedRecords().stream().map(RecordKey::recordId).toList()).equals(JsonUtil.MAPPER.convertValue(test.get("excludedIds"),new com.fasterxml.jackson.core.type.TypeReference<Set<String>>(){}));
                        if(test.has("forbiddenActions")) for(var action:test.get("forbiddenActions")) passed &= intent.forbids(SemanticIntent.Action.valueOf(action.asText()));
                        result.put("passed",passed);result.put("outcome",outcome);result.put("scope",state.getDesired());result.put("excludedRecords",state.getExcludedRecords());
                    } catch(Exception e) {
                        result.put("error",e.getClass().getSimpleName());
                        if(e instanceof IntentCodec.InvalidOutput invalid) result.put("invalidOutput",invalid.output());
                        // No external exception bodies (which can contain credentials) are written to reports.
                        state.setUnresolvedCompany(true);state.setUnresolvedReports(true);state.setPhase(DialogueState.Phase.CLARIFY);
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
