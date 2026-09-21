package com.example.report.agent;

import com.example.report.rule.Candidate;

import java.util.List;

/**
 * 待确认卡片载荷
 */
public record PlanPayload(
        String planId,
        String previewId,
        int count,
        List<String> excluded,
        List<Candidate> records
) {
}
