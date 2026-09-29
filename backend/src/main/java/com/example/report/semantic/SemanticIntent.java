package com.example.report.semantic;

import java.util.ArrayList;
import java.util.List;

/** V2 business language. Model output cannot execute dispatch or specify SQL, identities or plan IDs. */
public record SemanticIntent(int version, Action action, List<ScopeChange> scopeChanges,
                             List<Restriction> restrictions, Clarify clarify) {
    public enum Action { PREVIEW, PREPARE_DISPATCH, CANCEL_PLAN, SHOW_RESULT, EXPLAIN_RULES, CLARIFY, HELP }
    public enum Target { COMPANY, REPORTS, RECORDS }
    // KEEP is an internal convenience only: omission of a target represents KEEP on the wire.
    public enum Operation { KEEP, REPLACE, ADD, REMOVE, CLEAR }
    public enum Clarify { NONE, COMPANY, REPORTS, RECORDS, ACTION }
    public enum RestrictionScope { THIS_TURN }
    public record Change(Operation operation, List<String> mentions, String evidence) {
        public static Change keep() { return new Change(Operation.KEEP, List.of(), ""); }
    }
    public record ScopeChange(Target target, Operation operation, List<String> mentions, String evidence) {
        public Change change() { return new Change(operation, mentions, evidence); }
    }
    /** An explicit prohibition, scoped to this utterance rather than future turns. */
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
