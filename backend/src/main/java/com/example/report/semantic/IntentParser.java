package com.example.report.semantic;

import com.example.report.catalog.ReportRef;
import java.util.List;

/**
 * 语义解析边界：将用户本轮原文和有界上下文转换为意图；输出必须经 IntentCodec 校验，不能直接执行派单。
 */
public interface IntentParser {
    // DOMAIN is read-only compatibility for historical V1 sessions, never emitted by V2.
    enum Source { DOMAIN, MODEL, MOCK }
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
     * @param mentionedReportTerms 本轮原文中可见目录的实体候选词，仅用于覆盖检查
     */
    record Context(DialogueState state, List<ReportRef> reports, List<String> mentionedReportTerms) {
        public Context(DialogueState state,List<ReportRef> reports) { this(state,reports,List.of()); }
    }
    SemanticIntent parse(String message, Context context);
    default Interpretation interpret(String message,Context context) { return new Interpretation(parse(message,context),Source.MODEL); }
}
