package com.example.report.analysis;

import com.example.report.assistant.SemanticDictionary;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** 在已授权的有效知识内进行有界、可解释召回；别名只来自同范围词典，排序不生成意图或证明答案正确。 */
public final class KnowledgeSearch {
    private static final int MAX_DIRECT_TERMS=256, MAX_EXPANDED_TERMS=64, MAX_FRAGMENTS=1600;
    private static final Pattern WORDS=Pattern.compile("(?:(?!\\p{IsHan})[\\p{L}\\p{N}_])+|[\\p{IsHan}]+");
    private static final Set<String> NOISE=Set.of("什么","怎么","如何","是否","一下","哪些","可以","以及","请问","这个","根据","进行","对于","我们","当前","相关",
            "what","which","how","is","are","the","of","to","and","or","please");
    private KnowledgeSearch() { }

    /**
     * 按原始来源而非片段数度量本轮覆盖，只描述当前授权且生效的有界语料。
     * @param searchedSources 参与搜索的独立原始来源数
     * @param matchedSources 至少一个片段命中的独立来源数
     * @param returnedSources 返回片段所属的独立来源数，不能等同全部已命中来源
     */
    public record Coverage(int searchedSources,int matchedSources,int returnedSources) { }
    /**
     * 一项明确配置的字段别名扩展，不把业务定义正文或其他字段当成同义词表。
     * @param field 当前目录内的真实字段标识
     * @param triggers 在原始问题中完整出现的字段名或已配置别名，已经规范化
     * @param addedTerms 本次实际加入的同字段名称，受全局64个扩展词预算约束
     */
    public record Expansion(String field,List<String> triggers,List<String> addedTerms) { }
    /**
     * 一个实际返回片段的命中解释；分数不作为置信度公开，也不代表事实已经复核。
     * @param sourceId 返回片段的实际引用编号
     * @param directTerms 在标题或正文中命中的原问题词项
     * @param aliasTerms 在标题或正文中命中的已配置字段扩展词项
     */
    public record Hit(String sourceId,List<String> directTerms,List<String> aliasTerms) { }
    /**
     * 分析和管理员试查共用的只读结果，明确两种截断及真实来源覆盖。
     * @param sources 按来源均衡与相关度排列、可完整引用的实际片段
     * @param matched 命中的片段总数，不是返回数量
     * @param searched 参与搜索的当前授权片段总数
     * @param truncated 是否仍有命中片段因返回预算未被选中
     * @param coverage 独立原始来源的搜索、命中与返回数量
     * @param queryTerms 本次从原问题提取的直接检索词，不包含自动猜测的指标
     * @param expansions 实际采用的同范围词典扩展及触发依据
     * @param expansionTruncated 是否有合法扩展词因64词预算未加入
     * @param hits 返回片段对应的命中说明，与sources顺序一致
     */
    public record Result(List<AnalysisEvidence.Source> sources,int matched,int searched,boolean truncated,Coverage coverage,
                         List<String> queryTerms,List<Expansion> expansions,boolean expansionTruncated,List<Hit> hits) { }

    private record Query(Set<String> direct,Set<String> expanded,List<Expansion> explanations,boolean truncated) { }
    private record Fragment(AnalysisEvidence.Source source,String title,String body) { }
    private record Scored(AnalysisEvidence.Source source,double score,Hit hit) { }

    /**
     * 在调用方已经校验的单一范围内召回，不查询数据库、不修改词典，不调用模型。
     * @param question 本轮完整问题，最多2000字符、256个直接词项
     * @param corpus 已授权且有效的知识引用片段，最多1600个、每片最多2000字符
     * @param dictionary 同范围已验证的词典定义；未配置时显式传入空定义
     * @param limit 返回片段预算，1至12；先覆盖不同命中来源，再逐轮补充同来源片段
     * @return 带真实命中解释、来源覆盖和截断标记的候选，不保证语义蕴含或知识完整性
     */
    public static Result search(String question,List<AnalysisEvidence.Source> corpus,SemanticDictionary.Definition dictionary,int limit) {
        AnalysisContract.text(question,2000,"知识检索问题");
        if(corpus==null||dictionary==null||corpus.size()>MAX_FRAGMENTS||limit<1||limit>12)AnalysisContract.fail("知识检索参数或范围预算无效");
        var ids=new HashSet<String>();var origins=new HashSet<String>();var fragments=new ArrayList<Fragment>();
        for(var source:corpus) {
            if(source==null||!"KNOWLEDGE".equals(source.kind())||source.text().length()>2000||!ids.add(source.id()))AnalysisContract.fail("知识片段类型、编号或引用大小无效");
            origins.add(source.originId());fragments.add(new Fragment(source,normalize(source.title()),normalize(source.text())));
        }
        var query=query(question,dictionary);var allTerms=new LinkedHashSet<>(query.direct());allTerms.addAll(query.expanded());
        var weights=new HashMap<String,Double>();
        // 词项频率按独立原始来源计数，不能让同一长文档的重复切片改变其稀有程度。
        for(String term:allTerms) {
            var matches=new HashSet<String>();for(var fragment:fragments)if(matches(fragment.title(),term)||matches(fragment.body(),term))matches.add(fragment.source().originId());
            weights.put(term,Math.log(1.0+(origins.size()+1.0)/(matches.size()+1.0))*term.codePointCount(0,term.length()));
        }
        var scored=new ArrayList<Scored>();
        for(var fragment:fragments) {
            double score=0;var direct=new ArrayList<String>();var aliases=new ArrayList<String>();
            for(String term:allTerms) {
                boolean title=matches(fragment.title(),term),body=matches(fragment.body(),term);if(!title&&!body)continue;
                boolean original=query.direct().contains(term);(original?direct:aliases).add(term);
                score+=weights.get(term)*(original?1.0:0.65)*((title?2:0)+(body?1:0));
            }
            if(score>0)scored.add(new Scored(fragment.source(),score,new Hit(fragment.source().id(),List.copyOf(direct),List.copyOf(aliases))));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed().thenComparing(s->s.source().id()));
        var byOrigin=new LinkedHashMap<String,List<Scored>>();
        for(var item:scored)byOrigin.computeIfAbsent(item.source().originId(),ignored->new ArrayList<>()).add(item);
        var selected=new ArrayList<Scored>();
        // 第一轮每个来源最多一片，后续仍按来源相关度逐轮补充；没有命中的来源绝不为凑数加入。
        for(int round=0;selected.size()<Math.min(limit,scored.size());round++)for(var origin:byOrigin.values()) {
            if(round<origin.size())selected.add(origin.get(round));if(selected.size()==limit)break;
        }
        var returned=selected.stream().map(s->s.source().originId()).distinct().count();
        return new Result(selected.stream().map(Scored::source).toList(),scored.size(),corpus.size(),scored.size()>limit,
                new Coverage(origins.size(),byOrigin.size(),(int)returned),List.copyOf(query.direct()),query.explanations(),query.truncated(),selected.stream().map(Scored::hit).toList());
    }

    /** 扩展仅由原问题中的完整配置名称触发；不递归触发，不使用meaning、公司别名、单位或枚举推断同义关系。 */
    private static Query query(String question,SemanticDictionary.Definition dictionary) {
        String normalized=normalize(question);var direct=terms(normalized);
        if(direct.size()>MAX_DIRECT_TERMS)AnalysisContract.fail("知识检索问题过长，请聚焦需要查证的事项");
        var expanded=new LinkedHashSet<String>();var explanations=new ArrayList<Expansion>();boolean truncated=false;
        for(var field:dictionary.fields().stream().sorted(Comparator.comparing(SemanticDictionary.Field::field)).toList()) {
            var names=new TreeSet<String>();names.add(normalize(field.field()));field.aliases().forEach(alias->names.add(normalize(alias)));
            var triggers=names.stream().filter(name->matches(normalized,name)).toList();if(triggers.isEmpty())continue;
            direct.addAll(triggers);if(direct.size()>MAX_DIRECT_TERMS)AnalysisContract.fail("知识检索问题过长，请聚焦需要查证的事项");
            var added=new ArrayList<String>();
            for(String name:names) {
                if(direct.contains(name)||expanded.contains(name))continue;
                if(expanded.size()==MAX_EXPANDED_TERMS){truncated=true;continue;}
                expanded.add(name);added.add(name);
            }
            if(!added.isEmpty())explanations.add(new Expansion(field.field(),triggers,List.copyOf(added)));
        }
        return new Query(Collections.unmodifiableSet(direct),Collections.unmodifiableSet(expanded),List.copyOf(explanations),truncated);
    }

    /** 中文采用二/三字召回，英文和标识符保留完整词项；通用问句噪声不会成为默认候选。 */
    private static LinkedHashSet<String> terms(String question) {
        var result=new LinkedHashSet<String>();var matcher=WORDS.matcher(question);
        while(matcher.find()) {
            String word=matcher.group();if(Character.UnicodeScript.of(word.codePointAt(0))!=Character.UnicodeScript.HAN){if(word.codePointCount(0,word.length())>1&&!NOISE.contains(word))result.add(word);continue;}
            int[] codepoints=word.codePoints().toArray();
            for(int length=2;length<=3;length++)for(int index=0;index+length<=codepoints.length;index++) {
                String term=new String(codepoints,index,length);if(!NOISE.contains(term))result.add(term);
            }
        }
        return result;
    }

    /** 非汉字单词两端不能连接另一个标识符字符；中英混排可自然分界，重音字母也不能被切成英文子串。 */
    private static boolean matches(String text,String term) {
        if(term.isEmpty())return false;
        for(int start=text.indexOf(term);start>=0;start=text.indexOf(term,start+1)) {
            int end=start+term.length();
            boolean left=!wordCharacter(term.codePointAt(0))||start==0||!wordCharacter(text.codePointBefore(start));
            boolean right=!wordCharacter(term.codePointBefore(term.length()))||end==text.length()||!wordCharacter(text.codePointAt(end));
            if(left&&right)return true;
        }
        return false;
    }
    private static boolean wordCharacter(int value){return value=='_'||Character.isLetterOrDigit(value)&&Character.UnicodeScript.of(value)!=Character.UnicodeScript.HAN;}
    private static String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+"," ").trim();}
}
