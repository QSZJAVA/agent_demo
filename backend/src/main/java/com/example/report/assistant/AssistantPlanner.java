package com.example.report.assistant;

import com.example.report.catalog.CatalogEntry;
import com.example.report.semantic.DialogueState;
import java.util.List;
import java.util.Set;

/** 统一助手计划边界；真实实现只解析意图，测试实现可注入固定计划，任何实现均无业务执行权。 */
public interface AssistantPlanner {
    /** 依据当前原文、有限成功查询上下文及授权目录生成完整计划，失败不得自动选择派单。 */
    AssistantPlan plan(String message,DialogueState state,List<CatalogEntry> reports,Set<String> companies);
}
