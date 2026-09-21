package com.example.report.rule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.report.common.ApiException;
import com.example.report.entity.DispatchRule;
import com.example.report.entity.DispatchRuleHistory;
import com.example.report.mapper.DispatchRuleHistoryMapper;
import com.example.report.mapper.DispatchRuleMapper;
import com.example.report.permission.CurrentUser;
import com.example.report.report.ReportType;
import com.example.report.rule.fact.FieldInfo;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 规则管理：草稿 → 试算 → 发布（旧版本自动停用）→ 回滚；每次动作落历史并广播刷新缓存
 */
@Service
public class RuleService {

    private final DispatchRuleMapper ruleMapper;
    private final DispatchRuleHistoryMapper historyMapper;
    private final RuleEngine ruleEngine;
    private final RuleCache ruleCache;
    private final DispatchCandidateService candidateService;

    public RuleService(DispatchRuleMapper ruleMapper, DispatchRuleHistoryMapper historyMapper, RuleEngine ruleEngine,
                       RuleCache ruleCache, DispatchCandidateService candidateService) {
        this.ruleMapper = ruleMapper;
        this.historyMapper = historyMapper;
        this.ruleEngine = ruleEngine;
        this.ruleCache = ruleCache;
        this.candidateService = candidateService;
    }

    public List<DispatchRule> list() {
        return ruleMapper.selectList(new LambdaQueryWrapper<DispatchRule>()
                .orderByAsc(DispatchRule::getReportType)
                .orderByAsc(DispatchRule::getCompanyCode)
                .orderByDesc(DispatchRule::getVersion));
    }

    public List<DispatchRuleHistory> history(String reportType, String companyCode) {
        return historyMapper.selectList(new LambdaQueryWrapper<DispatchRuleHistory>()
                .eq(reportType != null && !reportType.isBlank(), DispatchRuleHistory::getReportType, reportType)
                .eq(companyCode != null && !companyCode.isBlank(), DispatchRuleHistory::getCompanyCode, companyCode)
                .orderByDesc(DispatchRuleHistory::getId)
                .last("LIMIT 200"));
    }

    public List<FieldInfo> fields(String reportType) {
        return candidateService.fields(ReportType.fromCode(reportType));
    }

    /** 语法校验 + 变量必须是事实模型里的字段 */
    public Set<String> validate(String reportType, String expression) {
        ReportType type = ReportType.fromCode(reportType);
        Set<String> vars = ruleEngine.variables(expression);
        Set<String> known = candidateService.fields(type).stream().map(FieldInfo::name).collect(Collectors.toSet());
        List<String> unknown = vars.stream().filter(v -> !known.contains(v)).toList();
        if (!unknown.isEmpty()) {
            throw new ApiException("表达式引用了事实模型中不存在的字段：" + String.join("、", unknown));
        }
        return vars;
    }

    public DispatchCandidateService.DryRunResult dryRun(CurrentUser user, String reportType, String companyCode, String expression) {
        validate(reportType, expression);
        String company = normalizeCompany(companyCode);
        if (!DispatchRule.ANY_COMPANY.equals(company) && !user.companies().contains(company)) {
            throw ApiException.forbidden("公司 " + company + " 不在您的可见范围内");
        }
        return candidateService.dryRun(ReportType.fromCode(reportType), company, expression, user.companies());
    }

    /** 新建或修改草稿；已发布的规则不能直接改，只能新建版本 */
    @Transactional
    public DispatchRule saveDraft(CurrentUser user, RuleForm form) {
        validate(form.getReportType(), form.getExpression());
        String company = normalizeCompany(form.getCompanyCode());
        LocalDateTime now = LocalDateTime.now();
        DispatchRule rule;
        if (form.getId() != null) {
            rule = ruleMapper.selectById(form.getId());
            if (rule == null) {
                throw ApiException.notFound("规则不存在");
            }
            if (!DispatchRule.STATUS_DRAFT.equals(rule.getStatus())) {
                throw new ApiException("只有草稿可以修改，已发布的规则请新建版本");
            }
        } else {
            rule = new DispatchRule();
            rule.setReportType(ReportType.fromCode(form.getReportType()).code());
            rule.setCompanyCode(company);
            rule.setVersion(nextVersion(rule.getReportType(), company));
            rule.setStatus(DispatchRule.STATUS_DRAFT);
            rule.setCreatedAt(now);
        }
        rule.setName(form.getName() == null || form.getName().isBlank() ? defaultName(rule) : form.getName().trim());
        rule.setDescription(form.getDescription());
        rule.setExpression(form.getExpression().trim());
        rule.setEffectiveFrom(form.getEffectiveFrom());
        rule.setEffectiveTo(form.getEffectiveTo());
        rule.setUpdatedBy(user.userId());
        rule.setUpdatedAt(now);
        if (rule.getId() == null) {
            ruleMapper.insert(rule);
        } else {
            ruleMapper.updateById(rule);
        }
        return rule;
    }

    /** 发布草稿：同范围之前已发布的版本自动停用 */
    @Transactional
    public DispatchRule publish(CurrentUser user, Long id) {
        DispatchRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw ApiException.notFound("规则不存在");
        }
        if (!DispatchRule.STATUS_DRAFT.equals(rule.getStatus())) {
            throw new ApiException("只有草稿可以发布");
        }
        validate(rule.getReportType(), rule.getExpression());
        LocalDateTime now = LocalDateTime.now();
        List<DispatchRule> published = ruleMapper.selectList(new LambdaQueryWrapper<DispatchRule>()
                .eq(DispatchRule::getReportType, rule.getReportType())
                .eq(DispatchRule::getCompanyCode, rule.getCompanyCode())
                .eq(DispatchRule::getStatus, DispatchRule.STATUS_PUBLISHED));
        for (DispatchRule old : published) {
            old.setStatus(DispatchRule.STATUS_DISABLED);
            old.setUpdatedBy(user.userId());
            old.setUpdatedAt(now);
            ruleMapper.updateById(old);
            history(old, "disable", user.userId(), now);
        }
        rule.setStatus(DispatchRule.STATUS_PUBLISHED);
        rule.setUpdatedBy(user.userId());
        rule.setUpdatedAt(now);
        ruleMapper.updateById(rule);
        history(rule, "publish", user.userId(), now);
        ruleCache.broadcastRefresh();
        return rule;
    }

    @Transactional
    public DispatchRule disable(CurrentUser user, Long id) {
        DispatchRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw ApiException.notFound("规则不存在");
        }
        if (!DispatchRule.STATUS_PUBLISHED.equals(rule.getStatus())) {
            throw new ApiException("只有已发布的规则可以停用");
        }
        LocalDateTime now = LocalDateTime.now();
        rule.setStatus(DispatchRule.STATUS_DISABLED);
        rule.setUpdatedBy(user.userId());
        rule.setUpdatedAt(now);
        ruleMapper.updateById(rule);
        history(rule, "disable", user.userId(), now);
        ruleCache.broadcastRefresh();
        return rule;
    }

    /** 回滚到某个历史版本：复制其表达式为新版本并直接发布 */
    @Transactional
    public DispatchRule rollback(CurrentUser user, Long id) {
        DispatchRule target = ruleMapper.selectById(id);
        if (target == null) {
            throw ApiException.notFound("规则不存在");
        }
        if (DispatchRule.STATUS_PUBLISHED.equals(target.getStatus())) {
            throw new ApiException("该版本已经是当前生效版本");
        }
        LocalDateTime now = LocalDateTime.now();
        List<DispatchRule> published = ruleMapper.selectList(new LambdaQueryWrapper<DispatchRule>()
                .eq(DispatchRule::getReportType, target.getReportType())
                .eq(DispatchRule::getCompanyCode, target.getCompanyCode())
                .eq(DispatchRule::getStatus, DispatchRule.STATUS_PUBLISHED));
        for (DispatchRule old : published) {
            old.setStatus(DispatchRule.STATUS_DISABLED);
            old.setUpdatedBy(user.userId());
            old.setUpdatedAt(now);
            ruleMapper.updateById(old);
            history(old, "disable", user.userId(), now);
        }
        DispatchRule rule = new DispatchRule();
        rule.setReportType(target.getReportType());
        rule.setCompanyCode(target.getCompanyCode());
        rule.setName(target.getName());
        rule.setDescription(target.getDescription());
        rule.setExpression(target.getExpression());
        rule.setVersion(nextVersion(target.getReportType(), target.getCompanyCode()));
        rule.setStatus(DispatchRule.STATUS_PUBLISHED);
        rule.setCreatedAt(now);
        rule.setUpdatedBy(user.userId());
        rule.setUpdatedAt(now);
        ruleMapper.insert(rule);
        history(rule, "rollback", user.userId(), now);
        ruleCache.broadcastRefresh();
        return rule;
    }

    @Transactional
    public void deleteDraft(Long id) {
        DispatchRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw ApiException.notFound("规则不存在");
        }
        if (!DispatchRule.STATUS_DRAFT.equals(rule.getStatus())) {
            throw new ApiException("只有草稿可以删除");
        }
        ruleMapper.deleteById(id);
    }

    private int nextVersion(String reportType, String companyCode) {
        List<DispatchRule> all = ruleMapper.selectList(new LambdaQueryWrapper<DispatchRule>()
                .eq(DispatchRule::getReportType, reportType)
                .eq(DispatchRule::getCompanyCode, companyCode)
                .orderByDesc(DispatchRule::getVersion)
                .last("LIMIT 1"));
        return all.isEmpty() ? 1 : all.get(0).getVersion() + 1;
    }

    private void history(DispatchRule rule, String action, String operator, LocalDateTime at) {
        DispatchRuleHistory h = new DispatchRuleHistory();
        h.setRuleId(rule.getId());
        h.setReportType(rule.getReportType());
        h.setCompanyCode(rule.getCompanyCode());
        h.setVersion(rule.getVersion());
        h.setName(rule.getName());
        h.setExpression(rule.getExpression());
        h.setDescription(rule.getDescription());
        h.setAction(action);
        h.setOperatedBy(operator);
        h.setOperatedAt(at);
        historyMapper.insert(h);
    }

    private static String normalizeCompany(String companyCode) {
        if (companyCode == null || companyCode.isBlank()) {
            return DispatchRule.ANY_COMPANY;
        }
        return companyCode.trim().toUpperCase();
    }

    private static String defaultName(DispatchRule rule) {
        String scope = DispatchRule.ANY_COMPANY.equals(rule.getCompanyCode()) ? "默认" : rule.getCompanyCode() + " 公司";
        return ReportType.fromCode(rule.getReportType()).label() + scope + "规则 v" + rule.getVersion();
    }

    @Data
    public static class RuleForm {
        private Long id;
        private String reportType;
        private String companyCode;
        private String name;
        private String description;
        private String expression;
        private LocalDateTime effectiveFrom;
        private LocalDateTime effectiveTo;
    }
}
