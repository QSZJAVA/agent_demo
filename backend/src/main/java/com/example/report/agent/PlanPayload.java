package com.example.report.agent;

import com.example.report.dispatch.PlanSnapshot;
import com.example.report.rule.Candidate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待确认卡片载荷。status 是生成时的状态；之后的状态以服务端卡片状态接口为准。
 * @param planId 派单清单标识，关联服务端持久化清单
 * @param previewId 预览标识，选择和建单必须绑定此快照
 * @param status 当前业务状态，以所属状态机为准
 * @param count 当前业务对象的记录数量
 * @param excluded 本次建单实际排除的记录数
 * @param records 有界待确认记录样本，完整清单须分页读取
 * @param expiresAt 有效期截止时间，到期后须重新校验或创建
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
                snapshot.plan().getItemCount(), snapshot.excluded(), snapshot.candidates().stream().limit(50).toList(), snapshot.plan().getExpiresAt());
    }
}
