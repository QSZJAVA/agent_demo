package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 明确标量比较表达的证据校验器；只对同一阈值且无歧义的数学关系检查等号与方向，不解释动作、字段或目标。
 * 不能生成或修改计划；未识别、跨单位或多义表达仍由真实模型复核，冲突返回422而非自动替换操作符。
 */
final class NumericBoundaryEvidence {
    private NumericBoundaryEvidence() { }
    private static final String NUMBER="(?:[+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?(?:亿|万|千|百)?|[负正]?[零〇一二两三四五六七八九十百千万亿]+(?:点[零〇一二两三四五六七八九0-9]+)?)";
    private static final String UNIT="(?:元|块钱|块|天|日|笔|条|个)?";
    private static final Map<String,String> PREFIX=relations();
    private static final Map<String,String> SUFFIX=Map.of("及以上","GTE","以上","GTE","及以下","LTE","以下","LTE","or more","GTE","or less","LTE");
    private static final Pattern BEFORE=Pattern.compile("(?<![A-Za-z])("+alternatives(PREFIX)+")\\s*("+NUMBER+")",Pattern.CASE_INSENSITIVE);
    private static final Pattern AFTER=Pattern.compile("("+NUMBER+")\\s*"+UNIT+"\\s*("+alternatives(SUFFIX)+")",Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_EXCLUSION=Pattern.compile("不(?:包含|包括|含)(?:本数|等于|等号|边界|[0-9零一二三四五六七八九])");
    private static final Pattern EXPLICIT_INCLUSION=Pattern.compile("(?:包含|包括|含)(?:本数|等于|等号|边界)");
    private static final Pattern LOWER_LABEL=Pattern.compile("下限|下界|lower\\s+(?:bound|limit)",Pattern.CASE_INSENSITIVE);
    private static final Pattern UPPER_LABEL=Pattern.compile("上限|上界|upper\\s+(?:bound|limit)",Pattern.CASE_INSENSITIVE);
    private static final Pattern BOTH_LABELS=Pattern.compile("上下限|上下界|both\\s+(?:bounds|limits)",Pattern.CASE_INSENSITIVE);

    /**
     * 从真实旧比较符确定端点方向，仅在本轮依据明确命名另一端点时拒绝冲突，不据文字生成筛选或授权撤销。
     * @param oldOperator 已生效且被模型声明替换/移除的旧比较符
     * @param evidence 本轮授权改变旧条件的连续原文；未明确端点的表达交独立语义复核理解
     */
    static void validateChangeEvidence(String oldOperator,String evidence) {
        boolean lower=Set.of("GT","GTE").contains(oldOperator),upper=Set.of("LT","LTE").contains(oldOperator);
        if(!lower && !upper)return;
        boolean both=BOTH_LABELS.matcher(evidence).find();
        boolean saysLower=both || LOWER_LABEL.matcher(evidence).find(),saysUpper=both || UPPER_LABEL.matcher(evidence).find();
        if(saysLower==saysUpper)return;
        if((saysLower && !lower) || (saysUpper && !upper))
            throw new ApiException(422,"旧条件变更依据仅明确了"+(saysLower?"下限":"上限")
                    +"，不能据此删除另一端点；保留未被授权改变的旧条件。若另有整体撤销要求，须分别引用对应原话，不能扩大当前端点的作用域");
    }

    /** 已声明条件只在原文存在相同阈值的唯一关系时接受确定性校验，不从用户文字构造新字段或调用业务。 */
    static void validate(String operator,JsonNode values,String evidence,String negationEvidence) {
        if(!Set.of("GT","GTE","LT","LTE").contains(operator) || values.size()!=1)return;
        if(!negationEvidence.isEmpty() && EXPLICIT_EXCLUSION.matcher(negationEvidence).find())
            throw new ApiException(422,"不含本数或不含等于阈值属于端点限定，不是对整个比较取反；negationEvidence必须为空，保留原文中不含等号的正确条件");
        BigDecimal threshold=decimal(values.get(0).asText());if(threshold==null)return;
        var all=boundaries(evidence);var matching=all.stream().filter(bound->bound.value().compareTo(threshold)==0).toList();
        if(matching.isEmpty())return;
        var meanings=new HashSet<String>();
        for(var bound:matching) {
            String relation=bound.operator();
            // “以下但不含本数”等显式等号修正仅在证据内只有同一个阈值时适用；多端点由模型分别引用。
            if(all.stream().allMatch(other->other.value().compareTo(threshold)==0)) {
                if(EXPLICIT_EXCLUSION.matcher(evidence).find())relation=exclusive(relation);
                else if(EXPLICIT_INCLUSION.matcher(evidence).find())relation=inclusive(relation);
            }
            if(!negationEvidence.isEmpty()) {
                boolean outside=false;
                for(int start=evidence.indexOf(negationEvidence);start>=0;start=evidence.indexOf(negationEvidence,start+1))
                    if(start+negationEvidence.length()<=bound.start() || start>=bound.end()){outside=true;break;}
                if(!outside)throw new ApiException(422,"negationEvidence只能引用比较表达之外的独立否定短语，不能引用整句或不少于、不超过内部；没有整条谓词取反时该字段必须为空");
                relation=switch(relation){case "GT"->"LTE";case "GTE"->"LT";case "LT"->"GTE";case "LTE"->"GT";default->relation;};
            }
            meanings.add(relation);
        }
        if(meanings.size()==1 && !meanings.contains(operator))
            throw new ApiException(422,"明确数值比较证据要求 "+meanings.iterator().next()+"，但复核条件使用 "+operator+"；须按原文核对方向和恰好等于阈值，不得用错误复核要求规划器改坏正确条件");
    }

    private static String exclusive(String relation){return switch(relation){case "GTE"->"GT";case "LTE"->"LT";default->relation;};}
    private static String inclusive(String relation){return switch(relation){case "GT"->"GTE";case "LT"->"LTE";default->relation;};}
    private static List<Boundary> boundaries(String evidence) {
        var result=new ArrayList<Boundary>();var before=BEFORE.matcher(evidence);var after=AFTER.matcher(evidence);
        while(before.find()) {
            var number=decimal(before.group(2));
            if(number!=null)result.add(new Boundary(number,PREFIX.get(before.group(1).toLowerCase(Locale.ROOT)),before.start(),before.end()));
        }
        while(after.find()) {
            var number=decimal(after.group(1));
            if(number!=null)result.add(new Boundary(number,SUFFIX.get(after.group(2).toLowerCase(Locale.ROOT)),after.start(),after.end()));
        }
        return result;
    }
    private static Map<String,String> relations() {
        var map=new LinkedHashMap<String,String>();
        for(String term:List.of("大于等于","不少于","不低于","不小于","至少","at least","no less than",">=","≥"))map.put(term,"GTE");
        for(String term:List.of("小于等于","不多于","不高于","不大于","不超过","至多","最多","at most","no more than","<=","≤"))map.put(term,"LTE");
        for(String term:List.of("大于","超过","高于","多于","more than","greater than",">"))map.put(term,"GT");
        for(String term:List.of("小于","少于","低于","不到","不足","less than","<"))map.put(term,"LT");
        return Map.copyOf(map);
    }
    private static String alternatives(Map<String,String> terms){return terms.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo)).map(Pattern::quote).collect(Collectors.joining("|"));}

    /** 只读解析十进制、常用中文整数/小数和万/亿数量级，用于比对已存在的阈值，不用于猜测字段单位。 */
    static BigDecimal decimal(String value) {
        try {
            String token=value.replace(",","");boolean negative=token.startsWith("负") || token.startsWith("-");
            if(token.startsWith("负") || token.startsWith("正") || token.startsWith("-") || token.startsWith("+"))token=token.substring(1);
            var arabic=Pattern.compile("([0-9]+(?:\\.[0-9]+)?)([百千万亿]?)").matcher(token);
            BigDecimal result;
            if(arabic.matches())result=new BigDecimal(arabic.group(1)).multiply(arabic.group(2).isEmpty()?BigDecimal.ONE:unit(arabic.group(2).charAt(0)));
            else {
                var parts=token.split("点",-1);if(parts.length>2 || parts[0].isEmpty())return null;
                BigDecimal total=BigDecimal.ZERO,section=BigDecimal.ZERO,number=BigDecimal.ZERO;
                for(char c:parts[0].toCharArray()) {
                    int digit=digit(c);
                    if(digit>=0){number=number.multiply(BigDecimal.TEN).add(BigDecimal.valueOf(digit));continue;}
                    var multiplier=unit(c);if(multiplier==null)return null;
                    if(multiplier.compareTo(BigDecimal.valueOf(10000))<0) {
                        section=section.add((number.signum()==0?BigDecimal.ONE:number).multiply(multiplier));number=BigDecimal.ZERO;
                    } else if(c=='万') {
                        total=total.add(section.add(number).multiply(multiplier));section=BigDecimal.ZERO;number=BigDecimal.ZERO;
                    } else {
                        total=total.add(section).add(number).multiply(multiplier);section=BigDecimal.ZERO;number=BigDecimal.ZERO;
                    }
                }
                result=total.add(section).add(number);
                if(parts.length==2) {
                    if(parts[1].isEmpty())return null;var fraction=new StringBuilder("0.");
                    for(char c:parts[1].toCharArray()){int digit=digit(c);if(digit<0)return null;fraction.append(digit);}
                    result=result.add(new BigDecimal(fraction.toString()));
                }
            }
            return negative?result.negate():result;
        } catch(NumberFormatException failure){return null;}
    }
    private static int digit(char c) {
        if(c>='0' && c<='9')return c-'0';
        return switch(c){case '零','〇'->0;case '一'->1;case '二','两'->2;case '三'->3;case '四'->4;case '五'->5;case '六'->6;case '七'->7;case '八'->8;case '九'->9;default->-1;};
    }
    private static BigDecimal unit(char c){return switch(c){case '十'->BigDecimal.TEN;case '百'->BigDecimal.valueOf(100);case '千'->BigDecimal.valueOf(1000);case '万'->BigDecimal.valueOf(10000);case '亿'->BigDecimal.valueOf(100000000);default->null;};}
    /** 证据内已明确写出的标量关系及其位置；位置用于排除比较词内部的重复否定。 */
    private record Boundary(BigDecimal value,String operator,int start,int end) { }
}
