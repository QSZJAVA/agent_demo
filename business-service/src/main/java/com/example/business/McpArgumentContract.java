package com.example.business;

import com.example.report.common.ApiException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * HTTP MCP 参数的确定性边界；执行服务端已发布契约中的类型、嵌套结构、范围和长度约束。
 * 校验先于身份解析和业务调用，不依赖客户端遵循 Schema，也不允许 Jackson 将小数截为执行版本。
 * 仅解释本服务实际使用的 Schema 子集，不执行外部引用或调用方提供的 Schema。
 */
final class McpArgumentContract {
    private McpArgumentContract() { }

    /** 按工具注册时的可信字段契约校验整个参数对象；失败只返回字段路径，不回显业务值。 */
    static void validate(Map<String,Object> properties,List<String> required,Object arguments) {
        check(Map.of("type","object","properties",properties,"required",required,"additionalProperties",false),arguments,"参数",0);
    }

    /** 将已解码的 JSON 数值无损转为有界整数；字符串、布尔、小数、非有限值及溢出一律拒绝。 */
    static long integer(Object value,String field,long minimum,long maximum) {
        try {
            long number=decimal(value,field).longValueExact();
            if(number<minimum || number>maximum)throw new ArithmeticException();
            return number;
        } catch(ArithmeticException invalid) {
            throw invalid(field,"必须为范围内的整数");
        }
    }

    /** 递归约束先检查类型及集合上界，再进入子节点；路径均来自服务端字段名。 */
    @SuppressWarnings("unchecked")
    private static void check(Map<String,Object> schema,Object value,String path,int depth) {
        if(depth>16)throw invalid(path,"嵌套层级过多");
        Object declared=schema.get("type");
        List<?> types=declared instanceof List<?> list?list:List.of(declared);
        boolean matches=types.stream().anyMatch(type->matches(Objects.toString(type),value));
        if(!matches)throw invalid(path,"类型不符合契约");
        if(schema.get("enum") instanceof List<?> values && values.stream().noneMatch(option->equal(option,value)))
            throw invalid(path,"不属于允许的取值");
        if(value==null)return;
        if(value instanceof Map<?,?> object) {
            var properties=(Map<String,Object>)schema.getOrDefault("properties",Map.of());
            if(Boolean.FALSE.equals(schema.get("additionalProperties")) && !properties.keySet().containsAll(object.keySet()))
                throw invalid(path,"包含未声明字段");
            if(schema.get("required") instanceof List<?> required && !object.keySet().containsAll(required))
                throw invalid(path,"缺少必填字段");
            for(var property:properties.entrySet())if(object.containsKey(property.getKey()))
                check((Map<String,Object>)property.getValue(),object.get(property.getKey()),path+"."+property.getKey(),depth+1);
        } else if(value instanceof List<?> array) {
            bounded(array.size(),schema,"minItems","maxItems",path);
            if(schema.get("items") instanceof Map<?,?> itemSchema)
                for(int index=0;index<array.size();index++)check((Map<String,Object>)itemSchema,array.get(index),path+"["+index+"]",depth+1);
        } else if(value instanceof String string) {
            bounded(string.codePointCount(0,string.length()),schema,"minLength","maxLength",path);
            if(schema.get("pattern") instanceof String pattern && !Pattern.compile(pattern).matcher(string).find())
                throw invalid(path,"格式不符合契约");
        } else if(value instanceof Number) {
            BigDecimal number=decimal(value,path);
            if(types.contains("integer") && number.stripTrailingZeros().scale()>0)throw invalid(path,"必须为整数");
            if(schema.get("minimum") instanceof Number min && number.compareTo(decimal(min,path))<0
                    || schema.get("maximum") instanceof Number max && number.compareTo(decimal(max,path))>0)
                throw invalid(path,"超出允许范围");
        }
    }

    private static boolean matches(String type,Object value) {
        return switch(type) {
            case "null" -> value==null;
            case "object" -> value instanceof Map<?,?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "number","integer" -> value instanceof Number;
            default -> false;
        };
    }

    private static boolean equal(Object first,Object second) {
        if(first instanceof Number && second instanceof Number)return decimal(first,"枚举").compareTo(decimal(second,"枚举"))==0;
        return Objects.equals(first,second);
    }

    private static BigDecimal decimal(Object value,String path) {
        if(!(value instanceof Number number))throw invalid(path,"必须为数值");
        try {return number instanceof BigDecimal exact?exact:new BigDecimal(number.toString());}
        catch(NumberFormatException invalid) {throw invalid(path,"必须为有限数值");}
    }

    private static void bounded(int size,Map<String,Object> schema,String minimum,String maximum,String path) {
        if(schema.get(minimum) instanceof Number min && size<min.intValue()
                || schema.get(maximum) instanceof Number max && size>max.intValue())throw invalid(path,"长度或数量超出允许范围");
    }

    private static ApiException invalid(String path,String reason) {return new ApiException(path+reason);}
}
