package com.example.report.analysis;

import com.example.report.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 核对模型实际接收的检索执行事实与计量单位，区分未执行、无命中和来源/片段截断。 */
class KnowledgeCoverageTest {
    @Test void dataOnlyAnalysisDoesNotPretendKnowledgeWasSearched() throws Exception {
        var value=coverage(List.of(Map.of("index",0,"action","SNAPSHOT")));
        assertTrue(value.isObject());assertFalse(value.path("performed").asBoolean(true));assertEquals(0,value.path("steps").size());
        assertFalse(value.path("truncated").asBoolean(true));assertFalse(value.path("expansionTruncated").asBoolean(true));
    }
    @Test void anExecutedSearchWithoutHitsIsDifferentFromNoSearch() throws Exception {
        var value=coverage(List.of(step(2,4,0,0,7,0,0,false,false)));
        assertTrue(value.path("performed").asBoolean());var item=value.path("steps").get(0);
        assertEquals(3,item.path("stepNumber").asInt());assertEquals(4,item.path("searchedSources").asInt());
        assertEquals(7,item.path("searchedFragments").asInt());assertEquals(0,item.path("returnedFragments").asInt());
    }
    @Test void fragmentAndSourceCountsKeepTheirOwnUnitsAcrossSteps() throws Exception {
        var value=coverage(List.of(step(0,6,6,6,18,18,12,true,false),step(4,3,2,2,8,3,3,false,true)));
        assertTrue(value.path("performed").asBoolean());assertTrue(value.path("truncated").asBoolean());assertTrue(value.path("expansionTruncated").asBoolean());
        var first=value.path("steps").get(0);assertEquals(6,first.path("returnedSources").asInt());assertEquals(12,first.path("returnedFragments").asInt());
        assertEquals(18,first.path("searchedFragments").asInt());assertEquals(18,first.path("matchedFragments").asInt());
        assertEquals(2,value.path("steps").get(1).path("returnedSources").asInt());assertEquals(3,value.path("steps").get(1).path("returnedFragments").asInt());
    }
    @Test void actualCoverageCanBeCitedWithoutAcceptingInventedCounts() {
        var fragment=new AnalysisEvidence.Source("doc","origin","KNOWLEDGE","制度","v1","A / report","当前有效","制度正文");
        var result=new KnowledgeSearch.Result(Collections.nCopies(12,fragment),18,18,true,new KnowledgeSearch.Coverage(6,6,6),List.of(),List.of(),false,List.of());
        var source=AnalysisEngine.retrievalEvidence(0,"version","A / report","2026-10-12T00:00:00Z",result);
        assertEquals("DATA",source.kind());assertEquals(source.id(),source.originId());
        var valid=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("已搜索6份来源、18个片段，返回12个片段。",List.of(source.id()))),List.of("本轮检索覆盖有限"));
        var answer=AnalysisEvidence.resolve(valid,List.of(source));assertEquals(source.text(),answer.claims().get(0).citations().get(0).quote());
        var invalid=new AnalysisEvidence.Draft("ANSWERED",List.of(new AnalysisEvidence.DraftClaim("返回13个片段。",List.of(source.id()))),List.of());
        assertThrows(com.example.report.common.ApiException.class,()->AnalysisEvidence.resolve(invalid,List.of(source)));
    }
    private static com.fasterxml.jackson.databind.JsonNode coverage(List<Map<String,Object>> steps) throws Exception {
        Object value=ReflectionTestUtils.invokeMethod(AnalysisEngine.class,"knowledgeCoverage",steps);
        return JsonUtil.MAPPER.readTree(JsonUtil.toJson(value));
    }
    private static Map<String,Object> step(int index,int searchedSources,int matchedSources,int returnedSources,int searchedFragments,int matchedFragments,int returnedFragments,boolean truncated,boolean expansionTruncated) {
        return Map.of("index",index,"action","KNOWLEDGE","result",Map.of("coverage",new KnowledgeSearch.Coverage(searchedSources,matchedSources,returnedSources),
                "searched",searchedFragments,"matched",matchedFragments,"sources",Collections.nCopies(returnedFragments,"引用片段"),"truncated",truncated,"expansionTruncated",expansionTruncated));
    }
}
