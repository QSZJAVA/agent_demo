package com.example.report.support;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.config.AgentProperties;
import com.example.report.dispatch.DispatchVersionService;
import com.example.report.dispatch.PlanService;
import com.example.report.dispatch.PreviewService;
import com.example.report.rule.Candidate;
import com.example.report.rule.DispatchCandidateService;
import com.example.report.rule.RuleCache;
import org.springframework.transaction.support.TransactionOperations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 派单链路的测试装配：真实的目录权限服务、版本服务、预览 / 清单状态机，
 * 目录、规则指纹、候选记录与存储用测试替身（不访问数据库与模型）。
 */
public final class DispatchHarness {

    public final TestCatalog catalog = new TestCatalog();
    public final AgentProperties props = new AgentProperties();
    public final ReportCatalogService catalogService = new ReportCatalogService(catalog.catalog(), props);
    public final RuleCache rules = mock(RuleCache.class);
    /** 当前生效规则的指纹；测试里改它来模拟"有人发布了新规则" */
    public final AtomicReference<String> ruleVersion = new AtomicReference<>("rules-v1");
    public final DispatchCandidateService candidates = mock(DispatchCandidateService.class);
    /** 各报表当前满足规则的记录（report_id → 记录），按公司范围过滤后返回 */
    public final Map<String, List<Candidate>> data = new ConcurrentHashMap<>();
    public final InMemoryDispatchStore store = new InMemoryDispatchStore();
    public final DispatchVersionService versions = new DispatchVersionService(catalogService, rules);
    public final PreviewService previews = new PreviewService(catalogService, candidates, versions, store.previews(),
            store.plans(), props, TransactionOperations.withoutTransaction());
    public final PlanService plans = new PlanService(previews, store.previews(), store.plans(), versions, props,
            TransactionOperations.withoutTransaction());

    public DispatchHarness() {
        when(rules.fingerprint(anyString(), anyCollection(), anyCollection())).thenAnswer(inv -> ruleVersion.get());
        when(candidates.findCandidates(anyString(), anySet(), anyList())).thenAnswer(inv -> {
            Set<String> companies = inv.getArgument(1);
            List<CatalogEntry> reports = inv.getArgument(2);
            List<Candidate> result = new ArrayList<>();
            for (CatalogEntry r : reports) {
                data.getOrDefault(r.reportId(), List.of()).stream()
                        .filter(c -> companies.contains(c.companyCode()))
                        .forEach(result::add);
            }
            return result;
        });
        when(candidates.qualifiedPlanKeys(anyString(), anySet(), anyList(), org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(inv -> {
                    List<CatalogEntry> reports = inv.getArgument(2);
                    Map<String, List<String>> ids = inv.getArgument(3);
                    return candidates.findCandidates(inv.getArgument(0), inv.getArgument(1), reports).stream()
                            .filter(c -> ids.getOrDefault(c.reportId(), List.of()).contains(c.recordId()))
                            .map(Candidate::key).collect(java.util.stream.Collectors.toSet());
                });
        when(candidates.findCandidates(anyString(), anySet(), anyList(), org.mockito.ArgumentMatchers.anyInt(), anyList()))
                .thenAnswer(inv -> {
                    List<Candidate> all = candidates.findCandidates(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
                    return com.example.report.dispatch.PreviewService.applyExcludes(all, inv.getArgument(4)).stream()
                            .limit((Integer) inv.getArgument(3)).toList();
                });
        when(candidates.findCandidates(anyString(), anySet(), anyList(), org.mockito.ArgumentMatchers.anyInt(), anyList(),
                org.mockito.ArgumentMatchers.any(java.util.function.IntConsumer.class)))
                .thenAnswer(inv -> candidates.findCandidates(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                        inv.getArgument(3), inv.getArgument(4)));
        doAnswer(inv -> {
            List<Candidate> all = candidates.findCandidates(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            java.util.function.Consumer<Candidate> sink = inv.getArgument(5);
            PreviewService.applyExcludes(all, inv.getArgument(3)).forEach(sink);
            return null;
        }).when(candidates).scanCandidates(anyString(), anySet(), anyList(), anyList(),
                org.mockito.ArgumentMatchers.any(java.util.function.IntConsumer.class),
                org.mockito.ArgumentMatchers.any(java.util.function.Consumer.class));
    }

    public DispatchHarness put(String reportId, Candidate... records) {
        data.put(reportId, List.of(records));
        return this;
    }

    public static Candidate candidate(String reportId, String recordId, String docNo, String company, String label) {
        String name = switch (reportId) {
            case TestCatalog.SALES -> "销售报表";
            case TestCatalog.RECEIVABLE -> "应收报表";
            case TestCatalog.EXPENSE -> "费用报表";
            default -> "采购报表";
        };
        return new Candidate(reportId, name, recordId, docNo, company, label, BigDecimal.valueOf(2000),
                LocalDate.of(2026, 1, 1), 1L, "测试规则", 1, "金额 > 20 元", 1L);
    }
}
