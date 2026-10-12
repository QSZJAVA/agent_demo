package com.example.report.analysis;

import com.example.report.common.ApiException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 来源召回、精确引用及制度表单边界的纯逻辑回归，不据此声称模型答案已经真实验收。 */
class AnalysisEvidenceTest {
    static AnalysisEvidence.Source source(String id,String title,String text){return new AnalysisEvidence.Source(id,id,"KNOWLEDGE",title,"2","A / rpt-sales-order","2026-09-01生效",text);}
    static final AnalysisEvidence.Source SOURCE=source("s1","销售口径","销售金额合计为90071992547409.93元；不等于已收款。");
    static AnalysisEvidence.Answer answer(String text,String id,String quote){return new AnalysisEvidence.Answer("ANSWERED",List.of(new AnalysisEvidence.Claim(text,List.of(new AnalysisEvidence.Citation(id,quote)))),List.of());}
    @Test void acceptsExactGroundedDecimal(){assertDoesNotThrow(()->AnalysisEvidence.validate(answer("合计90071992547409.93元。","s1",SOURCE.text()),List.of(SOURCE)));}
    @Test void serverResolvesOnlySelectedActualSourceIntoExactQuote(){var draft=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("销售金额不等于已收款。",List.of("s1"))),List.of());
        var resolved=AnalysisEvidence.resolve(draft,List.of(SOURCE));assertEquals(SOURCE.text(),resolved.claims().get(0).citations().get(0).quote());
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(draft,List.of()));}
    @Test void sourcePartsPreserveEveryCharacterAndRemainWithinCitationLimit(){var original=source("long","长资料","行文本和数字90071992547409.93。\n".repeat(250));var parts=AnalysisEvidence.parts(original);
        assertTrue(parts.size()>1);assertTrue(parts.stream().allMatch(s->s.text().length()<=2000));assertEquals(original.text(),parts.stream().map(AnalysisEvidence.Source::text).collect(java.util.stream.Collectors.joining()));assertEquals(parts.size(),parts.stream().map(AnalysisEvidence.Source::id).distinct().count());}
    @Test void allowsActualVersionMetadataButDoesNotTreatItAsBodyQuote(){assertDoesNotThrow(()->AnalysisEvidence.validate(answer("依据版本2，销售金额不等于已收款。","s1",SOURCE.text()),List.of(SOURCE)));
        assertThrows(ApiException.class,()->AnalysisEvidence.validate(answer("依据版本3。","s1",SOURCE.text()),List.of(SOURCE)));
        assertThrows(ApiException.class,()->AnalysisEvidence.validate(answer("依据版本2。","s1","2026-09-01生效"),List.of(SOURCE)));}
    @Test void rejectsInventedSource(){assertThrows(ApiException.class,()->AnalysisEvidence.validate(answer("说明","missing",SOURCE.text()),List.of(SOURCE)));}
    @Test void rejectsModifiedQuote(){assertThrows(ApiException.class,()->AnalysisEvidence.validate(answer("说明","s1","销售金额等于已收款"),List.of(SOURCE)));}
    @Test void rejectsUncomputedNumber(){assertThrows(ApiException.class,()->AnalysisEvidence.validate(answer("合计90071992547409.94元。","s1",SOURCE.text()),List.of(SOURCE)));}
    @Test void fullAndSelectedSetsNeedTheirOwnNumericalCitations(){
        var full=source("full","查询全集","全部5条记录合计30.15元。");var selected=source("selected","排名选集","选中2条记录合计20.10元。");
        var supported=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("全部5条合计30.15元；选中2条合计20.10元。",List.of("full","selected"))),List.of());
        assertDoesNotThrow(()->AnalysisEvidence.resolve(supported,List.of(full,selected)));
        var missing=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("全部5条合计30.15元；选中2条合计20.10元。",List.of("selected"))),List.of());
        assertThrows(ApiException.class,()->AnalysisEvidence.resolve(missing,List.of(full,selected)));
        var hints=AnalysisEvidence.citationHints(missing,List.of(full,selected));assertEquals(2,hints.size());
        assertTrue(hints.stream().allMatch(h->h.get("candidateSourceIds").equals(List.of("full"))));
        // 提示没有修改原草稿，更不等于已经引用或通过验证。
        assertEquals(List.of("selected"),missing.claims().get(0).sourceIds());assertThrows(ApiException.class,()->AnalysisEvidence.resolve(missing,List.of(full,selected)));
    }
    @Test void missingNumberHintNeverFabricatesASource(){var draft=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("金额为999.99元。",List.of("s1"))),List.of());
        var hints=AnalysisEvidence.citationHints(draft,List.of(SOURCE));assertEquals(List.of(),hints.get(0).get("candidateSourceIds"));}
    @Test void rejectsUnsupportedDefinitiveAnswer(){assertThrows(ApiException.class,()->AnalysisEvidence.validate(new AnalysisEvidence.Answer("ANSWERED",List.of(),List.of()),List.of()));}
    @Test void allowsExplicitInsufficiency(){assertDoesNotThrow(()->AnalysisEvidence.validate(new AnalysisEvidence.Answer("INSUFFICIENT",List.of(),List.of("没有出差制度依据")),List.of()));}
    @Test void conflictNeedsAnExplanation(){assertThrows(ApiException.class,()->AnalysisEvidence.validate(new AnalysisEvidence.Answer("CONFLICTING",List.of(),List.of()),List.of(SOURCE)));}
    @Test void retrievalHasNoUnrelatedDefault(){assertTrue(KnowledgeSearch.search("火星轨道",List.of(SOURCE),com.example.report.assistant.SemanticDictionary.Definition.empty(),12).sources().isEmpty());}
    @Test void retrievalFindsBusinessDefinition(){assertEquals("s1",KnowledgeSearch.search("销售金额指什么",List.of(SOURCE,source("s2","报销","出差酒店标准")),com.example.report.assistant.SemanticDictionary.Definition.empty(),12).sources().get(0).id());}
    @Test void retrievalMakesTruncationExplicit(){var sources=new ArrayList<AnalysisEvidence.Source>();for(int i=0;i<15;i++)sources.add(source("s"+i,"销售口径","销售金额定义"));var result=KnowledgeSearch.search("销售金额",sources,com.example.report.assistant.SemanticDictionary.Definition.empty(),12);assertEquals(15,result.matched());assertEquals(12,result.sources().size());assertTrue(result.truncated());}
    @Test void knowledgeCannotFetchCredentialUrls(){assertThrows(ApiException.class,()->new KnowledgeService.Content("A","rpt-sales-order","口径","正文","手册","https://user:fixture@example.com/manual","2026-09-01",null));}
    @Test void knowledgeRequiresValidExclusiveEndDate(){assertThrows(ApiException.class,()->new KnowledgeService.Content("A","rpt-sales-order","口径","正文","手册",null,"2026-09-01","2026-09-01"));}
    @Test void knowledgeEffectivenessIsBounded(){var content=new KnowledgeService.Content("A","rpt-sales-order","口径","正文","手册",null,"2026-09-01","2026-10-01");assertTrue(content.effective(java.time.LocalDate.parse("2026-09-01")));assertFalse(content.effective(java.time.LocalDate.parse("2026-10-01")));}
}
