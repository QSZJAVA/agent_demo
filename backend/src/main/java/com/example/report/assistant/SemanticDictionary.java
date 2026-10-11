package com.example.report.assistant;

import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.ApiException;
import com.example.report.rule.FieldFact;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.*;
import java.util.*;

/** 业务词典的有界类型契约；词典仅提供语义事实，不能创建业务字段、权限或可执行意图。 */
public final class SemanticDictionary {
    private SemanticDictionary() { }
    /**
     * 单份词典的业务范围。
     * @param domain 报表、派单记录或工单数据域
     * @param companyCode 当前授权公司代码
     * @param reportId 当前授权报表稳定标识
     */
    public record Scope(BusinessQuery.Domain domain,String companyCode,String reportId) {
        public Scope {
            if(domain==null || companyCode==null || companyCode.isBlank() || companyCode.length()>32
                    || reportId==null || !reportId.matches("[a-z][a-z0-9-]{2,63}")) fail("业务词典范围无效");
        }
    }
    /**
     * 相对于来源字段基本单位的精确倍率。
     * @param name 用户使用的单位名称，例如万元
     * @param factor 换算至来源单位的正十进制倍率字符串，不经过浮点转换
     */
    public record Unit(String name,String factor) {
        public Unit {
            text(name,32,"单位");
            // 与来源数值采用同一精度和指数边界，避免短指数在模型输入中展开成无界金额。
            try {if(((BigDecimal)FieldFact.scalar("decimal",factor)).signum()<=0)fail("单位倍率必须为有界正十进制数");}
            catch(RuntimeException e){fail("单位倍率必须为有界正十进制数");}
        }
    }
    /**
     * 一个来源枚举值及其业务叫法。
     * @param value 按实际字段类型解析的来源标量值
     * @param aliases 该值的业务别名，同字段内不能与其他值冲突
     */
    public record Value(String value,List<String> aliases) {
        public Value { text(value,128,"枚举值");aliases=names(aliases,12,"枚举别名"); }
    }
    /**
     * 已存在字段的补充业务含义，不改变查询字段的类型和权限。
     * @param field 目录内真实字段名称
     * @param aliases 字段业务别名
     * @param meaning 经管理员维护的业务定义，空串表示仅使用目录说明
     * @param baseUnit 来源字段基本单位，空串表示未声明
     * @param currency 已核实的币种代码，空串表示未声明，不能据此合并未知币种金额
     * @param units 显式单位换算表，空集合表示没有额外单位
     * @param values 已知枚举值与别名，空集合表示没有枚举定义
     */
    public record Field(String field,List<String> aliases,String meaning,String baseUnit,String currency,List<Unit> units,List<Value> values) {
        public Field {
            if(field==null || !field.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))fail("词典字段名称无效");
            aliases=names(aliases,12,"字段别名");optional(meaning,512,"业务定义");optional(baseUnit,32,"基本单位");optional(currency,3,"币种");
            if(!currency.isEmpty() && !currency.matches("[A-Z]{3}"))fail("币种须为三位大写代码，未知时留空");
            if(units==null || values==null || units.size()>12 || values.size()>40)fail("单位或枚举数量超过上限");
            units=List.copyOf(units);values=List.copyOf(values);
            if(units.stream().map(u->normalize(u.name())).distinct().count()!=units.size())fail("同一字段的单位名称重复");
            if(!units.isEmpty() && baseUnit.isBlank())fail("配置单位换算前须明确来源基本单位");
            if(values.stream().map(Value::value).distinct().count()!=values.size())fail("同一字段的枚举值重复");
            var claimed=new HashMap<String,String>();
            for(var v:values) {var words=new ArrayList<>(v.aliases());words.add(v.value());for(String word:words)claim(claimed,word,v.value(),"同一枚举别名指向不同来源值");}
        }
    }
    /**
     * 管理员发布的完整语义定义。
     * @param companyName 当前公司展示名称，空串时使用可信公司代码
     * @param companyAliases 当前公司的称谓，不在不同公司之间自动合并身份
     * @param timeZone 本范围时间口径的IANA时区
     * @param fiscalStartMonth 财务年度起始月份，1至12
     * @param holidays 明确维护的非工作日ISO日期，未列出不代表已接入完整企业日历
     * @param fields 真实目录字段的语义定义
     */
    public record Definition(String companyName,List<String> companyAliases,String timeZone,int fiscalStartMonth,List<String> holidays,List<Field> fields) {
        public Definition {
            optional(companyName,128,"公司名称");companyAliases=names(companyAliases,12,"公司别名");
            try {ZoneId.of(timeZone);}catch(RuntimeException invalid){fail("请选择有效IANA时区");}
            if(fiscalStartMonth<1 || fiscalStartMonth>12 || holidays==null || holidays.size()>366 || fields==null || fields.size()>64)fail("词典月份、日历或字段数量无效");
            try {for(String day:holidays)if(!LocalDate.parse(day).toString().equals(day))fail("非工作日须为ISO日期");}catch(RuntimeException invalid){fail("非工作日须为ISO日期");}
            holidays=holidays.stream().distinct().sorted().toList();fields=List.copyOf(fields);
            if(fields.stream().map(Field::field).distinct().count()!=fields.size())fail("词典字段重复");
            var claimed=new HashMap<String,String>();
            for(var f:fields){claim(claimed,f.field(),f.field(),"字段别名指向多个字段");for(String alias:f.aliases())claim(claimed,alias,f.field(),"字段别名指向多个字段");}
        }
        /** 未配置范围的明确默认口径；不虚构公司名称、币种或来源字段。 */
        public static Definition empty(){return new Definition("",List.of(),"Asia/Shanghai",1,List.of(),List.of());}
        /** 校验目录字段、单位和枚举类型；词典不能越过数据源字段白名单。 */
        public void validate(List<FieldInfo> columns) {
            var known=new HashMap<String,FieldInfo>();columns.forEach(c->known.put(c.name(),c));
            for(var f:fields){var actual=known.get(f.field());if(actual==null)fail("词典引用未配置字段："+f.field());
                if((!f.units().isEmpty() || !f.baseUnit().isEmpty() || !f.currency().isEmpty()) && !Set.of("integer","long","decimal").contains(actual.type()))fail("只有数值字段可以定义单位或币种");
                var typedValues=new HashSet<Object>();
                for(var value:f.values()) {
                    Object typed=FieldFact.scalar(actual.type(),value.value());
                    // 数值枚举按来源类型比较；字符串编号001仍与1保持不同身份。
                    if(typed instanceof BigDecimal number)typed=number.stripTrailingZeros();
                    if(!typedValues.add(typed))fail("同一字段存在等价的枚举值");
                }
                // 字段名即稳定协议名；不能用别名遮蔽同范围其他真实字段。
                for(String alias:f.aliases())for(String key:known.keySet())if(normalize(alias).equals(normalize(key)) && !key.equals(f.field()))fail("字段别名与其他来源字段冲突");
            }
        }
        /** 依据同一参考时刻计算自然月和财务年度，供所有模型阶段读取相同时间事实。 */
        public Map<String,Object> calendar(Instant at) {
            var today=at.atZone(ZoneId.of(timeZone)).toLocalDate();var month=today.withDayOfMonth(1);
            var fiscal=LocalDate.of(today.getYear(),fiscalStartMonth,1);if(fiscal.isAfter(today))fiscal=fiscal.minusYears(1);
            return Map.of("timeZone",timeZone,"today",today.toString(),"monthStart",month.toString(),"nextMonthStart",month.plusMonths(1).toString(),
                    "previousMonthStart",month.minusMonths(1).toString(),"previousMonthEndExclusive",month.toString(),"fiscalYearStart",fiscal.toString(),
                    "nextFiscalYearStart",fiscal.plusYears(1).toString(),"configuredHolidays",holidays,"holidayCalendarComplete",false);
        }
    }
    /** 规范化仅用于词典冲突检查，不产生业务意图或替代稳定对象身份。 */
    static String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);}
    private static List<String> names(List<String> values,int max,String label){if(values==null || values.size()>max)fail(label+"数量无效");for(String s:values)text(s,128,label);if(values.stream().map(SemanticDictionary::normalize).distinct().count()!=values.size())fail(label+"重复");return List.copyOf(values);}
    private static void claim(Map<String,String> claimed,String name,String target,String reason){var prior=claimed.putIfAbsent(normalize(name),target);if(prior!=null && !prior.equals(target))fail(reason);}
    private static void optional(String value,int max,String label){if(value==null || value.length()>max || value.codePoints().anyMatch(c->Character.isISOControl(c)))fail(label+"无效");}
    private static void text(String value,int max,String label){optional(value,max,label);if(value.isBlank())fail(label+"不能为空");}
    private static void fail(String message){throw new ApiException(422,message);}
}
