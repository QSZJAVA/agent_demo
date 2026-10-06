package com.example.report.rule;

import com.example.report.catalog.query.FactRow;
import com.example.report.catalog.query.FieldInfo;
import java.math.BigDecimal;
import java.util.*;

/** 已确认业务事实的通用一致性边界；由业务写入方在来源行锁内调用，不重新解释自然语言。 */
public final class ConfirmedRecord {
    private ConfirmedRecord() { }

    /**
     * 比较记录身份、金额日期及目录声明的全部标量快照；任一字段变化均要求重新查询确认。
     * 数字按精确数值比较，字段顺序不影响结果；缺失、重复或损坏字段拒绝，不能补成空快照。
     * @param frozen 已确认清单中的不可变记录
     * @param current 当前事务锁定的来源记录
     * @param fields 当前已验证目录的字段契约
     * @return 当前记录是否仍与确认事实一致；本方法不写库
     */
    public static boolean matches(Candidate frozen, FactRow current, List<FieldInfo> fields) {
        if (frozen == null || current == null || !Objects.equals(frozen.recordId(), current.recordId())
                || !Objects.equals(frozen.companyCode(), current.companyCode())
                || !Objects.equals(frozen.date(), current.date()) || !numberEquals(frozen.amount(), current.amount())) return false;
        var customer=CounterpartyRef.fromFacts(current.facts());
        if (frozen.counterparty()==null ? customer!=null : customer==null || !frozen.counterparty().id().equals(customer.id())) return false;
        try {
            return normalized(frozen.fields()).equals(normalized(FieldFact.capture(fields,current.facts())));
        } catch(RuntimeException invalid) { return false; }
    }

    /** 按字段名建立精确比较结构；拒绝重复名，避免覆盖后误认为字段一致。 */
    private static Map<String,Object> normalized(List<FieldFact> fields) {
        if(fields==null)throw new IllegalArgumentException("缺少确认字段快照");
        var values=new LinkedHashMap<String,Object>();
        for(var field:fields) {
            if(field==null || values.containsKey(field.name()))throw new IllegalArgumentException("确认字段重复或为空");
            Object value=field.value()==null?null:FieldFact.scalar(field.type(),field.value());
            if(value instanceof BigDecimal number)value=number.stripTrailingZeros();
            values.put(field.name(),Arrays.asList(field.type(),value));
        }
        return values;
    }
    private static boolean numberEquals(BigDecimal a,BigDecimal b) { return a==null?b==null:b!=null && a.compareTo(b)==0; }
}
