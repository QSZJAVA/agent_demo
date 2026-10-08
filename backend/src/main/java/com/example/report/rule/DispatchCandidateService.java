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

    @org.springframework.beans.factory.annotation.Value("${agent.preview.max-scanned-rows:100000}")
    private int maxScannedRows = 100000;
    @org.springframework.beans.factory.annotation.Value("${agent.preview.max-scan-seconds:120}")
    private int maxScanSeconds = 120;
    private final RuleCache ruleCache;
    private final RuleEngine ruleEngine;

    public DispatchCandidateService(RuleCache ruleCache, RuleEngine ruleEngine) {
        this.ruleCache = ruleCache;
        this.ruleEngine = ruleEngine;
    }

    public List<FieldInfo> fields(CatalogEntry report) {
        return report.fields();
    }

    /**
     * 重新核验明确绑定的记录，保持身份、公司和数量完全一致；任何目标失效都拒绝整组，不能悄悄少派。
     * 只使用按标识读取的待派单事实与当前生效规则，不扫描或补入同报表的其他记录。
     * @param tenantId 当前身份所属租户
     * @param companies 已核验授权的公司集合
     * @param reports 已核验可派单的报表目录
     * @param targets 服务端绑定的精确目标及原公司边界
     * @param progress 配额、取消与租约的只读守卫
     * @return 与目标原顺序一致的完整合格记录；权限、来源和规则失败时不返回部分结果
     */
    public List<Candidate> findBoundCandidates(String tenantId,Set<String> companies,List<CatalogEntry> reports,
            List<com.example.report.dispatch.RecordTarget> targets,java.util.function.IntConsumer progress) {
        if(targets==null || targets.isEmpty() || targets.size()>maxScannedRows)
            throw new com.example.report.common.ApiException(422,"目标集合为空或超过核验预算");
        var requested=new java.util.LinkedHashMap<com.example.report.dispatch.RecordKey,String>();
        for(var target:targets) {
            if(!companies.contains(target.companyCode()))throw com.example.report.common.ApiException.forbidden("目标公司不存在或无权访问");
            if(requested.putIfAbsent(target.key(),target.companyCode())!=null)
                throw new com.example.report.common.ApiException(422,"派单目标重复，未生成预览");
        }
        Set<String> allowed=reports.stream().filter(r->tenantId.equals(r.tenantId()) && r.usable())
                .map(CatalogEntry::reportId).collect(java.util.stream.Collectors.toSet());
        if(requested.keySet().stream().anyMatch(k->!allowed.contains(k.reportId())))
            throw com.example.report.common.ApiException.forbidden("目标报表不存在或不可派单");
        var found=new java.util.LinkedHashMap<com.example.report.dispatch.RecordKey,Candidate>();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(maxScanSeconds);int scanned=0;
        for(var report:reports) {
            var ids=requested.keySet().stream().filter(k->k.reportId().equals(report.reportId())).map(com.example.report.dispatch.RecordKey::recordId).toList();
            for(int start=0;start<ids.size();start+=500) {
                checkScanBudget(deadline,scanned);progress.accept(scanned);
                var batch=ids.subList(start,Math.min(start+500,ids.size()));
                var rows=report.adapter().pendingRowsByIds(tenantId,batch);
                if(rows.size()>batch.size())throw new com.example.report.common.ApiException(502,"来源返回了重复或范围外记录，未生成清单");
                for(var row:rows) {
                    checkScanBudget(deadline,++scanned);progress.accept(scanned);
                    var key=new com.example.report.dispatch.RecordKey(report.reportId(),row.recordId());
                    if(!batch.contains(row.recordId()) || found.containsKey(key))
                        throw new com.example.report.common.ApiException(502,"来源返回了重复或范围外记录，未生成清单");
                    if(!java.util.Objects.equals(requested.get(key),row.companyCode()))
                        throw new com.example.report.common.ApiException(409,"目标记录所属公司已变化，请重新查询核对后派单");
                    var rule=ruleCache.find(tenantId,report.reportId(),row.companyCode());
                    if(rule.isEmpty() || !matchesRequired(rule.get().getExpression(),row))
                        throw new com.example.report.common.ApiException(422,"指定记录 "+java.util.Objects.toString(row.docNo(),row.recordId())+" 当前不符合生效派单规则，未生成清单；请重新核验或调整目标");
                    var active=rule.get();found.put(key,toCandidate(report,row,active.getId(),active.getName(),active.getVersion(),active.getDescription()));
                }
            }
        }
        checkScanBudget(deadline,scanned);progress.accept(scanned);
        if(found.size()!=requested.size())throw new com.example.report.common.ApiException(409,"指定目标中有记录已派单、已不存在或不再可见，未生成部分清单；请重新查询核对");
        return requested.keySet().stream().map(found::get).toList();
    }

    /** 仅复核有界清单中的记录；规则异常必须上抛，不能把不完整资格集合用于执行。 */
    public Set<String> qualifiedPlanKeys(String tenantId, Set<String> companies, List<CatalogEntry> reports,
                                         Map<String, ? extends Collection<String>> recordIds) {
        Set<String> qualified = new HashSet<>();
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
                    if (rule.isPresent() && matchesRequired(rule.get().getExpression(), row)) {
                        qualified.add(toCandidate(report, row, rule.get().getId(), rule.get().getName(),
                                rule.get().getVersion(), rule.get().getDescription()).key());
                    }
                }
            }
        }
        return qualified;
    }

    /** 指定报表范围、公司范围内按各报表当前生效规则应派单的记录；结果按传入的报表顺序排列*/
    public List<Candidate> findCandidates(String tenantId, Set<String> companies, List<CatalogEntry> reports) {
        return findCandidates(tenantId, companies, reports, Integer.MAX_VALUE);
    }

    public List<Candidate> findCandidates(String tenantId, Set<String> companies, List<CatalogEntry> reports, int maxMatches) {
        return findCandidates(tenantId, companies, reports, maxMatches, scanned -> { });
    }


    public List<Candidate> findCandidates(String tenantId, Set<String> companies, List<CatalogEntry> reports,
                                          int maxMatches, java.util.function.IntConsumer progress) {
        List<Candidate> result = new ArrayList<>();
        visitCandidates(tenantId, companies, reports, progress, candidate -> {
            result.add(candidate);
            return result.size() < maxMatches;
        });
        return result;
    }

    /** 有界游标扫描授权范围内的待派单事实并逐条输出匹配候选；进度表示扫描数，不能作为最终命中数量。 */
    public void scanCandidates(String tenantId, Set<String> companies, List<CatalogEntry> reports,
    java.util.function.IntConsumer progress,
                               java.util.function.Consumer<Candidate> consumer) {
        visitCandidates(tenantId, companies, reports, progress, candidate -> {
            consumer.accept(candidate);
            return true;
        });
    }

    /** 全部扫描受行数和时限预算约束；任一行规则求值失败即终止，部分候选不能激活为完整预览。 */
    private void visitCandidates(String tenantId, Set<String> companies, List<CatalogEntry> reports,
                                 java.util.function.IntConsumer progress,
                                 java.util.function.Predicate<Candidate> visitor) {
        int scanned = 0;
        if(maxScannedRows<1 || maxScanSeconds<1)throw new IllegalStateException("查询预算必须为正数");
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(maxScanSeconds);
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
                Set<String> cursors=new HashSet<>();
                // 扫描预算同时限制该集合内存；拒绝跨页重复，不能重复计数或构造重复清单。
                Set<String> seenIds=new HashSet<>();
                while (true) {
                    checkScanBudget(deadline,scanned);
                    List<FactRow> page = report.adapter().pendingRowsAfterWithRule(tenantId, Set.of(company),
                            afterId, 500, active.getExpression());
                    if(page.size()>500)throw new com.example.report.common.ApiException(502,"报表来源未遵守分页上限，未生成预览");
                    scanned = Math.addExact(scanned,page.size());
                    checkScanBudget(deadline,scanned);
                    progress.accept(scanned);
                    for (FactRow row : page) {
                        checkScanBudget(deadline,scanned);
                        if(row.recordId()==null || row.recordId().isBlank() || !seenIds.add(row.recordId()) || !company.equals(row.companyCode()))
                            throw new com.example.report.common.ApiException(502,"报表来源记录标识或范围无效，未生成预览");
                        if (matchesRequired(active.getExpression(), row)) {
                            if (!visitor.test(toCandidate(report, row, active.getId(), active.getName(),
                                    active.getVersion(), active.getDescription()))) break outer;
                        }
                    }
                    if (page.size() < 500) break;
                    String next=page.get(page.size() - 1).recordId();
                    if(!cursors.add(next))throw new com.example.report.common.ApiException(502,"报表来源游标未前进，未生成预览");
                    afterId = next;
                }
            }
        }
        checkScanBudget(deadline,scanned);
    }

    /** 超限只报告失败；部分扫描结果不具有业务完整性，不能返回给用户继续建单。 */
    private void checkScanBudget(long deadline,int scanned) {
        if(Thread.currentThread().isInterrupted() || System.nanoTime()-deadline>=0)
            throw new com.example.report.common.ApiException(408,"查询超时或已取消，未生成完整预览，请缩小范围后重试");
        if(scanned>maxScannedRows)throw new com.example.report.common.ApiException(422,"查询扫描量超过限制，未生成完整预览，请缩小范围后重试");
    }
    /** 规则失败与不命中必须分开；只允许正常布尔结果决定业务记录是否入选。 */
    private boolean matchesRequired(String expression,FactRow row) {
        try { return ruleEngine.matches(expression,row.facts()); }
        catch(RuntimeException invalid) {
            throw new com.example.report.common.ApiException(422,"来源字段或规则计算异常，查询结果不完整，请联系管理员检查规则或数据后重新查询");
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

    /** 仅供管理员试算收集求值错误；调用方必须展示错误数，普通业务预览不得使用此路径。 */
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
                row.label(), row.amount(), row.date(), ruleId, ruleName, ruleVersion, description, report.catalogVersion(), CounterpartyRef.fromFacts(row.facts()), FieldFact.capture(report.fields(),row.facts()));
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
