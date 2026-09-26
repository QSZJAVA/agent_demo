package com.example.report.dispatch;

import com.example.report.common.JsonUtil;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPlanItem;
import com.example.report.rule.Candidate;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;

/**
 * 待确认清单：状态 + 条目
 *
 * @param expiredPlanIds 生成本清单时失效的旧清单
 * @param replayed       幂等键重复：返回的是第一次生成的清单，本次没有新建
 */
public record PlanSnapshot(DispatchPlan plan, List<DispatchPlanItem> items, List<String> expiredPlanIds, boolean replayed) {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    public PlanSnapshot {
        expiredPlanIds = expiredPlanIds == null ? List.of() : List.copyOf(expiredPlanIds);
    }

    public PlanSnapshot(DispatchPlan plan, List<DispatchPlanItem> items) {
        this(plan, items, List.of(), false);
    }

    public List<Candidate> candidates() {
        return items.stream().map(PlanSnapshot::toCandidate).toList();
    }

    public List<String> excluded() {
        String json = plan.getExcludeJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JsonUtil.MAPPER.readValue(json, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    public static Candidate toCandidate(DispatchPlanItem i) {
        return new Candidate(i.getReportId(), i.getReportName(), i.getRecordId(), i.getDocNo(), i.getCompanyCode(),
                i.getLabel(), i.getAmount(), i.getBizDate(), i.getRuleId(), i.getRuleName(), i.getRuleVersion(), null,
                i.getCatalogVersion());
    }
}
