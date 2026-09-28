package com.example.report.operations;

import com.example.report.catalog.*;
import com.example.report.common.JsonUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ResolverEvaluation {
    public record Sample(String query,String expected,List<String> reports,List<String> visible) { }
    public record CaseResult(String query,String expected,String actual,List<String> expectedReports,List<String> actualReports,boolean passed) { }
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
