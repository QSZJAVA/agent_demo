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
import java.util.Map;
import java.util.HashSet;
import java.util.Collection;

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

    /** Recheck only the IDs in an already bounded plan, without scanning the entire report. */
    public Set<String> qualifiedPlanKeys(String tenantId, Set<String> companies, List<CatalogEntry> reports,
                                         Map<String, ? extends Collection<String>> recordIds) {
        Set<String> qualified = new HashSet<>();
        EvalErrors errors = new EvalErrors();
        for (CatalogEntry report : reports) {
            if (!java.util.Objects.equals(tenantId, report.tenantId()) || !report.usable()) continue;
            Collection<String> ids = recordIds.get(report.reportId());
            if (ids == null || ids.isEmpty()) continue;
            List<String> distinct = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
            for (int start = 0; start < distinct.size(); start += 500) {
                List<FactRow> rows = report.adapter().pendingRowsByIds(tenantId,
                        distinct.subList(start, Math.min(start + 500, distinct.size())));
                for (FactRow row : rows) {
                    if (!companies.contains(row.companyCode())) continue;
                    Optional<DispatchRule> rule = ruleCache.find(tenantId, report.reportId(), row.companyCode());
                    if (rule.isPresent() && matchesSafely(rule.get().getExpression(), row, rule.get().getName(), errors)) {
                        qualified.add(toCandidate(report, row, rule.get().getId(), rule.get().getName(),
                                rule.get().getVersion(), rule.get().getDescription()).key());
                    }
                }
            }
        }
        if (errors.count > 0) log.warn("派单复核规则求值失败 {} 行，首条：{}", errors.count, errors.sample);
        return qualified;
    }

    /** 指定报表范围、公司范围内按各报表当前生效规则应派单的记录；结果按传入的报表顺序排列*/
    public List<Candidate> findCandidates(String tenantId, Set<String> companies, List<CatalogEntry> reports) {
        return findCandidates(tenantId, companies, reports, Integer.MAX_VALUE);
    }

    public List<Candidate> findCandidates(String tenantId, Set<String> companies, List<CatalogEntry> reports, int maxMatches) {
        return findCandidates(tenantId, companies, reports, maxMatches, List.of());
    }

    public List<Candidate> findCandidates(String tenantId, Set<String> companies, List<CatalogEntry> reports,
                                          int maxMatches, List<String> excludes) {
        return findCandidates(tenantId, companies, reports, maxMatches, excludes, scanned -> { });
    }

    public List<Candidate> findCandidates(String tenantId, Set<String> companies, List<CatalogEntry> reports,
                                          int maxMatches, List<String> excludes, java.util.function.IntConsumer progress) {
        List<Candidate> result = new ArrayList<>();
        visitCandidates(tenantId, companies, reports, excludes, progress, candidate -> {
            result.add(candidate);
            return result.size() < maxMatches;
        });
        return result;
    }

    /** 有界游标扫描授权范围内的待派单事实并逐条输出匹配候选；进度表示扫描数，不能作为最终命中数量。 */
    public void scanCandidates(String tenantId, Set<String> companies, List<CatalogEntry> reports,
    List<String> excludes, java.util.function.IntConsumer progress,
                               java.util.function.Consumer<Candidate> consumer) {
        visitCandidates(tenantId, companies, reports, excludes, progress, candidate -> {
            consumer.accept(candidate);
            return true;
        });
    }

    /** 每页最多500条并在本地完整复核规则；SQL下推只作安全粗筛，求值异常按未命中统计，不能错误派单。*/
    private void visitCandidates(String tenantId, Set<String> companies, List<CatalogEntry> reports,
                                 List<String> excludes, java.util.function.IntConsumer progress,
                                 java.util.function.Predicate<Candidate> visitor) {
        int scanned = 0;
        List<String> keys = excludes == null ? List.of() : excludes.stream().filter(java.util.Objects::nonNull)
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        EvalErrors errors = new EvalErrors();
        outer:
        for (CatalogEntry report : reports) {
            if (!java.util.Objects.equals(tenantId, report.tenantId()) || !report.usable()) {
                continue;
            }
            for (String company : new java.util.TreeSet<>(companies)) {
                Optional<DispatchRule> rule = ruleCache.find(tenantId, report.reportId(), company);
                if (rule.isEmpty()) continue;
                DispatchRule active = rule.get();
                String afterId = null;
                while (true) {
                    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("查询已取消");
                    List<FactRow> page = report.adapter().pendingRowsAfterWithRule(tenantId, Set.of(company),
                            afterId, 500, active.getExpression());
                    scanned += page.size();
                    progress.accept(scanned);
                    for (FactRow row : page) {
                        if (!company.equals(row.companyCode())) continue;
                        if (matchesSafely(active.getExpression(), row, active.getName(), errors)) {
                            if (keys.stream().anyMatch(k -> k.equalsIgnoreCase(row.docNo())
                                    || (row.label() != null && row.label().contains(k)))) continue;
                            if (!visitor.test(toCandidate(report, row, active.getId(), active.getName(),
                                    active.getVersion(), active.getDescription()))) break outer;
                        }
                    }
                    if (page.size() < 500) break;
                    afterId = page.get(page.size() - 1).recordId();
                }
            }
        }
        if (errors.count > 0) {
            // 只汇总告警一次，避免逐行刷日志
            log.warn("派单规则求值失败 {} 行，已按不命中处理，首条：{}", errors.count, errors.sample);
        }
    }

    /** 试算：对某个范围（具体公司或通配 = 全部公司）的粗筛结果跑一个任意表达式 */
    public DryRunResult dryRun(String tenantId, CatalogEntry report, String companyCode, String expression, Set<String> allCompanies) {
        return dryRun(tenantId, report, companyCode, expression, allCompanies, () -> { });
    }

    public DryRunResult dryRun(String tenantId, CatalogEntry report, String companyCode, String expression,
                               Set<String> allCompanies, Runnable checkPermit) {
        if (!java.util.Objects.equals(tenantId, report.tenantId())) {
            throw com.example.report.common.ApiException.notFound("报表不存在或无权访问");
        }
        Set<String> scope = DispatchRule.ANY_COMPANY.equals(companyCode) ? allCompanies : Set.of(companyCode);
        List<Candidate> samples = new ArrayList<>(20);
        EvalErrors errors = new EvalErrors();
        int total = 0, hits = 0;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(120);
        String afterId = null;
        while (report.usable()) {
            checkPermit.run();
            checkDryRunDeadline(deadline);
            List<FactRow> page = report.adapter().dryRunRowsAfter(tenantId, scope, afterId, 500);
            if (page.size() > 500) throw new com.example.report.common.ApiException("报表适配器未遵守试算分页上限");
            for (FactRow row : page) {
                checkPermit.run();
                checkDryRunDeadline(deadline);
                if (++total > 10000) throw new com.example.report.common.ApiException(422,
                        "试算范围超过 10000 条，请缩小公司或报表数据范围后重试；未返回部分统计");
                if (!scope.contains(row.companyCode())) throw new com.example.report.common.ApiException("报表试算返回了范围外的记录");
                if (matchesSafely(expression, row, null, errors)) {
                    hits++;
                    if (samples.size() < 20) samples.add(toCandidate(report, row, null, "试算", 0, null));
                }
            }
            if (page.size() < 500) break;
            String nextId = page.get(page.size() - 1).recordId();
            if (nextId == null || nextId.equals(afterId)) throw new com.example.report.common.ApiException("报表试算游标未前进");
            afterId = nextId;
        }
        checkDryRunDeadline(deadline);
        checkPermit.run();
        return new DryRunResult(total, hits, List.copyOf(samples), errors.count, errors.sample);
    }

    private static void checkDryRunDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) {
            throw new com.example.report.common.ApiException(408, "试算已取消或超过 120 秒，请缩小范围后重试");
        }
    }

    /** 单行求值出错按不命中处理：一行脏数据（例如空字段上调字符串函数）不能拖垮整次查询*/
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
     * @param total 授权范围内统计总数，不能用当前页长度代替
     * @param hitCount 命中规则的来源记录数
     * @param samples 有界试算命中样本，用于人工检查
     * @param errorCount 规则求值失败的记录数
     * @param errorSample 有界求值失败摘要，无错误时为空
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
