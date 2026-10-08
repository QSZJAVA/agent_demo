package com.example.report.assistant;

import com.example.report.catalog.CatalogEntry;
import com.example.report.semantic.DialogueState;
import java.util.List;
import java.util.Set;

/** 统一助手计划边界；真实实现只解析意图，测试实现可注入固定计划，任何实现均无业务执行权。 */
public interface AssistantPlanner {
    /** 解析来源用于审计；生产实现为MODEL，测试替身必须明确标示测试来源。 */
    default com.example.report.semantic.IntentParser.Source source() {return com.example.report.semantic.IntentParser.Source.MODEL;}
    /** 依据当前原文、有限成功查询上下文及授权目录生成完整计划，失败不得自动选择派单。 */
    AssistantPlan plan(String message,DialogueState state,List<CatalogEntry> reports,Set<String> companies);
    /** 以无写入副作用的业务校验检查草稿；真实模型实现可在最多两次草稿修正预算内使用反馈，其他实现只验证一次。 */
    default AssistantPlan plan(String message,DialogueState state,List<CatalogEntry> reports,Set<String> companies,
                               java.util.function.Consumer<AssistantPlan> validateDraft) {
        var plan=plan(message,state,reports,companies);validateDraft.accept(plan);return plan;
    }
    /** 统一任务规划使用真实对话、对象引用和只读执行反馈；其他实现仍须运行同一草稿业务校验。 */
    default AssistantPlan plan(String message,DialogueState state,List<CatalogEntry> reports,Set<String> companies,AssistantPlanningContext context) {
        var plan=plan(message,state,reports,companies);context.validateDraft().apply(plan);return plan;
    }
}
