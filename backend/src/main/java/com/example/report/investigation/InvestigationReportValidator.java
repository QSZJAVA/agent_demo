package com.example.report.investigation;

import com.example.report.common.JsonUtil;
import com.example.report.operations.SensitiveData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.util.*;

/** 校验模型的结构化结论与引用，校验后生成受控正文；不接收模型自由文本事实或已执行操作声明。 */
@Component
public class InvestigationReportValidator {
    private static final Set<String> REASONS=Set.of("BUSINESS_REJECTED","PRECHECK_SKIPPED","RESULT_UNKNOWN","REMOTE_SUCCESS_LOCAL_UNRESOLVED","REQUEST_NOT_FOUND","EVIDENCE_MISSING","UNDETERMINED");
    private static final Set<String> CERTAINTIES=Set.of("VERIFIED","HYPOTHESIS","INSUFFICIENT");
    private static final Set<String> NEXT=Set.of("VIEW_EVIDENCE","USE_EXISTING_RECONCILE","RECHECK_CURRENT_RULES","MANUAL_REVIEW","NONE");
    public static String schema() {
        var finding=object(Map.of("itemRef",string(32),"reasonCode",enumeration(REASONS),"certainty",enumeration(CERTAINTIES),"evidenceIds",Map.of("type","array","maxItems",4,"items",string(32)),"nextStep",enumeration(NEXT)));
        var unresolved=object(Map.of("itemRef",string(32),"topics",Map.of("type","array","minItems",1,"maxItems",4,"uniqueItems",true,"items",enumeration(InvestigationReportText.TOPICS.keySet()))));
        return InvestigationJson.canonical(object(Map.of("findings",Map.of("type","array","minItems",1,"maxItems",10,"items",finding),"unresolved",Map.of("type","array","maxItems",10,"items",unresolved))));
    }
    private static Map<String,Object> string(int max) {return Map.of("type","string","minLength",1,"maxLength",max);}
    private static Map<String,Object> enumeration(Set<String> values) {return Map.of("type","string","enum",values.stream().sorted().toList());}
    private static Map<String,Object> object(Map<String,Object> fields) {return Map.of("type","object","properties",fields,"required",fields.keySet().stream().sorted().toList(),"additionalProperties",false);}
    /** 最多4096输出token的当前报告；未知字段、漏项、伪造引用和相互矛盾事实整份拒绝。 */
    public Map<String,Object> validate(String json,String finishReason,InvestigationSession s) {
        try {
            if(json==null || json.length()>30000 || "length".equals(finishReason)) throw invalid("报告输出被截断或超过体积上限，须重新生成完整报告");
            var mapper=JsonUtil.MAPPER.copy().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var root=mapper.readTree(json);keys(root,Set.of("findings","unresolved"));
            var findings=root.get("findings");var unresolved=root.get("unresolved");
            if(!findings.isArray() || findings.size()!=s.items.size()) throw invalid("本次findings必须恰好"+s.items.size()+"项，每个目标itemRef一次");
            if(!unresolved.isArray()) throw invalid("unresolved必须为数组，无待查事项时为[]");
            if(unresolved.size()>s.items.size()) throw invalid("本次unresolved最多"+s.items.size()+"项，同一itemRef的多个待查事项必须合并为一项");
            var seen=new HashSet<String>();
            for(var f:findings) {
                keys(f,Set.of("itemRef","reasonCode","certainty","evidenceIds","nextStep"));
                String ref=text(f,"itemRef",32);s.item(ref);if(!seen.add(ref)) throw invalid();
                String reason=text(f,"reasonCode",40),certainty=text(f,"certainty",24),next=text(f,"nextStep",40);
                if(!REASONS.contains(reason)) throw invalid("reasonCode只能使用Schema列出的原因枚举");
                if(!CERTAINTIES.contains(certainty)) throw invalid("certainty只能为VERIFIED、HYPOTHESIS、INSUFFICIENT");
                if(!NEXT.contains(next)) throw invalid("nextStep只能为VIEW_EVIDENCE、USE_EXISTING_RECONCILE、RECHECK_CURRENT_RULES、MANUAL_REVIEW、NONE");
                var refs=f.get("evidenceIds");if(!refs.isArray() || refs.size()>4) throw invalid("每条evidenceIds最多4个，只保留支持该结论的引用");
                var cited=new ArrayList<InvestigationEvidenceStore.Evidence>();var unique=new HashSet<String>();
                for(var e:refs) {
                    if(!e.isTextual() || !unique.add(e.asText())) throw invalid();
                    var evidence=s.evidence.get(e.asText());
                    if(evidence==null || (!evidence.refs().isEmpty() && !evidence.refs().contains(ref))) throw invalid("证据引用必须是本轮实际已取得的E编号且属于本条目");cited.add(evidence);
                }
                // 状态和核对结论只能从当前引用事实推出；不能仅凭一句模型解释授予“已核实”。
                // 已观测的当前状态和明确远端结果不能被缺规则、历史事件或UNDETERMINED隐藏。
                String required=requiredObservedReason(ref,s);
                if(required!=null && !required.equals(reason)) throw invalid("条目"+ref+"已有明确状态事实，应使用"+required+"并引用本次对应证据；缺少规则或历史信息记入unresolved。"+requiredCitationHint(required,ref,s));
                if("PRECHECK_SKIPPED".equals(reason) && !"SKIPPED".equals(s.item(ref).get("status")))
                    throw invalid("条目"+ref+"当前并非SKIPPED，旧执行事件不能说明本次执行复核跳过");
                if("RESULT_UNKNOWN".equals(reason)) {
                    // 不能通过省略已取得核对证据，把明确成功、失败、未查到请求或缺请求号重新描述为仅有UNKNOWN。
                    for(var evidence:s.evidence.values()) if("MCP_LOOKUP".equals(evidence.type()) && evidence.data().get("items") instanceof List<?> rows)
                        for(var value:rows) if(value instanceof Map<?,?> row && ref.equals(row.get("itemRef"))) {
                            String status=Objects.toString(row.get("status"),"");
                            if("FAILED".equals(status)) throw invalid("本次MCP核对FAILED是明确业务失败，即使错误码为RECORD_CHANGED也应使用BUSINESS_REJECTED/VERIFIED并引用该核对证据，不能报告RESULT_UNKNOWN");
                            if("SUCCESS".equals(status)) throw invalid("本次MCP核对SUCCESS且本地未明，应使用REMOTE_SUCCESS_LOCAL_UNRESOLVED/VERIFIED并引用条目及核对证据");
                            if("NOT_FOUND".equals(status)) throw invalid("本次MCP核对NOT_FOUND，应使用REQUEST_NOT_FOUND并引用该核对证据，不代表可以重发");
                            if("NO_REQUEST_ID".equals(row.get("error"))) throw invalid("缺原请求号导致不能核对，应使用EVIDENCE_MISSING/INSUFFICIENT并引用缺失证据");
                        }
                }
                var currentEvidence=cited.stream().filter(e -> !"EXECUTION_EVENT".equals(e.type())).toList();
                // 只有与冻结快照相同尝试次数的事件能支持当前状态，历史尝试保留为追溯证据。
                boolean skippedEvent="PRECHECK_SKIPPED".equals(reason) && cited.stream().filter(e -> "EXECUTION_EVENT".equals(e.type()))
                        .anyMatch(e -> e.data().get("items") instanceof List<?> rows && rows.stream().anyMatch(v -> v instanceof Map<?,?> row
                                && ref.equals(row.get("itemRef")) && "SKIPPED".equals(row.get("outcome")) && Objects.equals(row.get("attemptCount"),s.item(ref).get("attemptCount"))));
                if(!"UNDETERMINED".equals(reason) && !(skippedEvent || supported(reason,ref,"PRECHECK_SKIPPED".equals(reason)?currentEvidence:cited))) throw invalid(unsupportedMessage(reason,ref,s));
                // 核实程度针对枚举所表达的状态事实，不针对尚未解释的规则因果；与独立评分契约一致。
                if(Set.of("BUSINESS_REJECTED","PRECHECK_SKIPPED","REMOTE_SUCCESS_LOCAL_UNRESOLVED").contains(reason) && !"VERIFIED".equals(certainty))
                    throw invalid("条目"+ref+"的"+reason+"已有直接状态证据，保留原因并将certainty设为VERIFIED；缺少因果说明记入unresolved，不降低已核实状态事实");
                if(Set.of("EVIDENCE_MISSING","UNDETERMINED").contains(reason) && !"INSUFFICIENT".equals(certainty))
                    throw invalid("条目"+ref+"应保留"+reason+"，certainty必须为INSUFFICIENT，不能通过改原因回避缺少证据");
                if(Set.of("RESULT_UNKNOWN","REQUEST_NOT_FOUND").contains(reason) && "HYPOTHESIS".equals(certainty))
                    throw invalid("已查询到的未知或未找到状态应使用VERIFIED或INSUFFICIENT，不是HYPOTHESIS");
                if("VERIFIED".equals(certainty) && (cited.isEmpty() || Set.of("UNDETERMINED","EVIDENCE_MISSING").contains(reason)))
                    throw invalid("条目"+ref+"的"+reason+"不能标记VERIFIED；若原因是EVIDENCE_MISSING或UNDETERMINED，保留该原因并将certainty改为INSUFFICIENT，不要改成RESULT_UNKNOWN来回避缺证据事实");
                if("HYPOTHESIS".equals(certainty) && cited.isEmpty()) throw invalid("HYPOTHESIS须引用已有相关线索，没有线索应使用INSUFFICIENT");
                // 远端明确失败、本地仍未明时，原清单核对入口也负责把本地UNKNOWN收尾为FAILED；建议仍不执行任何写入。
                boolean failedNeedsReconcile="BUSINESS_REJECTED".equals(reason) && Set.of("UNKNOWN","PENDING").contains(Objects.toString(s.item(ref).get("status"),""));
                if("USE_EXISTING_RECONCILE".equals(next) && !failedNeedsReconcile && !Set.of("RESULT_UNKNOWN","REQUEST_NOT_FOUND","REMOTE_SUCCESS_LOCAL_UNRESOLVED","UNDETERMINED","EVIDENCE_MISSING").contains(reason)) throw invalid("本地已经明确失败或复核跳过，应核查证据、规则或人工复核；无本地未明状态可供核对");
            }
            var unresolvedRefs=new HashSet<String>();
            for(var u:unresolved) {
                keys(u,Set.of("itemRef","topics"));String ref=text(u,"itemRef",32);s.item(ref);
                if(!unresolvedRefs.add(ref)) throw invalid("同一itemRef的多个待查事项必须合并为一项");
                var topics=u.get("topics");var distinct=new HashSet<String>();
                if(!topics.isArray() || topics.isEmpty() || topics.size()>4) throw invalid("topics必须包含1～4个待查事项枚举");
                for(var topic:topics) if(!topic.isTextual() || !InvestigationReportText.TOPICS.containsKey(topic.asText()) || !distinct.add(topic.asText())) throw invalid("topics只能使用不同的待查事项枚举");
            }
            @SuppressWarnings("unchecked") var report=(Map<String,Object>)JsonUtil.MAPPER.convertValue(SensitiveData.value(root),Map.class);return InvestigationReportText.render(report);
        } catch(InvestigationFailure e) {throw e;}
        catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw invalid("报告必须是完整JSON对象，禁止Markdown围栏、注释和尾随说明");}
        catch(Exception e) {throw invalid();}
    }
    /** 只约束模型已取得的当前状态事实；补充证据缺失不抹掉明确失败或复核跳过。 */
    private static String requiredObservedReason(String ref,InvestigationSession s) {
        for(var e:s.evidence.values()) if("ITEM_SNAPSHOT".equals(e.type()) && e.data().get("items") instanceof List<?> rows)
            for(var v:rows) if(v instanceof Map<?,?> row && ref.equals(row.get("itemRef")) && Objects.equals(row.get("status"),s.item(ref).get("status"))) {
                if("SKIPPED".equals(row.get("status"))) return "PRECHECK_SKIPPED";
                if("FAILED".equals(row.get("status")) && row.get("errorCode")!=null && !row.get("errorCode").toString().matches(".*(TIMEOUT|TRANSPORT|UNKNOWN).*")) return "BUSINESS_REJECTED";
            }
        if(!Set.of("UNKNOWN","PENDING").contains(Objects.toString(s.item(ref).get("status"),""))) return null;
        String required=null;
        for(var e:s.evidence.values()) if("MCP_LOOKUP".equals(e.type()) && e.data().get("items") instanceof List<?> rows)
            for(var v:rows) if(v instanceof Map<?,?> row && ref.equals(row.get("itemRef"))) {
                String reason=switch(Objects.toString(row.get("status"),"")) {case "SUCCESS" -> "REMOTE_SUCCESS_LOCAL_UNRESOLVED";case "FAILED" -> "BUSINESS_REJECTED";case "NOT_FOUND" -> "REQUEST_NOT_FOUND";default -> null;};
                if(reason!=null) required=reason;
            }
        return required;
    }
    public static boolean supported(String reason,String ref,List<InvestigationEvidenceStore.Evidence> evidence) {
        boolean failed=false,skipped=false,unknown=false,localUnknown=false,remoteSuccess=false,notFound=false,missing=false;
        for(var e:evidence) {
            Object source=e.data().get("items");if(!(source instanceof List<?> rows)) continue;
            for(var value:rows) {
                if(!(value instanceof Map<?,?> row) || !ref.equals(row.get("itemRef"))) continue;
                String status=Objects.toString(row.get("status"),Objects.toString(row.get("outcome"),""));
                if("ITEM_SNAPSHOT".equals(e.type())) {
                    failed|="FAILED".equals(status) && row.get("errorCode")!=null && !Objects.toString(row.get("errorCode")).matches(".*(TIMEOUT|TRANSPORT|UNKNOWN).*");
                    skipped|="SKIPPED".equals(status);localUnknown|=Set.of("UNKNOWN","PENDING").contains(status);unknown|=localUnknown;
                }
                if("EXECUTION_EVENT".equals(e.type())) skipped|="SKIPPED".equals(status);
                if("MCP_LOOKUP".equals(e.type())) {failed|="FAILED".equals(status);remoteSuccess|="SUCCESS".equals(status);unknown|="UNKNOWN".equals(status);notFound|="NOT_FOUND".equals(status);missing|=row.containsKey("error");}
                if("RULE_SNAPSHOT".equals(e.type())) missing|=Boolean.TRUE.equals(row.get("missing"));
            }
            if("EXECUTION_EVENT".equals(e.type())) missing|=Boolean.TRUE.equals(e.data().get("complete")) && ((Number)e.data().getOrDefault("total",-1)).intValue()==0;
        }
        return switch(reason) {
            case "BUSINESS_REJECTED" -> failed;
            case "PRECHECK_SKIPPED" -> skipped;
            case "RESULT_UNKNOWN" -> unknown && !remoteSuccess && !failed;
            // 先UNKNOWN后SUCCESS的两次远端查询不能冒充本地未明证据。
            case "REMOTE_SUCCESS_LOCAL_UNRESOLVED" -> remoteSuccess && localUnknown;
            case "REQUEST_NOT_FOUND" -> notFound;
            case "EVIDENCE_MISSING" -> missing;
            default -> false;
        };
    }
    /** 反馈只引用已取得的结构化状态，避免模型重试时继续把冲突的原始说明当成成功证据。 */
    private static String unsupportedMessage(String reason,String ref,InvestigationSession s) {
        var observations=new ArrayList<String>();
        for(var entry:s.evidence.entrySet()) {
            var evidence=entry.getValue();if(!"MCP_LOOKUP".equals(evidence.type()) || !(evidence.data().get("items") instanceof List<?> rows)) continue;
            for(var value:rows) if(value instanceof Map<?,?> row && ref.equals(row.get("itemRef")))
                observations.add(entry.getKey()+":"+Objects.toString(row.get("status"),Objects.toString(row.get("error"),"缺状态")));
        }
        String required=requiredObservedReason(ref,s);
        String correction=required==null
                ?"以远端status枚举为准，message不覆盖状态；远端UNKNOWN应报告RESULT_UNKNOWN，缺少远端SUCCESS时不能报告REMOTE_SUCCESS_LOCAL_UNRESOLVED。"
                :requiredCitationHint(required,ref,s);
        return "条目"+ref+"的原因枚举"+reason+"缺少直接支持的引用。本次已取得远端状态："+observations+"。"+correction;
    }
    /**
     * 引用修复只提示本轮、同条目的已校验最小证据组合，不生成或自动接受报告。
     * 当前明确状态至多需要一条状态证据，远端成功且本地未明需要两种来源；不把旧事件或其他条目当作替代证据。
     */
    private static String requiredCitationHint(String reason,String ref,InvestigationSession s) {
        var candidates=s.evidence.entrySet().stream().filter(e -> e.getValue().refs().contains(ref)
                && Set.of("ITEM_SNAPSHOT","MCP_LOOKUP").contains(e.getValue().type())).toList();
        for(var one:candidates) if(supported(reason,ref,List.of(one.getValue())))
            return citationCorrection(reason,List.of(one.getKey()));
        for(int a=0;a<candidates.size();a++) for(int b=a+1;b<candidates.size();b++) {
            var one=candidates.get(a);var two=candidates.get(b);
            if(supported(reason,ref,List.of(one.getValue(),two.getValue())))
                return citationCorrection(reason,List.of(one.getKey(),two.getKey()));
        }
        return "当前明确状态要求"+reason+"，但尚无完整支持引用，不能猜测证据编号或改变已核实状态来回避缺证据。";
    }
    private static String citationCorrection(String reason,List<String> refs) {
        return "本条目已有可直接支持"+reason+"的证据组合"+refs+"，请使用这些引用并保持该原因；引用不完整应补齐引用，不能把已核实状态降级为RESULT_UNKNOWN或UNDETERMINED。";
    }
    private static String text(JsonNode node,String key,int max) {var value=node.get(key);if(value==null || !value.isTextual() || value.asText().isBlank() || value.asText().length()>max) throw invalid("字段"+key+"须为1～"+max+"字符的文本");return value.asText();}
    private static void keys(JsonNode node,Set<String> keys) {String message="对象须完整且只包含这些字段："+String.join(",",keys.stream().sorted().toList());if(node==null || !node.isObject() || node.size()!=keys.size()) throw invalid(message);node.fieldNames().forEachRemaining(key -> {if(!keys.contains(key)) throw invalid(message);});}
    private static InvestigationFailure invalid() {return new InvestigationFailure("REPORT_INVALID","模型报告未通过范围、引用或事实校验");}
    private static InvestigationFailure invalid(String message) {return new InvestigationFailure("REPORT_INVALID",message);}
}
