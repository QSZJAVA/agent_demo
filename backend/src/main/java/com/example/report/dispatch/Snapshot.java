package com.example.report.dispatch;

import com.example.report.rule.Candidate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 预览快照：一次 previewDispatchable 的结果，是执行派单的唯一依据
 *
 * @param reportTypes 本次查询明确指定的报表类型；空列表表示全部报表。升级前写入的旧快照可能为 null
 */
public record Snapshot(
        String id,
        String userId,
        String conversationId,
        String rulesFingerprint,
        LocalDateTime createdAt,
        List<Candidate> candidates,
        List<String> reportTypes
) {
}
