package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.rule.FieldFact;
import java.util.List;

/**
 * 单条记录在本次业务读取快照中的派单资格证据；只由业务服务计算，不授予派单执行或确认权限。
 * @param eligible 是否同时满足报表启用派单、来源未派单和当前生效规则；计算失败不返回此对象
 * @param reason 业务结论及原因，不含模型推算或执行成功承诺
 * @param ruleId 本次适用的dispatch_rule.id，使用字符串保留精度；未进入规则求值时为空
 * @param ruleName 本次适用规则名称；没有适用规则时为空
 * @param ruleVersion 本次适用的规则版本；没有适用规则时为空
 * @param ruleDescription 适用规则的业务说明；未维护说明或没有适用规则时为空
 * @param checkedFields 规则实际引用的目录标量字段及当前值；未进入规则求值时为空集合
 */
public record DispatchEligibility(Boolean eligible,String reason,String ruleId,String ruleName,Integer ruleVersion,
                                  String ruleDescription,List<FieldFact> checkedFields) {
    public DispatchEligibility {
        if(eligible==null || reason==null || reason.isBlank() || checkedFields==null || checkedFields.size()>128)
            throw new ApiException(502,"派单资格核验结果不完整");
        checkedFields=List.copyOf(checkedFields);
        if(Boolean.TRUE.equals(eligible) && (ruleId==null || ruleName==null || ruleVersion==null))
            throw new ApiException(502,"派单资格核验缺少生效规则证据");
    }
}
