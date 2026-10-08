package com.example.business;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.report.assistant.DispatchEligibility;
import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.query.FactRow;
import com.example.report.common.ApiException;
import com.example.report.entity.DispatchRule;
import com.example.report.mapper.DispatchRuleMapper;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.FieldFact;
import com.example.report.rule.RuleEngine;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

/** 单记录派单资格只读核验；沿用业务查询事务和来源事实，按正式派单相同的公司优先级、生效时间及表达式引擎判断。 */
@Service
public class DispatchEligibilityService {
    private final DispatchRuleMapper rules;
    private final RuleEngine engine;

    public DispatchEligibilityService(DispatchRuleMapper rules,RuleEngine engine) {
        this.rules=rules;this.engine=engine;
    }

    /**
     * 核验已唯一定位的当前来源记录；调用方须处于业务查询的只读快照事务，不获取执行锁或创建预览。
     * @param user 服务认证解析的当前身份，仍复核租户、公司和报表权限
     * @param report 本次授权目录快照
     * @param fact 同次读取取得的原始类型事实，不使用浏览器或模型提供的金额和状态
     * @param sourceStatus 同次读取取得的来源派单状态
     * @return 结论、适用规则版本和被求值字段；规则异常上抛，不能伪装成不符合条件
     */
    public DispatchEligibility evaluate(CurrentUser user,CatalogEntry report,FactRow fact,String sourceStatus) {
        if(!Objects.equals(user.tenantId(),report.tenantId()) || !user.hasPermission(report.permissionCode())
                || fact==null || !user.companies().contains(fact.companyCode()))
            throw ApiException.notFound("记录不存在或无权查看");
        if(!report.dispatchEnabled())return unavailable("该报表当前未启用派单，不能按规则派单。");
        if("已派单".equals(sourceStatus))return unavailable("该记录当前已派单，不符合再次派单的条件。");
        if(!"未派单".equals(sourceStatus))throw new ApiException(422,"来源派单状态不明确，暂时无法判断该记录的派单资格，请核对来源状态后重试");
        LocalDateTime now=LocalDateTime.now();
        // 普通快照读与正式执行的加锁读职责不同；资格结论仅描述此刻，执行时仍按确认版本重新校验。
        var active=rules.selectList(new LambdaQueryWrapper<DispatchRule>()
                .eq(DispatchRule::getTenantId,user.tenantId()).eq(DispatchRule::getReportId,report.reportId())
                .eq(DispatchRule::getStatus,DispatchRule.STATUS_PUBLISHED)
                .in(DispatchRule::getCompanyCode,List.of(fact.companyCode(),DispatchRule.ANY_COMPANY))).stream()
                .filter(r->user.tenantId().equals(r.getTenantId()) && report.reportId().equals(r.getReportId())
                        && DispatchRule.STATUS_PUBLISHED.equals(r.getStatus()))
                .filter(r->fact.companyCode().equals(r.getCompanyCode()) || DispatchRule.ANY_COMPANY.equals(r.getCompanyCode()))
                .filter(r->(r.getEffectiveFrom()==null || !r.getEffectiveFrom().isAfter(now))
                        && (r.getEffectiveTo()==null || r.getEffectiveTo().isAfter(now)))
                .sorted(Comparator.<DispatchRule,Boolean>comparing(r->!fact.companyCode().equals(r.getCompanyCode()))
                        .thenComparing(DispatchRule::getVersion,Comparator.reverseOrder()))
                .findFirst().orElse(null);
        if(active==null)return unavailable("该记录所属报表和公司当前没有适用的生效派单规则，暂不具备按规则派单的条件。");
        try {
            var variables=engine.variables(active.getExpression());
            var fields=FieldFact.capture(report.fields(),fact.facts()).stream().filter(f->variables.contains(f.name())).toList();
            if(!fields.stream().map(FieldFact::name).collect(java.util.stream.Collectors.toSet()).containsAll(variables))
                throw new IllegalStateException("规则引用未配置字段");
            boolean eligible=engine.matches(active.getExpression(),fact.facts());
            return new DispatchEligibility(eligible,eligible?"该记录当前未派单，并且满足当前生效的派单规则。":"该记录当前未派单，但不满足当前生效的派单规则。",
                    String.valueOf(active.getId()),active.getName(),active.getVersion(),active.getDescription(),fields);
        } catch(RuntimeException invalid) {
            throw new ApiException(422,"该记录的来源字段或派单规则计算异常，暂时无法判断是否符合条件，请检查规则和数据后重试");
        }
    }

    /** 缺少当前派单前提时明确返回原因；不会把未执行过的规则计算写为已经命中或未命中。 */
    private static DispatchEligibility unavailable(String reason) {
        return new DispatchEligibility(false,reason,null,null,null,null,List.of());
    }
}
