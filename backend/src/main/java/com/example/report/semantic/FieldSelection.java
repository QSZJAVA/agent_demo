package com.example.report.semantic;

import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.ApiException;
import com.example.report.rule.Candidate;
import com.example.report.rule.FieldFact;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Predicate;
import static com.example.report.semantic.SemanticIntent.*;

/** 将有界字段条件编译为快照谓词；只使用授权目录白名单，永不构造SQL或执行模型表达式。 */
public final class FieldSelection {
    private FieldSelection() { }
    /** 字段类型决定可用操作；同一能力描述也用于模型上下文与服务端校验。 */
    public static List<String> operators(String type) {
        if(!FieldFact.supported(type)) return List.of();
        List<String> result=new ArrayList<>(List.of("EQ","NE","IN","NOT_IN","IS_NULL","NOT_NULL"));
        if(type.equals("string")) result.addAll(List.of("CONTAINS","STARTS_WITH"));
        else if(!type.equals("boolean")) result.addAll(List.of("GT","GTE","LT","LTE"));
        return List.copyOf(result);
    }
    /** 先校验整组条件再逐行求值；OR组之间取并集，组内AND，无任何选择或数据库副作用。 */
    public static Predicate<Candidate> compile(List<ConditionGroup> groups,List<FieldInfo> fields) {
        Map<String,FieldInfo> allowed=new HashMap<>();fields.forEach(f -> allowed.put(f.name(),f));
        List<List<Predicate<Candidate>>> disjunction=new ArrayList<>();
        for(var group:groups) {
            List<Predicate<Candidate>> conjunction=new ArrayList<>();
            for(var c:group.allOf()) {
                var field=allowed.get(c.field());
                if(field==null || !operators(field.type()).contains(c.operator().name()))
                    throw new ApiException(422,"字段或操作未配置："+c.field()+" / "+c.operator());
                c.values().forEach(v -> FieldFact.scalar(field.type(),v));
                conjunction.add(row -> test(row,field,c));
            }
            disjunction.add(conjunction);
        }
        return row -> disjunction.stream().anyMatch(group -> group.stream().allMatch(test -> test.test(row)));
    }
    private static boolean test(Candidate row,FieldInfo field,FieldCondition condition) {
        var values=row.fields().stream().filter(f -> f.name().equals(field.name())).toList();
        if(values.size()!=1 || !values.get(0).type().equals(field.type())) throw new ApiException(422,"字段快照不完整或目录类型已变化，请重新查询");
        String value=values.get(0).value();
        if(condition.operator()==Comparison.IS_NULL) return value==null;
        if(condition.operator()==Comparison.NOT_NULL) return value!=null;
        // 空值不满足普通比较，包括NE/NOT_IN；用户需要明确IS_NULL，避免未知值被当成满足条件。
        if(value==null) return false;
        if(condition.operator()==Comparison.IN || condition.operator()==Comparison.NOT_IN) {
            boolean contains=condition.values().stream().anyMatch(v -> compare(field.type(),value,v)==0);
            return condition.operator()==Comparison.IN?contains:!contains;
        }
        String right=condition.values().get(0);
        if(condition.operator()==Comparison.CONTAINS) return value.contains(right);
        if(condition.operator()==Comparison.STARTS_WITH) return value.startsWith(right);
        int comparison=compare(field.type(),value,right);
        return switch(condition.operator()) {case EQ -> comparison==0;case NE -> comparison!=0;case GT -> comparison>0;
            case GTE -> comparison>=0;case LT -> comparison<0;case LTE -> comparison<=0;default -> throw new IllegalArgumentException();};
    }
    private static int compare(String type,String left,String right) {
        return switch(type) {
            case "decimal","integer","long" -> new BigDecimal(left).compareTo(new BigDecimal(right));
            case "date" -> java.time.LocalDate.parse(left).compareTo(java.time.LocalDate.parse(right));
            case "boolean" -> Boolean.compare(Boolean.parseBoolean(left),Boolean.parseBoolean(right));
            default -> left.compareTo(right);
        };
    }
}
