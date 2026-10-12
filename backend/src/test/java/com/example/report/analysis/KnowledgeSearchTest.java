package com.example.report.analysis;

import com.example.report.assistant.SemanticDictionary;
import com.example.report.common.ApiException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 知识召回的独立行为回归；验证词界、来源覆盖和词典边界，不把排序当成答案真实性证明。 */
class KnowledgeSearchTest {
    static AnalysisEvidence.Source source(String id,String origin,String title,String text) {
        return new AnalysisEvidence.Source(id,origin,"KNOWLEDGE",title,"1","A / rpt-sales-order","当前有效",text,0);
    }
    static SemanticDictionary.Field field(String name,List<String> aliases,String meaning) {
        return new SemanticDictionary.Field(name,aliases,meaning,"","",List.of(),List.of());
    }
    static SemanticDictionary.Definition dictionary(SemanticDictionary.Field... fields) {
        return new SemanticDictionary.Definition("",List.of(),"Asia/Shanghai",1,List.of(),List.of(fields));
    }

    @Test void englishWordDoesNotMatchInsideUnrelatedIdentifiers() {
        var corpus=List.of(source("syntax","syntax","解析说明","syntax parser"),source("tax","tax","Tax policy","tax is documented"));
        var result=KnowledgeSearch.search("tax",corpus,SemanticDictionary.Definition.empty(),12);
        assertEquals(List.of("tax"),result.sources().stream().map(AnalysisEvidence.Source::id).toList());
    }

    @Test void oneLongDocumentCannotCrowdOutAnotherMatchingSource() {
        var corpus=new ArrayList<AnalysisEvidence.Source>();
        for(int i=0;i<18;i++)corpus.add(source("a-"+String.format("%02d",i),"long-document","Tax policy","tax applies"));
        corpus.add(source("z-conflict","other-document","Tax policy","tax does not apply"));
        var result=KnowledgeSearch.search("tax",corpus,SemanticDictionary.Definition.empty(),12);
        assertTrue(result.sources().stream().anyMatch(s->s.id().equals("z-conflict")),"另一份真实来源不能被长文档的重复片段排除");
        assertEquals("z-conflict",result.sources().get(1).id());assertEquals(new KnowledgeSearch.Coverage(2,2,2),result.coverage());
        assertEquals(19,result.matched());assertEquals(12,result.sources().size());assertTrue(result.truncated());
    }

    @Test void fullWidthCaseAndPunctuationPreserveAnActualWholeWord() {
        var corpus=List.of(source("tax","tax","ＴＡＸ policy","（tax）"));
        assertEquals(1,KnowledgeSearch.search("Ｔａｘ",corpus,SemanticDictionary.Definition.empty(),12).matched());
    }

    @Test void identifierBoundariesRejectPrefixesSuffixesDigitsUnderscoresAndAccentLetters() {
        var corpus=new ArrayList<AnalysisEvidence.Source>();
        for(String value:List.of("pretax","taxable","tax2","my_tax","tax_id","taxé","étax"))corpus.add(source(value,value,"说明",value));
        corpus.add(source("whole","whole","说明","税额tax，按规定核对"));
        var result=KnowledgeSearch.search("tax",corpus,SemanticDictionary.Definition.empty(),12);
        assertEquals(List.of("whole"),result.sources().stream().map(AnalysisEvidence.Source::id).toList());
        assertTrue(KnowledgeSearch.search("taxé",List.of(source("tax","tax","税额","tax")),SemanticDictionary.Definition.empty(),12).sources().isEmpty());
    }

    @Test void underscoreIdentifierRemainsOneWholeTerm() {
        var result=KnowledgeSearch.search("order_no",List.of(source("real","real","字段说明","order_no 是单号"),source("other","other","字段说明","preorder_no order_no2")),SemanticDictionary.Definition.empty(),12);
        assertEquals(List.of("real"),result.sources().stream().map(AnalysisEvidence.Source::id).toList());
    }

    @Test void chineseRecallStillFindsBusinessPhrasesAndRetainsOriginalText() {
        String original="销售金额保持原始口径，原文ＴＡＸ不改写。";
        var result=KnowledgeSearch.search("销售金额指什么",List.of(source("sales","sales","销售定义",original),source("hotel","hotel","出差住宿","酒店报销")),SemanticDictionary.Definition.empty(),12);
        assertEquals(1,result.matched());assertEquals(original,result.sources().get(0).text());assertFalse(result.hits().get(0).directTerms().isEmpty());
    }

    @Test void configuredAliasFindsTheActualFieldAndShowsItsTrigger() {
        var result=KnowledgeSearch.search("booked value",List.of(source("amount","amount","字段说明","amount 是订单金额")),dictionary(field("amount",List.of("booked value","销售金额"),"")),12);
        assertEquals(1,result.matched());assertEquals("amount",result.expansions().get(0).field());
        assertEquals(List.of("booked value"),result.expansions().get(0).triggers());assertTrue(result.hits().get(0).aliasTerms().contains("amount"));
    }

    @Test void configuredMultiwordAliasNormalizesWhitespaceAndCase() {
        var result=KnowledgeSearch.search("ＢＯＯＫＥＤ\u00a0  VALUE",List.of(source("amount","amount","说明","amount")),dictionary(field("amount",List.of("booked value"),"")),12);
        assertEquals(1,result.matched());assertEquals(List.of("booked value"),result.expansions().get(0).triggers());
    }

    @Test void partialChineseAliasAndEnglishSubstringCannotTriggerExpansion() {
        var corpus=List.of(source("amount","amount","定义","amount"));var dictionary=dictionary(field("amount",List.of("净销售金额","tax"),""));
        assertTrue(KnowledgeSearch.search("净销售",corpus,dictionary,12).expansions().isEmpty());
        var result=KnowledgeSearch.search("syntax",corpus,dictionary,12);assertTrue(result.expansions().isEmpty());assertTrue(result.sources().isEmpty());
    }

    @Test void freeTextMeaningAndUnconfiguredMetricsAreNotSynonyms() {
        var definition=dictionary(field("amount",List.of("booked"),"cash 已收款 不是同一指标"));
        var result=KnowledgeSearch.search("booked",List.of(source("amount","amount","定义","amount"),source("cash","cash","收款","cash 已收款")),definition,12);
        assertEquals(List.of("amount"),result.sources().stream().map(AnalysisEvidence.Source::id).toList());
        assertFalse(result.expansions().stream().flatMap(e->e.addedTerms().stream()).anyMatch(t->t.contains("cash")));
    }

    @Test void expansionDoesNotRecursivelyTriggerAnotherFieldsName() {
        var definition=dictionary(field("amount",List.of("gross value"),""),field("gross",List.of("taxbasis"),""));
        var result=KnowledgeSearch.search("amount",List.of(source("taxbasis","taxbasis","定义","taxbasis")),definition,12);
        assertTrue(result.sources().isEmpty());assertEquals(List.of("amount"),result.expansions().stream().map(KnowledgeSearch.Expansion::field).toList());
    }

    @Test void expansionBudgetIsExplicitAndDeterministic() {
        var fields=new ArrayList<SemanticDictionary.Field>();var question=new StringBuilder();
        for(int i=0;i<8;i++){var aliases=new ArrayList<String>();for(int j=0;j<12;j++)aliases.add("alias_"+i+"_"+j);fields.add(field("f"+i,aliases,""));question.append("f").append(i).append(' ');}
        var result=KnowledgeSearch.search(question.toString(),List.of(),dictionary(fields.toArray(SemanticDictionary.Field[]::new)),12);
        assertEquals(64,result.expansions().stream().mapToInt(e->e.addedTerms().size()).sum());assertTrue(result.expansionTruncated());
    }

    @Test void sourceCoverageDistinguishesUnreturnedSourcesFromRepeatedFragments() {
        var corpus=new ArrayList<AnalysisEvidence.Source>();for(int i=0;i<14;i++)corpus.add(source("s"+i,"o"+i,"税额","tax"));
        var result=KnowledgeSearch.search("tax",corpus,SemanticDictionary.Definition.empty(),12);
        assertEquals(new KnowledgeSearch.Coverage(14,14,12),result.coverage());assertTrue(result.truncated());
        Collections.reverse(corpus);assertEquals(result,KnowledgeSearch.search("tax",corpus,SemanticDictionary.Definition.empty(),12));
    }

    @Test void quoteSplittingPreservesOriginAndDoesNotIncreaseReturnedBudget() {
        var original=source("long","document","Tax policy","tax applies。\n".repeat(600));var corpus=new ArrayList<>(AnalysisEvidence.parts(original));
        corpus.add(source("other","counter","Tax policy","tax does not apply"));
        assertTrue(corpus.stream().filter(s->!s.id().equals("other")).allMatch(s->s.originId().equals("document")));
        var result=KnowledgeSearch.search("tax",corpus,SemanticDictionary.Definition.empty(),2);
        assertEquals(2,result.sources().size());assertEquals(2,result.coverage().returnedSources());assertTrue(result.sources().stream().allMatch(s->s.text().length()<=2000));
    }

    @Test void unrelatedAndOnlyNoiseQuestionsDoNotReturnDefaultSources() {
        var corpus=List.of(source("sales","sales","销售制度","订单金额定义"));
        assertTrue(KnowledgeSearch.search("火星轨道",corpus,SemanticDictionary.Definition.empty(),12).sources().isEmpty());
        assertTrue(KnowledgeSearch.search("what is the",corpus,SemanticDictionary.Definition.empty(),12).queryTerms().isEmpty());
    }

    @Test void oversizeQuestionsCorporaAndUnsplitSourcesAreRejected() {
        var definition=SemanticDictionary.Definition.empty();var valid=source("s","s","说明","tax");
        assertThrows(ApiException.class,()->KnowledgeSearch.search("x".repeat(2001),List.of(),definition,12));
        assertThrows(ApiException.class,()->KnowledgeSearch.search(String.join(" ",java.util.stream.IntStream.range(0,257).mapToObj(i->"v"+i).toList()),List.of(),definition,12));
        assertThrows(ApiException.class,()->KnowledgeSearch.search("tax",Collections.nCopies(1601,valid),definition,12));
        assertThrows(ApiException.class,()->KnowledgeSearch.search("tax",List.of(source("long","long","说明","x".repeat(2001))),definition,12));
        assertThrows(ApiException.class,()->KnowledgeSearch.search("tax",List.of(valid,valid),definition,12));
    }
}
