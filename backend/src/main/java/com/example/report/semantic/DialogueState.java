package com.example.report.semantic;

import com.example.report.dispatch.RecordKey;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/** Requested scope, effective scope and failed attempts never share the same field. */
@Data
public class DialogueState {
    public enum Phase { READY, QUERYING, REJECTED, CLARIFY, PLAN_READY, FAILED }
    public record Scope(String companyCode, boolean allReports, List<String> reportIds) {
        public Scope { reportIds = reportIds == null ? List.of() : List.copyOf(reportIds); }
        public static Scope initial() { return new Scope(null, true, List.of()); }
    }
    private Scope desired = Scope.initial();
    private Scope effective;
    private Phase phase = Phase.READY;
    private String previewId;
    private String planId;
    private List<RecordKey> excludedRecords = List.of();
    private SemanticIntent pendingIntent;
    private IntentParser.Source parserSource;
    private boolean unresolvedReports;
    private boolean unresolvedCompany;
    private boolean unresolvedRecords;
    private String lastReason;
    private LocalDateTime attemptedAt;
    private List<String> recentUserMessages = List.of();
}
