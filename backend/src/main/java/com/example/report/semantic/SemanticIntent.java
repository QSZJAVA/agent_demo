package com.example.report.semantic;

import java.util.ArrayList;
import java.util.List;

/**
 * V1结构化业务意图；模型不能执行派单或指定SQL、身份及清单标识。
 * @param version 结构化语义协议版本，当前真实模型链路使用V1
 * @param action 本次业务动作，必须属于协议允许的动作集合
 * @param scopeChanges 候选公司、报表和记录修改；CLARIFY仅留证不应用，其他动作按协议顺序执行
 * @param restrictions 仅本轮有效的明确业务禁止，不继承到后续轮次
 * @param reportConstraints 本轮报表实体的最终语义角色，服务端核对实际范围而非强制每个实体对应一次操作
 * @param unsupportedConditions 本轮无法表达的条件原文；非空时只能澄清，不执行部分请求
 * @param clarify 需要用户澄清的字段，NONE表示无澄清要求
 */
public record SemanticIntent(int version, Action action, List<ScopeChange> scopeChanges,
                             List<Restriction> restrictions, List<ReportConstraint> reportConstraints, List<String> unsupportedConditions, Clarify clarify) {
    /** 当前唯一语义协议版本；重新编号后的V1包含全部字段筛选能力，不解析历史协议。 */
    public static final int VERSION = 1;
    public enum Action { PREVIEW, PREPARE_DISPATCH, CANCEL_PLAN, SHOW_RESULT, EXPLAIN_RULES, CLARIFY, HELP }
    /** 仅用于禁止声明；EXECUTE_DISPATCH表达不得提交执行，不能成为可执行Action或调用确认接口。 */
    public enum ForbiddenAction { PREVIEW, PREPARE_DISPATCH, CANCEL_PLAN, SHOW_RESULT, EXPLAIN_RULES, HELP, EXECUTE_DISPATCH }
    public enum Target { COMPANY, REPORTS, RECORDS }
    // KEEP/CLEAR仅用于服务端集合求值；模型范围明确使用ALL_AUTHORIZED，记录使用直接的排除与恢复语义。
    public enum Operation { KEEP, REPLACE, ADD, REMOVE, CLEAR, ALL_AUTHORIZED, EXCLUDE, RESTORE, REPLACE_EXCLUSIONS, RESTORE_ALL, KEEP_ONLY }
    public enum SelectorKind { NONE, DOCUMENT, DESCRIPTION, COUNTERPARTY, FIELDS, ALL, REFERENCE }
    public enum Comparison { EQ, NE, GT, GTE, LT, LTE, CONTAINS, STARTS_WITH, IN, NOT_IN, IS_NULL, NOT_NULL }
    public enum Quantifier { UNSPECIFIED, ONE, ALL }
    public enum ReportRole { INCLUDED, EXCLUDED, RECORD_SCOPE, UNCHANGED, UNCHANGED_OTHERS }
    public enum Clarify { NONE, COMPANY, REPORTS, RECORDS, ACTION }
    public enum RestrictionScope { THIS_TURN }
    /**
     * 范围或配置修改请求，保存前须校验相应协议。
     * @param operation KEEP、REPLACE、ADD、REMOVE、ALL_AUTHORIZED用于范围求值；CLEAR仅清空服务端排除集合
     * @param mentions 本轮原文实体片段，必须出现在对应evidence中
     * @param evidence 支撑该修改或禁止的连续原文证据
     */
    public record Change(Operation operation, List<String> mentions, String evidence) {
        public static Change keep() { return new Change(Operation.KEEP, List.of(), ""); }
    }
    /**
     * 本轮语义范围修改及其原文证据。
     * @param target COMPANY、REPORTS或RECORDS范围目标
     * @param operation 范围使用REPLACE/ADD/REMOVE/ALL_AUTHORIZED；ALL_AUTHORIZED表示全部当前授权范围，绝不是空集合。记录使用EXCLUDE/RESTORE/REPLACE_EXCLUSIONS/RESTORE_ALL/KEEP_ONLY
     * @param mentions 本轮原文实体片段；REFERENCE使用服务端提供的随机引用键，其余实体须出现在evidence中；FIELDS、ALL和恢复全部时为空
     * @param evidence 支撑该修改或禁止的连续原文证据
     * @param reportMentions 仅用于RECORDS的报表限定原话；空集合表示在当前完整预览定位，不改变查询报表范围
     * @param selectorKind DOCUMENT单据、DESCRIPTION摘要、COUNTERPARTY客户实体、FIELDS配置字段条件、ALL整类记录、REFERENCE当前预览内已定位记录引用；范围操作和恢复全部使用NONE
     * @param quantifier ONE要求唯一记录，ALL为明确的匹配集合，UNSPECIFIED在多条匹配时澄清
     * @param conditions FIELDS选择器的有界OR条件组；其他选择器必须为空，组内按AND求值
     */
    public record ScopeChange(Target target, Operation operation, List<String> mentions, String evidence, List<String> reportMentions, SelectorKind selectorKind, Quantifier quantifier, List<ConditionGroup> conditions) {
        public ScopeChange(Target target,Operation operation,List<String> mentions,String evidence,List<String> reportMentions,SelectorKind selectorKind,Quantifier quantifier) {
            this(target,operation,mentions,evidence,reportMentions,selectorKind,quantifier,List.of());
        }
        /** 服务端构造未限定报表的修改；模型协议仍须显式提供reportMentions。 */
        public ScopeChange(Target target, Operation operation, List<String> mentions, String evidence) {
            this(target, operation, mentions, evidence, List.of());
        }
        /** 服务端构造当前协议操作；集合操作转换只用于代码调用，不读取旧模型协议。 */
        public ScopeChange(Target target, Operation operation, List<String> mentions, String evidence, List<String> reportMentions) {
            this(target, recordOperation(target, operation), mentions, evidence, reportMentions,
                    target==Target.RECORDS && operation!=Operation.CLEAR && operation!=Operation.RESTORE_ALL ? SelectorKind.DESCRIPTION : SelectorKind.NONE,
                    Quantifier.UNSPECIFIED);
        }
        /** 转换为服务端集合求值操作，不向模型暴露排除集合的反向语义。 */
        public Change change() { return new Change(switch(operation) {
            case EXCLUDE -> Operation.ADD; case RESTORE -> Operation.REMOVE;
            case REPLACE_EXCLUSIONS, KEEP_ONLY -> Operation.REPLACE; case RESTORE_ALL -> Operation.CLEAR; default -> operation;
        }, mentions, evidence); }
    }
    /**
     * 有类型的单字段条件；字段名仅从授权目录中选择，字面量由程序校验，不接受SQL或表达式。
     * @param field 目录声明的事实字段名，非物理列名
     * @param operator 比较操作；必须适配字段类型
     * @param values 规范字面量数组，null判断为空，普通比较一项，IN/NOT_IN最多20项；金额沿用字段单位
     * @param evidence 支撑字段、比较及值的本轮连续原文，日期和单位转换保留原文证据
     */
    public record FieldCondition(String field,Comparison operator,List<String> values,String evidence) { }
    /**
     * 合取条件组；组之间按OR连接，无递归表达式。
     * @param allOf 同时满足的条件，1至8项
     */
    public record ConditionGroup(List<FieldCondition> allOf) { }
    /**
     * 报表实体在本轮最终意图中的作用；与操作分开，允许等价的范围操作归并。
     * @param mention 本轮报表原文，须由授权目录解析
     * @param role INCLUDED须在最终范围内，EXCLUDED须在范围外，RECORD_SCOPE用于记录定位，UNCHANGED保持具名报表不变，UNCHANGED_OTHERS保持明确记录操作范围之外的报表不变
     * @param evidence 包含报表及其角色的本轮连续原文
     */
    public record ReportConstraint(String mention, ReportRole role, String evidence) { }
    /**
     * 仅对本轮生效的业务禁止，不继承到后续轮次。
     * @param forbiddenAction 本轮明确禁止的业务动作；EXECUTE_DISPATCH仅声明禁止提交，不禁止准备，也不提供执行能力
     * @param scope 业务禁止生效范围，当前只允许THIS_TURN
     * @param evidence 支撑该修改或禁止的连续原文证据
     */
    public record Restriction(ForbiddenAction forbiddenAction, RestrictionScope scope, String evidence) { }
    /** 服务端测试及确定性调用的构造器，输出仍为完整V1协议。 */
    public SemanticIntent(int version, Action action, List<ScopeChange> changes, List<Restriction> restrictions, Clarify clarify) {
        this(version, action, changes, restrictions, constraints(changes), List.of(), clarify);
    }
    /** 服务端集合操作的构造器，不接受旧版本JSON。 */
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
    private static Operation recordOperation(Target target, Operation operation) {
        if (target!=Target.RECORDS) return operation;
        return switch(operation) { case ADD -> Operation.EXCLUDE; case REMOVE -> Operation.RESTORE;
            case REPLACE -> Operation.REPLACE_EXCLUSIONS; case CLEAR -> Operation.RESTORE_ALL; default -> operation; };
    }
    private static List<ReportConstraint> constraints(List<ScopeChange> changes) {
        if(changes==null) return List.of();
        var result=new java.util.LinkedHashMap<String,ReportConstraint>();
        for(var c:changes) {
            if(c==null || c.mentions()==null || c.reportMentions()==null) continue;
            if(c.target()==Target.REPORTS) for(String m:c.mentions()) result.put(m,new ReportConstraint(m,
                    c.operation()==Operation.REMOVE?ReportRole.EXCLUDED:ReportRole.INCLUDED,c.evidence()));
            if(c.target()==Target.RECORDS) for(String m:c.reportMentions()) result.putIfAbsent(m,new ReportConstraint(m,ReportRole.RECORD_SCOPE,c.evidence()));
        }
        return List.copyOf(result.values());
    }
    public List<Change> changesFor(Target target) {
        return scopeChanges.stream().filter(c -> c.target()==target).map(ScopeChange::change).toList();
    }
    public boolean changes(Target target) { return scopeChanges.stream().anyMatch(c -> c.target()==target); }
    /** 两种枚举按业务动作名比较；仅禁止声明中的执行值永远不能扩充可执行动作集合。 */
    public boolean forbids(Action requested) { return restrictions.stream().anyMatch(r -> r.forbiddenAction().name().equals(requested.name())); }
    public static SemanticIntent clarify(Clarify slot) {
        return new SemanticIntent(VERSION, Action.CLARIFY, List.of(), List.of(), slot);
    }
}
