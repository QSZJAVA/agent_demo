package com.example.report.analysis;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** 对已授权且有效的知识片段执行有界文本召回；排序只提供依据候选，不能生成业务意图或绕过模型复核。 */
public final class KnowledgeSearch {
    private KnowledgeSearch() { }
    /**
     * 召回范围的真实说明；截断和无命中必须传递给回答器。
     * @param sources 按相关度和稳定编号排列的实际片段
     * @param matched 匹配片段总数，不是返回条数
     * @param searched 本次在当前授权范围内搜索的总片段数
     * @param truncated 匹配数超过返回预算，不代表完整知识覆盖
     */
    public record Result(List<AnalysisEvidence.Source> sources,int matched,int searched,boolean truncated) { }

    /** 英文完整词与中文双字/三字片段加权，长片段优先；不能把无命中改成任意目录默认答案。 */
    public static Result search(String question,List<AnalysisEvidence.Source> corpus,int limit) {
        if(question==null||question.isBlank()||limit<1||limit>12)AnalysisContract.fail("知识检索参数无效");
        var terms=terms(question);if(terms.size()>256)AnalysisContract.fail("知识检索问题过长，请聚焦需要查证的事项");
        var bodies=corpus.stream().map(s->normalize(s.text())).toList();var titles=corpus.stream().map(s->normalize(s.title())).toList();
        var weights=new HashMap<String,Double>();
        for(String term:terms){int documents=0;for(int i=0;i<corpus.size();i++)if(bodies.get(i).contains(term)||titles.get(i).contains(term))documents++;
            weights.put(term,Math.log(1.0+(corpus.size()+1.0)/(documents+1.0))*term.length());}
        var scored=new ArrayList<Map.Entry<AnalysisEvidence.Source,Double>>();
        for(int index=0;index<corpus.size();index++) {
            var source=corpus.get(index);String body=bodies.get(index),title=titles.get(index);double score=0;
            for(String term:terms) {
                double weight=weights.get(term);
                if(body.contains(term))score+=weight;if(title.contains(term))score+=weight*2;
            }
            if(score>0)scored.add(Map.entry(source,score));
        }
        scored.sort(Comparator.<Map.Entry<AnalysisEvidence.Source,Double>,Double>comparing(Map.Entry::getValue).reversed().thenComparing(e->e.getKey().id()));
        return new Result(scored.stream().limit(limit).map(Map.Entry::getKey).toList(),scored.size(),corpus.size(),scored.size()>limit);
    }
    private static Set<String> terms(String question) {
        var result=new LinkedHashSet<String>();var matcher=Pattern.compile("[a-z0-9_]+|[\\p{IsHan}]+").matcher(normalize(question));
        var noise=Set.of("什么","怎么","如何","是否","一下","哪些","可以","以及","请问","这个","根据","进行","对于","我们","当前","相关");
        while(matcher.find()) {
            String word=matcher.group();if(word.matches("[a-z0-9_]+")){if(word.length()>1)result.add(word);continue;}
            for(int length=2;length<=3;length++)for(int i=0;i+length<=word.length();i++){String term=word.substring(i,i+length);if(!noise.contains(term))result.add(term);}
        }
        return result;
    }
    private static String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
}
