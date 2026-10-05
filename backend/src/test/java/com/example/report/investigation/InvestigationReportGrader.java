package com.example.report.investigation;

import java.util.*;

/** 独立报告评分器；以语料事实、实际查询证据和正文契约交叉判定，不调用生产校验器或正文生成器自评。 */
final class InvestigationReportGrader {
    private InvestigationReportGrader() { }
    // 这是独立验收文本契约。改动产品措辞需同时人工审查状态含义，不能从生产生成器自动导入期望。
    private static final Map<String,String> TEXT=Map.of(
            "BUSINESS_REJECTED","业务结果明确失败；具体原因请查看引用证据。",
            "PRECHECK_SKIPPED","执行前复核跳过；规则存在本身不能证明因果关系。",
            "RESULT_UNKNOWN","业务结果仍未知，不能据此认定失败或允许重发。",
            "REMOTE_SUCCESS_LOCAL_UNRESOLVED","本次远端核对成功，本地仍待核对；本调查未修改本地状态。",
            "REQUEST_NOT_FOUND","本次未查到原请求，不能据此认定未发送或允许重发。",
            "EVIDENCE_MISSING","必要证据缺失，尚不能据此作出完整判断。",
            "UNDETERMINED","尚不能确定异常原因。");
    private static final Map<String,String> TOPICS=Map.of(
            "RULE_CAUSALITY","仍需核查规则与异常的因果关系。","EVENT_HISTORY","仍需核查执行事件是否完整。",
            "REMOTE_RESULT","仍需通过原清单入口核查业务结果。","MANUAL_REVIEW","仍需人工核查相关事实。");

    /** 返回可汇总的计数和判定；漏项及无报告计入条目分母，错误正文不能靠正确枚举抵消。 */
    @SuppressWarnings("unchecked")
    static Map<String,Object> assess(Map<String,Object> test,Map<String,Object> report,InvestigationSession session) {
        var expected=(List<String>)test.get("expectedReasons");int correct=0,supported=0,asserted=0,legal=0,total=0;boolean valid=true;
        try {
            if(report==null) throw new IllegalArgumentException("无报告");
            valid=report.keySet().equals(Set.of("summary","findings","unresolved"));asserted++;
            if("以下结论仅描述调查时取得的证据。本次调查只读，未执行修复、重发或状态核对写入。".equals(report.get("summary"))) supported++;else valid=false;
            var findings=(List<Map<String,Object>>)report.get("findings");valid&=findings.size()==expected.size();var seen=new HashSet<String>();
            for(var f:findings) {
                asserted++;String ref=(String)f.get("itemRef");int index=Integer.parseInt(ref.substring(1))-1;
                if(!ref.matches("I[1-9][0-9]*") || index<0 || index>=expected.size() || !seen.add(ref)) {valid=false;continue;}
                String reason=expected.get(index),certainty=(String)f.get("certainty");
                boolean itemValid=f.keySet().equals(Set.of("itemRef","reasonCode","certainty","explanation","evidenceIds","nextStep")) && reason.equals(f.get("reasonCode"));
                var allowed=Set.of("RESULT_UNKNOWN","REQUEST_NOT_FOUND").contains(reason)?Set.of("VERIFIED","INSUFFICIENT"):
                        Set.of("EVIDENCE_MISSING","UNDETERMINED").contains(reason)?Set.of("INSUFFICIENT"):Set.of("VERIFIED");
                itemValid&=allowed.contains(certainty);
                var cited=new ArrayList<InvestigationEvidenceStore.Evidence>();var ids=(List<String>)f.get("evidenceIds");var unique=new HashSet<String>();
                itemValid&=ids.size()<=4;
                for(String id:ids) {
                    total++;var e=session.evidence.get(id);
                    if(e!=null && unique.add(id) && (e.refs().isEmpty() || e.refs().contains(ref))) {legal++;cited.add(e);}else itemValid=false;
                }
                var input=((List<Map<String,Object>>)test.get("items")).get(index);
                boolean fact=supports(reason,ref,input,test,cited);
                if(input.get("lookup")!=null && !Boolean.TRUE.equals(test.get("missingRequest")))
                    fact&=has(cited,"MCP_LOOKUP",ref,"status",input.get("lookup"));
                itemValid&=fact;
                String prefix="VERIFIED".equals(certainty)?"证据支持：":"INSUFFICIENT".equals(certainty)?"证据有限：":"待验证线索：";
                boolean body=fact && Objects.equals(prefix+TEXT.get(reason),f.get("explanation"));
                if(body) supported++;itemValid&=body;
                itemValid&=Set.of("VIEW_EVIDENCE","USE_EXISTING_RECONCILE","RECHECK_CURRENT_RULES","MANUAL_REVIEW","NONE").contains(f.get("nextStep"));
                if("USE_EXISTING_RECONCILE".equals(f.get("nextStep")) && Set.of("FAILED","SKIPPED").contains(input.get("status"))) itemValid=false;
                if(itemValid) correct++;else valid=false;
            }
            var unresolved=(List<Map<String,Object>>)report.get("unresolved");var unresolvedRefs=new HashSet<String>();valid&=unresolved.size()<=expected.size();
            for(var u:unresolved) {
                asserted++;var topics=(List<String>)u.get("topics");
                boolean good=u.keySet().equals(Set.of("itemRef","topics","message")) && seen.contains(u.get("itemRef")) && unresolvedRefs.add((String)u.get("itemRef"))
                        && !topics.isEmpty() && topics.size()<=4 && new HashSet<>(topics).size()==topics.size() && TOPICS.keySet().containsAll(topics)
                        && Objects.equals(String.join("",topics.stream().map(TOPICS::get).toList()),u.get("message"));
                if(good) supported++;else valid=false;
            }
        } catch(RuntimeException malformed) {valid=false;}
        return Map.of("passed",valid && correct==expected.size(),"correctItems",correct,"expectedItems",expected.size(),
                "supportedFacts",supported,"assertedFacts",asserted,"legalCitations",legal,"totalCitations",total);
    }

    /** 状态必须同时符合题目预期和本轮引用证据，不能用模型结论充当事实来源。 */
    private static boolean supports(String reason,String ref,Map<String,Object> input,Map<String,Object> test,List<InvestigationEvidenceStore.Evidence> cited) {
        boolean local=has(cited,"ITEM_SNAPSHOT",ref,"status",input.get("status"));
        return switch(reason) {
            case "BUSINESS_REJECTED" -> ("FAILED".equals(input.get("status")) && local) || ("FAILED".equals(input.get("lookup")) && has(cited,"MCP_LOOKUP",ref,"status","FAILED"));
            case "PRECHECK_SKIPPED" -> "SKIPPED".equals(input.get("status")) && (local || has(cited,"EXECUTION_EVENT",ref,"outcome","SKIPPED"));
            case "REMOTE_SUCCESS_LOCAL_UNRESOLVED" -> Set.of("UNKNOWN","PENDING").contains(input.get("status")) && local && "SUCCESS".equals(input.get("lookup")) && has(cited,"MCP_LOOKUP",ref,"status","SUCCESS");
            case "RESULT_UNKNOWN" -> Set.of("UNKNOWN","PENDING").contains(input.get("status")) && (local || has(cited,"MCP_LOOKUP",ref,"status","UNKNOWN"));
            case "REQUEST_NOT_FOUND" -> "NOT_FOUND".equals(input.get("lookup")) && has(cited,"MCP_LOOKUP",ref,"status","NOT_FOUND");
            case "EVIDENCE_MISSING" -> (Boolean.TRUE.equals(test.get("missingRequest")) && has(cited,"MCP_LOOKUP",ref,"error","NO_REQUEST_ID"))
                    || (Boolean.TRUE.equals(test.get("missingRule")) && has(cited,"RULE_SNAPSHOT",ref,"missing",true))
                    || (Boolean.TRUE.equals(test.get("missingEvents")) && cited.stream().anyMatch(e -> e.type().equals("EXECUTION_EVENT") && Boolean.TRUE.equals(e.data().get("complete")) && Integer.valueOf(0).equals(e.data().get("total"))));
            case "UNDETERMINED" -> true;
            default -> false;
        };
    }
    private static boolean has(List<InvestigationEvidenceStore.Evidence> cited,String type,String ref,String field,Object value) {
        return cited.stream().filter(e -> type.equals(e.type())).anyMatch(e -> e.data().get("items") instanceof List<?> rows && rows.stream().anyMatch(v -> v instanceof Map<?,?> row && ref.equals(row.get("itemRef")) && Objects.equals(value,row.get(field))));
    }
}
