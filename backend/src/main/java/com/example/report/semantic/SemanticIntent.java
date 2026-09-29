package com.example.report.semantic;

import java.util.List;

/** The model describes a grounded change; it cannot name tools, SQL, identities or executable plan IDs. */
public record SemanticIntent(int version, Action action, Change company, Change reports,
                             Change exclusions, Clarify clarify) {
    public enum Action { PREVIEW, PREPARE_DISPATCH, CANCEL_PLAN, SHOW_RESULT, EXPLAIN_RULES, CLARIFY, HELP }
    public enum Operation { KEEP, REPLACE, ADD, REMOVE, CLEAR }
    public enum Clarify { NONE, COMPANY, REPORTS, RECORDS, ACTION }
    public record Change(Operation operation, List<String> mentions, String evidence) {
        public static Change keep() { return new Change(Operation.KEEP, List.of(), ""); }
    }
    public static SemanticIntent clarify(Clarify slot) {
        return new SemanticIntent(1, Action.CLARIFY, Change.keep(), Change.keep(), Change.keep(), slot);
    }
}
