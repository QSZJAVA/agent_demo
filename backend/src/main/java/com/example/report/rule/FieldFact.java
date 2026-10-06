package com.example.report.rule;

import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 来源目录声明的标量字段快照；值以规范字符串保存，避免JSON数值反序列化损失精度。
 * @param name 目录事实字段名，不是模型提供的数据库列名
 * @param type string、decimal、long、integer、date或boolean，沿用来源字段类型
 * @param value 原币种/单位下的精确数值、ISO日期或文本；null表示来源值为空
 */
public record FieldFact(String name,String type,String value) {
    public FieldFact {
        if(name==null || !name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}") || !supported(type)
                || (value!=null && value.length()>4096)) throw new ApiException(422,"字段快照不符合目录标量契约");
        if(value!=null) scalar(type,value);
    }
    /** 标量白名单不包含JSON对象、脚本或SQL；字段能力必须有同类型确定性求值实现。 */
    public static boolean supported(String type) { return type!=null && Set.of("string","decimal","long","integer","date","boolean").contains(type); }
    /** 按目录类型验证来源或模型字面量；金额使用BigDecimal，不通过浮点数比较。 */
    public static Object scalar(String type,String value) {
        try {return switch(type) {
            case "string" -> value;
            case "decimal" -> decimal(value);
            case "integer" -> decimal(value).intValueExact();
            case "long" -> decimal(value).longValueExact();
            case "date" -> LocalDate.parse(value);
            case "boolean" -> {if(!Set.of("true","false").contains(value)) throw new IllegalArgumentException();yield Boolean.valueOf(value);}
            default -> throw new IllegalArgumentException();
        };} catch(RuntimeException error) {throw new ApiException(422,"字段值与类型“"+type+"”不匹配，请核对条件");}
    }
    /** 覆盖MySQL DECIMAL的65位精度与30位小数；限制指数和输入长度，且不舍入来源值。 */
    private static BigDecimal decimal(String value) {
        if(value.length()>128) throw new IllegalArgumentException();
        var number=new BigDecimal(value);
        if(number.precision()>65 || Math.abs((long)number.scale())>30) throw new IllegalArgumentException();
        return number;
    }
    /** 从授权来源事实冻结已声明的字段；不读取未配置列，不在刷新页面时重新读取业务源。 */
    public static List<FieldFact> capture(List<FieldInfo> fields,Map<String,Object> facts) {
        if(fields.size()>128) throw new ApiException(422,"报表字段超过快照上限");
        List<FieldFact> result=new ArrayList<>();
        for(var field:fields) if(supported(field.type()) && !field.name().equals("counterpartyAliases")) {
            if(!facts.containsKey(field.name())) throw new ApiException(422,"来源缺少已配置字段："+field.name());
            Object value=facts.get(field.name());
            result.add(new FieldFact(field.name(),field.type(),value==null?null:value instanceof BigDecimal number?number.toPlainString():value.toString()));
        }
        return List.copyOf(result);
    }
    /** 当前版本快照恢复；null和损坏数据均拒绝，不从旧来源或展示金额补造字段事实。 */
    public static List<FieldFact> restore(String json) {
        try {if(json==null) throw new IllegalArgumentException();return List.copyOf(JsonUtil.MAPPER.readValue(json,new com.fasterxml.jackson.core.type.TypeReference<List<FieldFact>>(){}));}
        catch(Exception error){throw new ApiException(422,"字段快照无法读取，请重新查询");}
    }
}
