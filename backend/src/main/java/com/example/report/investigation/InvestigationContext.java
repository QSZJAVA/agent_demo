package com.example.report.investigation;

import com.example.report.common.JsonUtil;
import org.springframework.ai.chat.messages.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 运行内上下文整理器；以已落库证据生成结构化笔记，不把模型摘要当事实，不跨任务复用记忆。 */
public final class InvestigationContext {
    public static final String VERSION="context-v2";
    private InvestigationContext() { }

    /** 计算逻辑消息UTF-8体积；实际HTTP请求仍由传输层独立检查，包括Schema和工具定义。 */
    public static int bytes(List<Message> messages) {
        return JsonUtil.toJson(messages.stream().map(m -> Map.of("role",m.getMessageType().name(),
                "text",Objects.toString(m.getText(),""),"toolCalls",m instanceof AssistantMessage a?a.getToolCalls():List.of(),
                "toolResponses",m instanceof ToolResponseMessage t?t.getResponses():List.of())).toList()).getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 仅在完整工具响应轮次之间压缩，保留系统约束、原问题和最近一组调用/结果，避免协议孤儿消息。
     * 笔记只能描述已取得证据；完整内容仍由evidence_read按需读取，关键状态不因旧日志淘汰而消失。
     */
    public static boolean compact(List<Message> messages,InvestigationSession s,int threshold) {
        if(messages.size()<6 || bytes(messages)<=threshold) return false;
        var recent=new ArrayList<>(messages.subList(messages.size()-2,messages.size()));
        if(!(recent.get(0) instanceof AssistantMessage) || !(recent.get(1) instanceof ToolResponseMessage)) return false;
        var candidate=new ArrayList<Message>();candidate.add(messages.get(0));candidate.add(messages.get(1));
        candidate.add(new UserMessage(InvestigationJson.canonical(notes(s))));candidate.addAll(recent);
        if(bytes(candidate)>=bytes(messages)) return false;
        messages.clear();messages.addAll(candidate);
        return true;
    }

    /** 保存证据索引、明确状态、缺失与分页信息；规则/日志正文按需加载，不能把裁剪内容解释为完整证据。 */
    public static Map<String,Object> notes(InvestigationSession s) {
        return Map.of("kind","PROGRAM_EVIDENCE_NOTES","version",VERSION,"executionVersion",s.snapshot.get("executionVersion"),
                "memoryScope","CURRENT_RUN_ONLY","evidence",project(s),"remainingToolCalls",s.budget.toolsLeft(),
                "lookupAttempts",new TreeMap<>(s.lookupAttempts),"collectionStop",Objects.toString(s.partialReason,"NORMAL"),
                // 有界目录按取得顺序排列；长事件投影中仍能直接定位E编号与真实来源，不能把规则当作事件回读。
                "availableEvidence",s.evidence.entrySet().stream().map(e -> Map.of("evidenceId",e.getKey(),"type",e.getValue().type(),
                        "itemRefs",e.getValue().refs(),"detailsOnDemand",!Set.of("ITEM_SNAPSHOT","MCP_LOOKUP").contains(e.getValue().type()))).toList());
    }

    /** 报告使用同一事实投影；保留每一条远端观测，不能只保留最后一句摘要掩盖冲突。 */
    public static Map<String,Object> project(InvestigationSession s) {
        var result=new LinkedHashMap<String,Object>();
        s.evidence.forEach((id,e) -> {
            var data=new LinkedHashMap<String,Object>();
            if(Set.of("ITEM_SNAPSHOT","MCP_LOOKUP").contains(e.type())) data.putAll(e.data());
            else {
                for(String k:List.of("complete","total","sourceTotal","nextCursor","status","selectedCount"))
                    if(e.data().containsKey(k)) data.put(k,e.data().get(k));
                if(e.data().get("items") instanceof List<?> rows) data.put("items",rows.stream().map(value -> {
                    var row=new LinkedHashMap<String,Object>();
                    if(value instanceof Map<?,?> original) for(String k:List.of("itemRef","missing","outcome","attemptCount","type","at"))
                        if(original.containsKey(k)) row.put(k,original.get(k));
                    return row;
                }).toList());
                data.put("detailsOnDemand",true);
            }
            result.put(id,Map.of("type",e.type(),"refs",e.refs(),"data",data,"truncated",e.truncated()));
        });
        return result;
    }
}
