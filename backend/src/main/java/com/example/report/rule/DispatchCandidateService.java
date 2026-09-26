package com.example.report.rule;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.query.FactRow;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.entity.DispatchRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 候选记录查找：报表查询适配器粗筛（租户、公司范围、未派单）→ 按当前生效规则逐行求值。
 * 报表来自目录，不再有按报表类型分支的代码。
 */
@Slf4j
@Service
public class DispatchCandidateService {

    private final RuleCache ruleCache;
    private final RuleEngine ruleEngine;

    public DispatchCandidateService(RuleCache ruleCache, RuleEngine ruleEngine) {
        this.ruleCache = ruleCache;
        this.ruleEngine = ruleEngine;
    }

    public List<FieldInfo> fields(CatalogEntry report) {
        return report.fields();
    }

    /** 指定报表范围、公司范围内按各报表当前生效规则应派单的记录；结果按传入的报表顺序排列 */
    public List<Candidate> findCandidates(String tenantId, Set<String> companies, List<CatalogEntry> reports) {
        List<Candidate> result = new ArrayList<>();
        EvalErrors errors = new EvalErrors();
        for (CatalogEntry report : reports) {
            if (!java.util.Objects.equals(tenantId, report.tenantId()) || !report.usable()) {
                continue;
            }
            for (FactRow row : report.adapter().pendingRows(tenantId, companies)) {
                Optional<DispatchRule> rule = ruleCache.find(tenantId, report.reportId(), row.companyCode());
                if (rule.isEmpty()) {
                    continue;
                }
                DispatchRule r = rule.get();
                if (matchesSafely(r.getExpression(), row, r.getName(), errors)) {
                    result.add(toCandidate(report, row, r.getId(), r.getName(), r.getVersion(), r.getDescription()));
                }
            }
        }
        if (errors.count > 0) {
            // 只汇总告警一次，避免逐行刷日志
            log.warn("派单规则求值失败 {} 行，已按不命中处理，首条：{}", errors.count, errors.sample);
        }
        return result;
    }

    /** 试算：对某个范围（具体公司或通配 = 全部公司）的粗筛结果跑一个任意表达式 */
    public DryRunResult dryRun(String tenantId, CatalogEntry report, String companyCode, String expression, Set<String> allCompanies) {
        if (!java.util.Objects.equals(tenantId, report.tenantId())) {
            throw com.example.report.common.ApiException.notFound("报表不存在或无权访问");
        }
        Set<String> scope = DispatchRule.ANY_COMPANY.equals(companyCode) ? allCompanies : Set.of(companyCode);
        List<FactRow> rows = report.usable() ? report.adapter().pendingRows(tenantId, scope) : List.of();
        List<Candidate> hits = new ArrayList<>();
        EvalErrors errors = new EvalErrors();
        for (FactRow row : rows) {
            if (matchesSafely(expression, row, null, errors)) {
                hits.add(toCandidate(report, row, null, "试算", 0, null));
            }
        }
        return new DryRunResult(rows.size(), hits.size(), hits.stream().limit(20).toList(), errors.count, errors.sample);
    }

    /** 单行求值出错按不命中处理：一行脏数据（例如空字段上调字符串函数）不能拖垮整次查询 */
    private boolean matchesSafely(String expression, FactRow row, String ruleName, EvalErrors errors) {
        try {
            return ruleEngine.matches(expression, row.facts());
        } catch (RuntimeException e) {
            errors.add(ruleName, row.docNo(), e);
            return false;
        }
    }

    public static Candidate toCandidate(CatalogEntry report, FactRow row, Long ruleId, String ruleName, Integer ruleVersion,
                                        String description) {
        return new Candidate(report.reportId(), report.reportName(), row.recordId(), row.docNo(), row.companyCode(),
                row.label(), row.amount(), row.date(), ruleId, ruleName, ruleVersion, description, report.catalogVersion());
    }

    /**
     * 试算结果：范围内总条数、命中条数、样例；
     * errorCount / errorSample 是求值出错（已按不命中处理）的行数与第一条的单据号和原因
     */
    public record DryRunResult(int total, int hitCount, List<Candidate> samples, int errorCount, String errorSample) {
    }

    /** 一次查询内的求值失败汇总：行数 + 第一条的位置与原因 */
    private static final class EvalErrors {
        private int count;
        private String sample;

        void add(String ruleName, String docNo, RuntimeException e) {
            if (count++ > 0) {
                return;
            }
            String reason = e instanceof NullPointerException
                    ? "字段值为空（可先判断 字段 != nil 再调用函数）"
                    : (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            sample = (ruleName == null ? "" : ruleName + " / ") + docNo + "：" + reason;
        }
    }
}
