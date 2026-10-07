package com.example.report.semantic;

import com.example.report.catalog.ReportRef;
import java.util.List;

/**
 * 语义解析边界：将用户本轮原文和有界上下文转换为意图；输出必须经 IntentCodec 校验，不能直接执行派单。
 */
public interface IntentParser {
    enum Source { MODEL, MOCK }
    /**
     * 语义意图和可审计的解析来源。
     * @param intent 通过结构和原文证据校验的语义意图
     * @param source 当前业务入口或解析来源标识
     */
    record Interpretation(SemanticIntent intent,Source source) { }
    /**
     * 受控的服务端业务上下文；不能扩大模型的执行能力。
     * @param state 当前会话权威语义状态，含请求范围与成功范围
     * @param reports 当前用户可见的报表引用集合
     * @param selectorsByReport 由当前授权目录声明的记录定位能力，不含客户数据
     * @param validateDraft 无副作用的计划一致性校验回调，不发送给模型，也不替代执行时权限校验
     * @param mentionedReportTerms 本轮原文中可见目录的实体候选词，仅用于覆盖检查
     * @param fieldsByReport 当前授权目录标量字段名称、类型和说明，不包含物理表名或实际记录值
     * @param currentSelection 当前有效预览的已选记录公开事实与完整数量；无可用预览时为空，不构成准备或执行授权
     */
    record Context(DialogueState state, List<ReportRef> reports, List<String> mentionedReportTerms, java.util.Map<String,List<String>> selectorsByReport,
                   java.util.function.Consumer<SemanticIntent> validateDraft, java.util.Map<String,List<com.example.report.catalog.query.FieldInfo>> fieldsByReport,
                   java.util.Map<String,Object> currentSelection) {
        public Context(DialogueState state,List<ReportRef> reports,List<String> mentions,java.util.Map<String,List<String>> selectors,
                       java.util.function.Consumer<SemanticIntent> validator,java.util.Map<String,List<com.example.report.catalog.query.FieldInfo>> fields) {
            this(state,reports,mentions,selectors,validator,fields,java.util.Map.of());
        }
        public Context(DialogueState state,List<ReportRef> reports,List<String> mentions,java.util.Map<String,List<String>> selectors,java.util.function.Consumer<SemanticIntent> validator) {
            this(state,reports,mentions,selectors,validator,java.util.Map.of());
        }
        public Context(DialogueState state,List<ReportRef> reports,List<String> mentions,java.util.Map<String,List<String>> selectors) {
            this(state,reports,mentions,selectors,intent -> { });
        }
        public Context(DialogueState state,List<ReportRef> reports,List<String> mentions) { this(state,reports,mentions,java.util.Map.of()); }
        public Context(DialogueState state,List<ReportRef> reports) { this(state,reports,List.of()); }
    }
    SemanticIntent parse(String message, Context context);
    default Interpretation interpret(String message,Context context) { return new Interpretation(parse(message,context),Source.MODEL); }
}
