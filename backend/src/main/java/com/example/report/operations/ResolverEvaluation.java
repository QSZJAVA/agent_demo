package com.example.report.operations;

import com.example.report.catalog.*;
import com.example.report.common.JsonUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * 以目录及别名版本指纹记录报表名称解析评估；用于维护名称匹配质量，结果不代表真实模型或外部 ERP 验收。
 */
@Service
public class ResolverEvaluation {
    /**
     * 用于名称解析回归的合成评估样本。
     * @param query 用户对报表的说法或本次解析输入
     * @param expected 评估样本预期的名称解析结论
     * @param reports 当前用户可见的报表引用集合
     * @param visible 评估模拟的可见报表标识集合，仅用于解析测试
     */
    public record Sample(String query,String expected,List<String> reports,List<String> visible) { }
    /**
     * 名称解析评估逐例结果。
     * @param query 用户对报表的说法或本次解析输入
     * @param expected 评估样本预期的名称解析结论
     * @param actual 评估实际名称解析结论
     * @param expectedReports 预期识别的报表标识集合
     * @param actualReports 实际识别的报表标识集合
     * @param passed 是否符合预期，或评估通过样本数，依本类型字段类型确定
     */
    public record CaseResult(String query,String expected,String actual,List<String> expectedReports,List<String> actualReports,boolean passed) { }
    /**
     * 名称解析评估汇总，不代表模型或外部业务验收。
     * @param total 授权范围内统计总数，不能用当前页长度代替
     * @param passed 符合预期的评估样本数
     * @param cases 评估逐例结果集合
     */
    public record Evaluation(int total,int passed,List<CaseResult> cases) { }
    public List<Sample> samples() {
        try(var in=new ClassPathResource("evaluation/resolver-cases.json").getInputStream()) {
            return Arrays.asList(JsonUtil.MAPPER.readValue(in,Sample[].class));
        } catch(Exception failure) { throw new IllegalStateException("解析评估集加载失败",failure); }
    }
    public Evaluation evaluate(List<CatalogEntry> entries,double threshold,double margin) {
        return evaluate(entries,threshold,margin,samples());
    }
    public Evaluation evaluate(List<CatalogEntry> entries,double threshold,double margin,List<Sample> samples) {
        var resolver=new ReportResolver(threshold,margin);
        var index=TermIndex.build(entries.stream().map(e->new TermIndex.ReportTerms(e.reportId(),e.reportName(),e.reportCode(),
                e.activeAliases().stream().map(a->new TermIndex.AliasTerm(a.alias(),a.priority())).toList())).toList());
        var cases=new ArrayList<CaseResult>();
        for(Sample sample:samples) {
            var visible=entries.stream().filter(e->sample.visible()==null||sample.visible().contains(e.reportId())).map(CatalogEntry::ref).toList();
            var result=resolver.resolve(sample.query(),index,visible);
            var ids=(result.matchType()==MatchType.AMBIGUOUS?result.candidates():result.reports()).stream().map(ReportRef::reportId).toList();
            boolean passed=sample.expected().equals(result.matchType().name()) && new HashSet<>(sample.reports()).equals(new HashSet<>(ids));
            cases.add(new CaseResult(sample.query(),sample.expected(),result.matchType().name(),sample.reports(),ids,passed));
        }
        return new Evaluation(cases.size(),(int)cases.stream().filter(CaseResult::passed).count(),cases);
    }
}
