package com.example.report.semantic;

import com.example.report.catalog.ReportRef;
import java.util.List;

public interface IntentParser {
    // DOMAIN is read-only compatibility for historical V1 sessions, never emitted by V2.
    enum Source { DOMAIN, MODEL, MOCK }
    record Interpretation(SemanticIntent intent,Source source) { }
    record Context(DialogueState state, List<ReportRef> reports, List<String> mentionedReportTerms) {
        public Context(DialogueState state,List<ReportRef> reports) { this(state,reports,List.of()); }
    }
    SemanticIntent parse(String message, Context context);
    default Interpretation interpret(String message,Context context) { return new Interpretation(parse(message,context),Source.MODEL); }
}
