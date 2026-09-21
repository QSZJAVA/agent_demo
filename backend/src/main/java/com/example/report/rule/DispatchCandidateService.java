package com.example.report.rule;

import com.example.report.entity.DispatchRule;
import com.example.report.report.ReportType;
import com.example.report.rule.fact.FactAssembler;
import com.example.report.rule.fact.FactRow;
import com.example.report.rule.fact.FieldInfo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 候选记录查找：SQL 粗筛（装配器）→ 按当前生效规则逐行求值
 */
@Service
public class DispatchCandidateService {

    private final Map<ReportType, FactAssembler> assemblers = new EnumMap<>(ReportType.class);
    private final RuleCache ruleCache;
    private final RuleEngine ruleEngine;

    public DispatchCandidateService(List<FactAssembler> assemblerList, RuleCache ruleCache, RuleEngine ruleEngine) {
        assemblerList.forEach(a -> assemblers.put(a.type(), a));
        this.ruleCache = ruleCache;
        this.ruleEngine = ruleEngine;
    }

    public List<FieldInfo> fields(ReportType type) {
        return assemblers.get(type).fields();
    }

    /** 当前用户可见公司范围内、按各报表当前生效规则应派单的记录；filter 为 null 表示全部报表 */
    public List<Candidate> findCandidates(Set<String> companies, ReportType filter) {
        List<Candidate> result = new ArrayList<>();
        for (ReportType type : ReportType.values()) {
            if (filter != null && filter != type) {
                continue;
            }
            for (FactRow row : assemblers.get(type).rows(companies)) {
                Optional<DispatchRule> rule = ruleCache.find(type, row.companyCode());
                if (rule.isEmpty()) {
                    continue;
                }
                DispatchRule r = rule.get();
                if (ruleEngine.matches(r.getExpression(), row.facts())) {
                    result.add(toCandidate(type, row, r.getName(), r.getVersion(), r.getDescription()));
                }
            }
        }
        return result;
    }

    /** 试算：对某个范围（具体公司或通配 = 全部公司）的粗筛结果跑一个任意表达式 */
    public DryRunResult dryRun(ReportType type, String companyCode, String expression, Set<String> allCompanies) {
        Set<String> scope = DispatchRule.ANY_COMPANY.equals(companyCode) ? allCompanies : Set.of(companyCode);
        List<FactRow> rows = assemblers.get(type).rows(scope);
        List<Candidate> hits = new ArrayList<>();
        for (FactRow row : rows) {
            if (ruleEngine.matches(expression, row.facts())) {
                hits.add(toCandidate(type, row, "试算", 0, null));
            }
        }
        return new DryRunResult(rows.size(), hits.size(), hits.stream().limit(20).toList());
    }

    private static Candidate toCandidate(ReportType type, FactRow row, String ruleName, Integer ruleVersion, String description) {
        return new Candidate(type.code(), type.label(), row.recordId(), row.docNo(), row.companyCode(),
                row.label(), row.amount(), row.date(), ruleName, ruleVersion, description);
    }

    /** 试算结果：范围内总条数、命中条数、样例 */
    public record DryRunResult(int total, int hitCount, List<Candidate> samples) {
    }
}
