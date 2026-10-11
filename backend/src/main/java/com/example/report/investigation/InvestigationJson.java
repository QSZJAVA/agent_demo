package com.example.report.investigation;

import com.example.report.common.Digests;
import com.example.report.common.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.ArrayList;
import java.util.TreeMap;

/**
 * 调查专用确定性 JSON；递归排序对象键，并以精确数值统一整数、小数尾零等等价表示。
 * 配置、来源指纹、幂等摘要和工具契约哈希共用此规则；字符串、布尔、空值及数组顺序保留原有含义。
 */
public final class InvestigationJson {
    private static final com.fasterxml.jackson.databind.ObjectWriter WRITER=JsonUtil.MAPPER.copy()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN).writer();
    private InvestigationJson() { }
    /**
     * 按当前 JSON 语义生成稳定文本；金额字段自身的字符串注解仍生效，不将数字字符串转成数字。
     * 仅消除数值表示差异，不通过 double 近似比较；真实金额变化和布尔/数字类型变化必须产生不同结果。
     */
    public static String canonical(Object value) {
        try {return WRITER.writeValueAsString(normalize(JsonUtil.MAPPER.valueToTree(value)));}
        catch(Exception error) {throw new IllegalStateException("调查JSON序列化失败",error);}
    }
    public static String hash(Object value) {return Digests.sha256(canonical(value));}

    /**
     * 在无损 JSON 树上递归处理：1、1.0 和 1.000 表示同一数值，true 和 "1" 仍是不同类型。
     * 拒绝非 JSON 数字，避免 NaN/Infinity 被当成同名字符串；原输入及其 BigDecimal scale 不被修改。
     */
    private static Object normalize(JsonNode value) {
        if(value.isObject()) {
            var sorted=new TreeMap<String,Object>();
            value.fields().forEachRemaining(field -> sorted.put(field.getKey(),normalize(field.getValue())));
            return sorted;
        }
        if(value.isArray()) {
            var ordered=new ArrayList<Object>();value.forEach(item -> ordered.add(normalize(item)));return ordered;
        }
        if(value.isNumber()) {
            Number number=value.numberValue();
            if((number instanceof Double d && !Double.isFinite(d)) || (number instanceof Float f && !Float.isFinite(f)))
                throw new IllegalArgumentException("调查 JSON 不允许非有限数值");
            return value.decimalValue().stripTrailingZeros();
        }
        return value;
    }
}
