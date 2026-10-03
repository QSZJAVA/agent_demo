package com.example.report.semantic;

import java.util.ArrayList;
import java.util.List;

/**
 * V2结构化业务意图；模型不能执行派单或指定SQL、身份及清单标识。
 * @param version 结构化语义协议版本，当前真实模型链路使用V2
 * @param action 本次业务动作，必须属于协议允许的动作集合
 * @param scopeChanges 按协议顺序应用的公司、报表和记录范围修改
 * @param restrictions 仅本轮有效的明确业务禁止，不继承到后续轮次
 * @param clarify 需要用户澄清的字段，NONE表示无澄清要求
 */
public record SemanticIntent(int version, Action action, List<ScopeChange> scopeChanges,
                             List<Restriction> restrictions, Clarify clarify) {
    public enum Action { PREVIEW, PREPARE_DISPATCH, CANCEL_PLAN, SHOW_RESULT, EXPLAIN_RULES, CLARIFY, HELP }
    public enum Target { COMPANY, REPORTS, RECORDS }
    // KEEP仅供内部兼容处理；当前模型协议中省略某个目标即表示保持原范围。
    public enum Operation { KEEP, REPLACE, ADD, REMOVE, CLEAR }
    public enum Clarify { NONE, COMPANY, REPORTS, RECORDS, ACTION }
    public enum RestrictionScope { THIS_TURN }
    /**
     * 范围或配置修改请求，保存前须校验相应协议。
     * @param operation KEEP、REPLACE、ADD、REMOVE或CLEAR；KEEP仅为服务端内部兼容表达
     * @param mentions 本轮原文实体片段，必须出现在对应evidence中
     * @param evidence 支撑该修改或禁止的连续原文证据
     */
    public record Change(Operation operation, List<String> mentions, String evidence) {
        public static Change keep() { return new Change(Operation.KEEP, List.of(), ""); }
    }
    /**
     * 本轮语义范围修改及其原文证据。
     * @param target COMPANY、REPORTS或RECORDS范围目标
     * @param operation REPLACE、ADD、REMOVE或CLEAR，须符合目标类型允许的操作
     * @param mentions 本轮原文实体片段，必须出现在对应evidence中
     * @param evidence 支撑该修改或禁止的连续原文证据
     */
    public record ScopeChange(Target target, Operation operation, List<String> mentions, String evidence) {
        public Change change() { return new Change(operation, mentions, evidence); }
    }
    /**
     * An explicit prohibition, scoped to this utterance rather than future turns.
     * @param action 本次业务动作，必须属于协议允许的动作集合
     * @param scope 业务禁止生效范围，当前只允许THIS_TURN
     * @param evidence 支撑该修改或禁止的连续原文证据
     */
    public record Restriction(Action action, RestrictionScope scope, String evidence) { }
    /** Convenience for deterministic callers and reading old persisted intents, never a second wire protocol. */
    public SemanticIntent(int version, Action action, Change company, Change reports, Change exclusions, Clarify clarify) {
        this(version, action, changes(company, reports, exclusions), List.of(), clarify);
    }
    private static List<ScopeChange> changes(Change company, Change reports, Change exclusions) {
        var result = new ArrayList<ScopeChange>();
        var values = List.of(company, reports, exclusions);
        for (int i=0; i<values.size(); i++) {
            var c=values.get(i);
            if (c.operation()!=Operation.KEEP || !c.mentions().isEmpty() || !c.evidence().isEmpty())
                result.add(new ScopeChange(Target.values()[i], c.operation(), c.mentions(), c.evidence()));
        }
        return List.copyOf(result);
    }
    public List<Change> changesFor(Target target) {
        return scopeChanges.stream().filter(c -> c.target()==target).map(ScopeChange::change).toList();
    }
    public boolean changes(Target target) { return scopeChanges.stream().anyMatch(c -> c.target()==target); }
    public boolean forbids(Action requested) { return restrictions.stream().anyMatch(r -> r.action()==requested); }
    public static SemanticIntent clarify(Clarify slot) {
        return new SemanticIntent(2, Action.CLARIFY, List.of(), List.of(), slot);
    }
}
