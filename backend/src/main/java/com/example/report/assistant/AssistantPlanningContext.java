package com.example.report.assistant;

import java.util.*;
import java.util.function.Function;

/**
 * 本轮规划的有限证据与无业务写入校验器；只在当前会话租约中使用，不持久化回调或赋予模型执行权限。
 * @param history 最近真实用户和助手文本，按时间排列；作为不可信上下文，不替代本轮指令
 * @param dispatchSelection 当前候选的有界展示事实、完整性和状态；没有候选时为空
 * @param validateDraft 校验草稿并返回只读事实证据；不得创建预览、清单或执行业务
 * @param dictionary 已按当前权限限定的业务词典与共同时间事实，不是系统指令
 * @param diagnostics 当前轮阶段计量，不进入模型输入或跨轮共享
 * @param catalog 当前调用的授权目录，仅用于候选身份校验，不整体序列化适配器
 * @param resolvedClarification 本轮经服务端核验的已完成选择；原问题的未决措辞不能覆盖这些事实
 * @param requestedQuery 用户通过条件编辑器明确提交的完整查询，已核对当前引用和字段类型；普通文本请求为空
 * @param analysisContext 本轮服务器核验的分析能力及当前成功查询快照元数据；三个模型阶段共用，不包含凭据或任意快照选择权
 */
public record AssistantPlanningContext(List<Map<String,String>> history,Map<String,Object> dispatchSelection,
                                      Function<AssistantPlan,Map<String,Object>> validateDraft,Map<String,Object> dictionary,PlanningDiagnostics diagnostics,java.util.List<com.example.report.catalog.CatalogEntry> catalog,Map<String,Object> resolvedClarification,BusinessQuery requestedQuery,Map<String,Object> analysisContext) {
    public AssistantPlanningContext {
        history=List.copyOf(history);dispatchSelection=Map.copyOf(dispatchSelection);Objects.requireNonNull(validateDraft);
        dictionary=Map.copyOf(dictionary);Objects.requireNonNull(diagnostics);
        catalog=List.copyOf(catalog);resolvedClarification=Map.copyOf(resolvedClarification);analysisContext=Map.copyOf(analysisContext);
    }
    /** 服务端未提供分析接续上下文时使用明确空集合；空集合不授权任何查询引用。 */
    public AssistantPlanningContext(List<Map<String,String>> history,Map<String,Object> dispatchSelection,Function<AssistantPlan,Map<String,Object>> validateDraft,Map<String,Object> dictionary,PlanningDiagnostics diagnostics,List<com.example.report.catalog.CatalogEntry> catalog,Map<String,Object> resolvedClarification,BusinessQuery requestedQuery){this(history,dispatchSelection,validateDraft,dictionary,diagnostics,catalog,resolvedClarification,requestedQuery,Map.of());}
    /** 无额外业务词典的程序调用仍使用独立轮次计量；不更改模型JSON契约。 */
    public AssistantPlanningContext(List<Map<String,String>> history,Map<String,Object> dispatchSelection,Function<AssistantPlan,Map<String,Object>> validateDraft){this(history,dispatchSelection,validateDraft,Map.of(),new PlanningDiagnostics());}
    /** 服务端建立本轮上下文后，规划器绑定同一批已授权目录。 */
    public AssistantPlanningContext(List<Map<String,String>> history,Map<String,Object> dispatchSelection,Function<AssistantPlan,Map<String,Object>> validateDraft,Map<String,Object> dictionary,PlanningDiagnostics diagnostics){this(history,dispatchSelection,validateDraft,dictionary,diagnostics,List.of());}
    public AssistantPlanningContext(List<Map<String,String>> history,Map<String,Object> dispatchSelection,Function<AssistantPlan,Map<String,Object>> validateDraft,Map<String,Object> dictionary,PlanningDiagnostics diagnostics,List<com.example.report.catalog.CatalogEntry> catalog){this(history,dispatchSelection,validateDraft,dictionary,diagnostics,catalog,Map.of(),null);}
    public AssistantPlanningContext withCatalog(List<com.example.report.catalog.CatalogEntry> entries){return new AssistantPlanningContext(history,dispatchSelection,validateDraft,dictionary,diagnostics,entries,resolvedClarification,requestedQuery,analysisContext);}
    public AssistantPlanningContext withResolvedClarification(Map<String,Object> resolved){return new AssistantPlanningContext(history,dispatchSelection,validateDraft,dictionary,diagnostics,catalog,resolved,requestedQuery,analysisContext);}
    /** 结构化输入属于用户本轮明确要求，三个模型阶段共享；它不是另一阶段的草稿，也不能跳过独立复核。 */
    public AssistantPlanningContext withRequestedQuery(BusinessQuery requested){return new AssistantPlanningContext(history,dispatchSelection,validateDraft,dictionary,diagnostics,catalog,resolvedClarification,requested,analysisContext);}
    /** 在外层只读预检后绑定同一份服务器快照元数据；返回新上下文，保留原校验回调、词典和轮次计量。 */
    public AssistantPlanningContext withAnalysisContext(Map<String,Object> context){return new AssistantPlanningContext(history,dispatchSelection,validateDraft,dictionary,diagnostics,catalog,resolvedClarification,requestedQuery,context);}
    /** 独立规划或协议测试使用相同边界，空证据不表示任何业务已经执行。 */
    public static AssistantPlanningContext empty(java.util.function.Consumer<AssistantPlan> validator) {
        return new AssistantPlanningContext(List.of(),Map.of(),plan->{validator.accept(plan);return Map.of();});
    }
    /**
     * 已持久化的本轮消息只保留一个证据位置（-1）；移除末尾重复项，保留更早的重复发言及其原下标。
     * @param message 本轮完整用户原文，不通过模糊相似判断或重排历史消息
     * @return 仅含此前对话的规划上下文；不修改业务选择或校验回调
     */
    public AssistantPlanningContext beforeCurrentMessage(String message) {
        if(history.isEmpty())return this;
        var last=history.get(history.size()-1);
        return "user".equals(last.get("role")) && Objects.equals(message,last.get("content"))
                ?new AssistantPlanningContext(history.subList(0,history.size()-1),dispatchSelection,validateDraft,dictionary,diagnostics,catalog,resolvedClarification,requestedQuery,analysisContext):this;
    }
}
