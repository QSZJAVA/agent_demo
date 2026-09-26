package com.example.report.agent;

import com.example.report.dispatch.PlanSnapshot;
import com.example.report.rule.Candidate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待确认卡片载荷。status 是生成时的状态；之后的状态以服务端卡片状态接口为准。
 */
public record PlanPayload(
        String planId,
        String previewId,
        String status,
        int count,
        List<String> excluded,
        List<Candidate> records,
        LocalDateTime expiresAt
) {
    public static PlanPayload of(PlanSnapshot snapshot) {
        return new PlanPayload(snapshot.plan().getId(), snapshot.plan().getPreviewId(), snapshot.plan().getStatus(),
                snapshot.items().size(), snapshot.excluded(), snapshot.candidates(), snapshot.plan().getExpiresAt());
    }
}
