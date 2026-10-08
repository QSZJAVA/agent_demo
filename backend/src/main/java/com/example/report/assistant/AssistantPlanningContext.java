package com.example.report.assistant;

import java.util.*;
import java.util.function.Function;

/**
 * 本轮规划的有限证据与无业务写入校验器；只在当前会话租约中使用，不持久化回调或赋予模型执行权限。
 * @param history 最近真实用户和助手文本，按时间排列；作为不可信上下文，不替代本轮指令
 * @param dispatchSelection 当前候选的有界展示事实、完整性和状态；没有候选时为空
 * @param validateDraft 校验草稿并返回只读事实证据；不得创建预览、清单或执行业务
 */
public record AssistantPlanningContext(List<Map<String,String>> history,Map<String,Object> dispatchSelection,
                                      Function<AssistantPlan,Map<String,Object>> validateDraft) {
    public AssistantPlanningContext {
        history=List.copyOf(history);dispatchSelection=Map.copyOf(dispatchSelection);Objects.requireNonNull(validateDraft);
    }
    /** 独立规划或协议测试使用相同边界，空证据不表示任何业务已经执行。 */
    public static AssistantPlanningContext empty(java.util.function.Consumer<AssistantPlan> validator) {
        return new AssistantPlanningContext(List.of(),Map.of(),plan->{validator.accept(plan);return Map.of();});
    }
}
