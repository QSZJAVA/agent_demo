package com.example.report.analysis;

import com.example.report.common.*;
import com.example.report.investigation.InvestigationJson;
import com.example.report.workspace.QueryAnalysis;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 分组排名与同源明细展开的纯计算；不读取新的来源、不把截断展示当作全集，也不由模型计算数值。 */
public final class AnalysisMath {
    private AnalysisMath() { }
    /** 完整分组按明确指标排名；金额比较必须有唯一已声明币种，并列以完整分组键稳定排序。 */
    public static List<Map<String,Object>> rankGroups(List<Map<String,Object>> groups,AnalysisContract.Rank rank) {
        if(!Set.of("sum","average","count").contains(rank.field()))AnalysisContract.fail("分组排名指标无效");
        var candidates=groups.stream().filter(g->g.get(rank.field())!=null).toList();
        if(candidates.size()<rank.count())AnalysisContract.fail("非空分组不足指定排名数量，请明确调整数量");
        if(!"count".equals(rank.field())){var currencies=new HashSet<String>();candidates.forEach(g->currencies.add(Objects.toString(g.get("currency"),"")));if(currencies.contains("")||currencies.size()>1)AnalysisContract.fail("分组金额排名必须明确唯一币种");}
        Comparator<Map<String,Object>> value=Comparator.comparing(g->new BigDecimal(g.get(rank.field()).toString()));if(rank.descending())value=value.reversed();
        return candidates.stream().sorted(value.thenComparing(AnalysisMath::key)).limit(rank.count()).toList();
    }
    private static String key(Map<String,Object> group){var key=new LinkedHashMap<String,Object>();key.put("dimensions",group.get("dimensions"));key.put("currency",group.get("currency"));key.put("period",group.get("period"));return InvestigationJson.canonical(key);}
    /** 仅从同一输入集合中展开选定分组；维度类型、NULL、币种和期间均精确匹配，不跨查询拼接事实。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> drill(List<Map<String,Object>> rows,QueryAnalysis.Request definition,List<Map<String,Object>> selected) {
        if(selected.isEmpty()||selected.size()>50)AnalysisContract.fail("展开分组数量无效");
        return rows.stream().filter(row->selected.stream().anyMatch(group->{
            Object raw=group.get("dimensions");if(!(raw instanceof Map<?,?> dimensions))AnalysisContract.fail("展开维度证据损坏");
            var dimensions=(Map<String,Object>)raw;
            if(!dimensions.keySet().equals(new LinkedHashSet<>(definition.dimensions())))AnalysisContract.fail("展开维度与原统计不一致");
            for(String field:definition.dimensions())if(!InvestigationJson.canonical(row.get(field)).equals(InvestigationJson.canonical(dimensions.get(field))))return false;
            String currency=Objects.toString(row.get("currency"),"");if(!Objects.equals(currency.isBlank()?null:currency,group.get("currency")))return false;
            String period=null;
            if(definition.dateField()!=null&&row.get(definition.dateField())!=null){var day=LocalDate.parse(row.get(definition.dateField()).toString());period="MONTH".equals(definition.bucket())?YearMonth.from(day).toString():day.toString();}
            return Objects.equals(period,group.get("period"));
        })).toList();
    }
}
