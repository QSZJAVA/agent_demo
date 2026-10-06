package com.example.report.support;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.ReportCatalog;
import com.example.report.catalog.TermIndex;
import com.example.report.catalog.query.FactRow;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.catalog.query.ReportQueryAdapter;
import com.example.report.permission.CurrentUser;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测试用报表目录：与当前基线中的三张种子报表、别名一致，可以在测试中途替换（模拟新增 / 停用 / 修改报表）。
 */
public final class TestCatalog {

    public static final String SALES = "rpt-sales-order";
    public static final String RECEIVABLE = "rpt-ar-invoice";
    public static final String EXPENSE = "rpt-expense-claim";
    public static final String PURCHASE = "rpt-purchase-order";

    public static final Set<String> DEMO_PERMISSIONS = Set.of("report:sales", "report:receivable", "report:expense");
    public static final CurrentUser USER1 = new CurrentUser("T001", "user1", "用户1", Set.of("A"), DEMO_PERMISSIONS, false);
    public static final CurrentUser USER2 = new CurrentUser("T001", "user2", "用户2", Set.of("B"), DEMO_PERMISSIONS, false);
    /** B 公司，没有应收报表权限 */
    public static final CurrentUser USER3 = new CurrentUser("T001", "user3", "用户3", Set.of("B"),
            Set.of("report:sales", "report:expense"), false);
    public static final CurrentUser ADMIN = new CurrentUser("T001", "admin", "管理员", Set.of("A", "B", "C"),
            Set.of(CurrentUser.ALL), true);

    /** 不会被调用的适配器：候选记录由测试直接给出 */
    public static final ReportQueryAdapter NO_QUERY = new ReportQueryAdapter() {
        @Override
        public List<FieldInfo> fields() {
            return List.of(new FieldInfo("amount", "decimal", "金额"), new FieldInfo("companyCode", "string", "公司代码"));
        }

        @Override
        public String docNoLabel() {
            return "单据号";
        }

        @Override
        public List<FactRow> pendingRows(String tenantId, Set<String> companies) {
            return List.of();
        }

        @Override
        public List<FactRow> rowsByIds(String tenantId, Collection<String> recordIds) {
            return List.of();
        }
    };

    private final AtomicReference<List<CatalogEntry>> entries = new AtomicReference<>(demo());
    private final ReportCatalog catalog = mock(ReportCatalog.class);

    public TestCatalog() {
        when(catalog.all()).thenAnswer(inv -> entries.get());
        when(catalog.find(any())).thenAnswer(inv -> entries.get().stream()
                .filter(e -> e.reportId().equals(inv.getArgument(0))).findFirst());
        when(catalog.terms()).thenAnswer(inv -> terms(entries.get()));
    }

    public ReportCatalog catalog() {
        return catalog;
    }

    public List<CatalogEntry> entries() {
        return entries.get();
    }

    public void set(List<CatalogEntry> list) {
        entries.set(List.copyOf(list));
    }

    /** 替换目录中的一张报表（例如版本号 +1、停用） */
    public void replace(CatalogEntry entry) {
        List<CatalogEntry> list = new ArrayList<>(entries.get());
        list.replaceAll(e -> e.reportId().equals(entry.reportId()) ? entry : e);
        set(list);
    }

    public void add(CatalogEntry entry) {
        List<CatalogEntry> list = new ArrayList<>(entries.get());
        list.add(entry);
        set(list);
    }

    public CatalogEntry get(String reportId) {
        return entries.get().stream().filter(e -> e.reportId().equals(reportId)).findFirst().orElseThrow();
    }

    public static List<CatalogEntry> demo() {
        return List.of(
                entry(SALES, "sales", "销售报表", "report:sales", 10,
                        List.of("销售", "销售台账", "订单销售表", "销售明细", "销售订单", "sales report", "客户对账")),
                entry(RECEIVABLE, "receivable", "应收报表", "report:receivable", 20,
                        List.of("应收", "应收发票台账", "应收台账", "应收账款", "AR", "客户对账", "应收保表")),
                entry(EXPENSE, "expense", "费用报表", "report:expense", 30,
                        List.of("费用", "报销", "报销单", "费用报销", "费用台账", "expense report")));
    }

    public static CatalogEntry purchase() {
        return entry(PURCHASE, "purchase", "采购报表", "report:purchase", 40, List.of("采购", "采购订单", "PO"));
    }

    public static CatalogEntry entry(String id, String code, String name, String permission, int sort, List<String> aliases) {
        List<CatalogEntry.AliasView> views = new ArrayList<>();
        for (int i = 0; i < aliases.size(); i++) {
            views.add(new CatalogEntry.AliasView((long) i, aliases.get(i), "COLLOQUIAL", 0, "ACTIVE"));
        }
        return new CatalogEntry("T001", id, code, name, code, name + "说明", "STANDARD", "{}", true, "PUBLISHED", 1, 1L,
                permission, sort, null, null, null, "test", LocalDateTime.now(), views, NO_QUERY, null);
    }

    /** 同一张报表换一个目录版本 / 状态 / 派单开关 */
    public static CatalogEntry with(CatalogEntry e, long catalogVersion, String status, boolean dispatchEnabled) {
        return new CatalogEntry("T001", e.reportId(), e.reportCode(), e.reportName(), e.domainCode(), e.description(), e.queryMode(),
                e.queryConfig(), dispatchEnabled, status, e.schemaVersion(), catalogVersion, e.permissionCode(), e.sortOrder(),
                e.effectiveFrom(), e.effectiveTo(), e.ownerUserId(), e.updatedBy(), e.updatedAt(), e.aliases(), e.adapter(),
                e.configError());
    }

    public static TermIndex terms(List<CatalogEntry> entries) {
        return TermIndex.build(entries.stream()
                .filter(CatalogEntry::published)
                .map(e -> new TermIndex.ReportTerms(e.reportId(), e.reportName(), e.reportCode(),
                        e.activeAliases().stream().map(a -> new TermIndex.AliasTerm(a.alias(), a.priority())).toList()))
                .toList());
    }
}
