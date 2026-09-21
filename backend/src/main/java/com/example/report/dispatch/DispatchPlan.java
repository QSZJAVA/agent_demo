package com.example.report.dispatch;

import com.example.report.rule.Candidate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待确认派单清单：快照减去排除项之后的最终清单
 */
public record DispatchPlan(
        String id,
        String userId,
        String conversationId,
        String previewId,
        String rulesFingerprint,
        LocalDateTime createdAt,
        List<Candidate> records,
        List<String> excluded
) {
}
