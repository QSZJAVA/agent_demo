package com.example.report.dispatch;

import com.example.report.catalog.ResolveResult;

import java.util.List;

/**
 * 一次预览请求的结果：生成了快照、需要用户在多张报表中选择、或者没有匹配的报表
 *
 * @param supersededPreviewIds 本次作废的旧预览
 * @param expiredPlanIds       本次失效的待确认清单
 */
public record PreviewOutcome(Status status, ResolveResult resolution, PreviewSnapshot snapshot,
                             List<String> supersededPreviewIds, List<String> expiredPlanIds) {

    public enum Status { OK, AMBIGUOUS, NOT_FOUND }

    static PreviewOutcome ok(ResolveResult resolution, PreviewSnapshot snapshot, List<String> superseded, List<String> expired) {
        return new PreviewOutcome(Status.OK, resolution, snapshot, List.copyOf(superseded), List.copyOf(expired));
    }

    static PreviewOutcome ambiguous(ResolveResult resolution) {
        return new PreviewOutcome(Status.AMBIGUOUS, resolution, null, List.of(), List.of());
    }

    static PreviewOutcome notFound(ResolveResult resolution) {
        return new PreviewOutcome(Status.NOT_FOUND, resolution, null, List.of(), List.of());
    }
}
