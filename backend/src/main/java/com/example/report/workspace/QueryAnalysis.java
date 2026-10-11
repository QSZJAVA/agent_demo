package com.example.report.workspace;

import com.example.report.assistant.*;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.*;
import java.math.*;
import java.time.*;
import java.util.*;

/** 完整查询快照的确定性指标、期间分析与排名；只使用受控字段和精确十进制，不调用模型计算。 */
public final class QueryAnalysis {
    private QueryAnalysis() { }
    /**
     * 指标口径；金额指标固定使用已声明的 amount 和 currency，不推测业务税额。
     * @param dimensions 零至三个白名单维度，NULL 与文本值分开
     * @param dateField 趋势日期字段；无趋势时为空
     * @param bucket NONE、DAY 或 MONTH
     * @param compareMonth 可选的完整自然月 yyyy-MM，返回环比与同比；仅 MONTH 可用
     */
    public record Request(List<String> dimensions,String dateField,String bucket,String compareMonth) {
        public Request {
            if(dimensions==null || dimensions.size()>3 || new HashSet<>(dimensions).size()!=dimensions.size()
                    || !Set.of("NONE","DAY","MONTH").contains(Objects.toString(bucket,""))
                    || ("NONE".equals(bucket)!=(dateField==null)) || (compareMonth!=null && !"MONTH".equals(bucket)))throw new ApiException(422,"指标参数无效");
            dimensions=List.copyOf(dimensions);
            if(compareMonth!=null)try{YearMonth.parse(compareMonth);}catch(Exception e){throw new ApiException(422,"比较月份必须为yyyy-MM");}
        }
    }
    /**
     * 完整范围有界排名；并列时稳定业务标识升序，空值始终排最后。
     * @param field 目录白名单排序字段
     * @param descending 降序取最大值，升序取最小值
     * @param count 用户明确要求的最终数量，范围1至50，不足时拒绝而非少取
     */
    public record Rank(String field,boolean descending,int count) {
        public Rank {if(field==null || field.isBlank() || count<1 || count>50)throw new ApiException(422,"排名需要明确字段与1至50条的数量");}
    }
    private static final class Accumulator {
        int count,amountCount,missingAmount;BigDecimal sum=BigDecimal.ZERO;
        void add(Map<String,Object> row){count++;if(row.get("amount")==null)missingAmount++;else{sum=sum.add(new BigDecimal(row.get("amount").toString()));amountCount++;}}
        Map<String,Object> values(){var v=new LinkedHashMap<String,Object>();v.put("count",count);v.put("amountCount",amountCount);v.put("missingAmountCount",missingAmount);
            v.put("sum",amountCount==0?null:sum.toPlainString());v.put("average",amountCount==0?null:sum.divide(BigDecimal.valueOf(amountCount),6,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());return v;}
    }
    /** 按维度、币种和日期分组，最多500个分组；超过预算拒绝整份统计，不截断后冒充完整结果。 */
    public static Map<String,Object> analyze(QuerySnapshotStore.Run run,List<Map<String,Object>> rows,Request request) {
        var columns=run.result().columns();for(String name:request.dimensions())field(columns,name);
        if(request.dateField()!=null && !"date".equals(field(columns,request.dateField()).type()))throw new ApiException(422,"趋势必须使用日期字段");
        var values=new TreeMap<String,Accumulator>();var labels=new HashMap<String,Map<String,Object>>();int unclassified=0,missingDate=0;
        for(var row:rows) {
            var dimension=new LinkedHashMap<String,Object>();for(String name:request.dimensions())dimension.put(name,row.get(name));
            String currency=Objects.toString(row.get("currency"),"");if(currency.isBlank() && row.get("amount")!=null)unclassified++;
            String date=request.dateField()==null?"":Objects.toString(row.get(request.dateField()),"");String period=null;
            if(request.dateField()!=null) {if(date.isBlank())missingDate++;else period="MONTH".equals(request.bucket())?YearMonth.from(LocalDate.parse(date)).toString():LocalDate.parse(date).toString();}
            var label=new LinkedHashMap<String,Object>();label.put("dimensions",dimension);label.put("currency",currency.isBlank()?null:currency);label.put("period",period);
            String key=JsonUtil.toJson(label);labels.put(key,label);var acc=values.computeIfAbsent(key,k->new Accumulator());
            // 无币种只保留记录数和缺值计数，不能把未声明币种的金额参与金额合计。
            if(currency.isBlank()){var withoutAmount=new HashMap<>(row);withoutAmount.put("amount",null);acc.add(withoutAmount);}else acc.add(row);
            if(values.size()>500)throw new ApiException(422,"指标分组超过500组，请缩小维度或查询范围");
        }
        var groups=values.entrySet().stream().map(entry->{var item=new LinkedHashMap<>(labels.get(entry.getKey()));item.putAll(entry.getValue().values());return item;}).toList();
        var response=new LinkedHashMap<String,Object>();response.put("snapshot",run.result().snapshot());response.put("definition",request);response.put("groups",groups);
        response.put("count",rows.size());response.put("unclassifiedAmountCount",unclassified);response.put("missingDateCount",missingDate);response.put("averageScale",6);
        if(request.compareMonth()!=null)response.put("comparison",periodComparison(run,rows,request));return response;
    }
    /** 排名基于全集；金额排名禁止跨币种或缺币种，以免产生没有业务意义的大小关系。 */
    public static List<Map<String,Object>> rank(QuerySnapshotStore.Run run,List<Map<String,Object>> rows,Rank rank) {
        var f=field(run.result().columns(),rank.field());
        if(!Set.of("decimal","integer","long","date","string").contains(f.type()))throw new ApiException(422,"该字段不支持候选排名");
        if("amount".equals(f.name())) {
            var currencies=new HashSet<String>();for(var row:rows)if(row.get("amount")!=null)currencies.add(Objects.toString(row.get("currency"),""));
            if(currencies.contains("") || currencies.size()>1)throw new ApiException(422,"金额排名必须明确唯一币种，请先按币种筛选");
        }
        if(rows.stream().filter(row->row.get(f.name())!=null).count()<rank.count())throw new ApiException(422,"非空候选不足指定数量，请明确调整数量");
        var q=run.result().query();var ordering=new BusinessQuery(q.domain(),BusinessQuery.View.LIST,q.reportIds(),q.companyCode(),List.of(),rank.field(),rank.descending(),1,50,null);
        return BusinessQueryEngine.materialize(ordering,run.result().columns(),rows,run.result().source()).rows().subList(0,rank.count());
    }
    private static Map<String,Object> periodComparison(QuerySnapshotStore.Run run,List<Map<String,Object>> rows,Request request) {
        YearMonth current=YearMonth.parse(request.compareMonth()),previous=current.minusMonths(1),year=current.minusYears(1);
        // 只比较已结束且被查询条件完整覆盖的期间；没有记录的完整期间才可解释为零。
        YearMonth observed=YearMonth.from(Instant.parse(run.result().observedAt()).atZone(ZoneId.of("Asia/Shanghai")));
        if(!current.isBefore(observed) || !covers(run.result().query(),request.dateField(),current)
                || !covers(run.result().query(),request.dateField(),previous) || !covers(run.result().query(),request.dateField(),year))
            return Map.of("available",false,"reason","当前、上月或去年同月尚未结束或未被完整查询范围覆盖；请扩大日期范围后比较");
        for(var row:rows) {
            if(row.get(request.dateField())==null)return Map.of("available",false,"reason","存在日期缺失的记录，无法证明期间金额完整");
            var period=YearMonth.from(LocalDate.parse(row.get(request.dateField()).toString()));
            if(Set.of(current,previous,year).contains(period) && (row.get("amount")==null || Objects.toString(row.get("currency"),"").isBlank()))
                return Map.of("available",false,"reason","比较期间存在缺少金额或币种的记录，不能据此计算完整增长率");
        }
        var totals=new TreeMap<String,Map<YearMonth,BigDecimal>>();
        for(var row:rows) {
            if(row.get(request.dateField())==null || row.get("amount")==null || Objects.toString(row.get("currency"),"").isBlank())continue;
            YearMonth month=YearMonth.from(LocalDate.parse(row.get(request.dateField()).toString()));
            if(!Set.of(current,previous,year).contains(month))continue;
            totals.computeIfAbsent(row.get("currency").toString(),k->new HashMap<>()).merge(month,new BigDecimal(row.get("amount").toString()),BigDecimal::add);
        }
        var comparisons=new ArrayList<Map<String,Object>>();totals.forEach((currency,map)->{
            BigDecimal value=map.getOrDefault(current,BigDecimal.ZERO),prior=map.getOrDefault(previous,BigDecimal.ZERO),lastYear=map.getOrDefault(year,BigDecimal.ZERO);
            var item=new LinkedHashMap<String,Object>();item.put("currency",currency);item.put("current",value.toPlainString());item.put("previousMonth",prior.toPlainString());item.put("previousYear",lastYear.toPlainString());
            item.put("monthOverMonthPercent",growth(value,prior));item.put("yearOverYearPercent",growth(value,lastYear));comparisons.add(item);
        });
        return Map.of("available",true,"scope","完整查询范围按币种汇总，不按展示页或维度子集","month",current.toString(),"rows",comparisons,"zeroDenominator","基期为零或负数时增长率为空，金额差额仍可核对");
    }
    private static String growth(BigDecimal value,BigDecimal prior){return prior.signum()<=0?null:value.subtract(prior).multiply(BigDecimal.valueOf(100)).divide(prior,6,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
    /** 每个析取分支的日期约束都完整覆盖期间，才能保证其他字段组合在各期间使用相同口径。 */
    private static boolean covers(BusinessQuery query,String field,YearMonth month) {
        for(var group:query.conditions())for(var filter:group.allOf())if(field.equals(filter.field())) {
            for(int day=1;day<=month.lengthOfMonth();day++) {
                String value=month.atDay(day).toString();var vs=filter.values();
                boolean matches=switch(filter.operator()) {
                    case "IS_NULL"->false;case "NOT_NULL"->true;case "EQ","IN"->vs.contains(value);case "NE","NOT_IN"->!vs.contains(value);
                    case "GT"->value.compareTo(vs.get(0))>0;case "GTE"->value.compareTo(vs.get(0))>=0;case "LT"->value.compareTo(vs.get(0))<0;case "LTE"->value.compareTo(vs.get(0))<=0;default->false;
                };if(!matches)return false;
            }
        }
        return true;
    }
    private static FieldInfo field(List<FieldInfo> columns,String name){return columns.stream().filter(f->f.name().equals(name)).findFirst().orElseThrow(()->new ApiException(422,"指标字段不在当前目录中"));}
}
