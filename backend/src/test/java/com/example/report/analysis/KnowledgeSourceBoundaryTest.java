package com.example.report.analysis;

import com.example.report.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 检查知识来源在内部持久证据和引用切片处的一致边界，不依赖HTTP最后一层脱敏来隐藏已泄漏的元数据。 */
class KnowledgeSourceBoundaryTest {
    @Test void sourceTitleAndTextUseTheSameRedactedMetadata() {
        var sources=new ArrayList<AnalysisEvidence.Source>();ReflectionTestUtils.invokeMethod(KnowledgeService.class,"add",sources,"document","联系人13800000000和person@example.com的住宿制度","1","A / rpt-sales-order","当前有效","原文同样包含13800000000与person@example.com。");
        String json=JsonUtil.toJson(sources);assertFalse(json.contains("13800000000"));assertFalse(json.contains("person@example.com"));assertTrue(sources.get(0).text().contains("来源标题："));
    }
    @Test void longMetadataKeepsEveryCitationWholeAndWithinItsBudget() {
        var sources=new ArrayList<AnalysisEvidence.Source>();String title="标题".repeat(100),body="条款正文。".repeat(2000);
        ReflectionTestUtils.invokeMethod(KnowledgeService.class,"add",sources,"document",title,"a".repeat(64),"A / rpt-sales-order","生效2026-01-01，未指定失效日期",body);
        assertTrue(sources.size()>1);assertTrue(sources.stream().allMatch(s->s.text().length()<=2000&&s.text().contains("来源标题：")&&s.text().contains("原文位置：")&&s.text().contains("正文片段：\n")));
        assertTrue(sources.stream().allMatch(s->s.originId().equals("document")&&!s.id().contains("-q")));
    }
    @Test void failedPreviewDiagnosticsKeepOnlyAccountingAndNoSensitiveContext() {
        var input=new LinkedHashMap<String,Object>();input.put("number",1);input.put("stage","RAG_REWRITE");input.put("status","RESPONDED");input.put("inputTokens",123);input.put("outputTokens",45);input.put("cachedInputTokens",null);input.put("validation","REJECTED");
        for(String key:List.of("question","reason","sourceId","source","queries","model"))input.put(key,"private-business-content");
        var result=KnowledgeRagPreviewService.diagnosticUsage(List.of(input));assertEquals(123,result.get(0).get("inputTokens"));assertEquals(45,result.get(0).get("outputTokens"));assertTrue(result.get(0).containsKey("cachedInputTokens"));assertNull(result.get(0).get("cachedInputTokens"));assertFalse(JsonUtil.toJson(result).contains("private-business-content"));
    }
    @Test void scopeDatesAndGeneratedLocationDoNotBecomeDocumentTopics() {
        var sources=new ArrayList<AnalysisEvidence.Source>();ReflectionTestUtils.invokeMethod(KnowledgeService.class,"add",sources,"document","差旅住宿定义","1","A / rpt-sales-order","生效2026-01-01","来源：测试资料\n差旅住宿按制度核定。");
        for(String query:List.of("sales","2026","UTF-16","片段"))assertTrue(KnowledgeSearch.search(query,sources,com.example.report.assistant.SemanticDictionary.Definition.empty(),12).sources().isEmpty(),query+"只在引用元数据中，不能成为正文命中");
        var result=KnowledgeSearch.search("差旅住宿",sources,com.example.report.assistant.SemanticDictionary.Definition.empty(),12);assertEquals(1,result.sources().size());assertTrue(result.sources().get(0).text().contains("rpt-sales-order"));assertTrue(result.sources().get(0).contentOffset()>0);
        assertEquals("差旅住宿定义",result.sources().get(0).title());
    }
    @Test void aBodyCanContainHeaderLikeWordsWithoutChangingItsSearchBoundary() {
        String header="来源元数据：sales 2026\n",body="正文片段：sales表示销售。";
        var source=new AnalysisEvidence.Source("id","origin","KNOWLEDGE","定义","1","A","当前",header+body,header.length());
        assertEquals(1,KnowledgeSearch.search("sales",List.of(source),com.example.report.assistant.SemanticDictionary.Definition.empty(),12).matched());
        assertThrows(com.example.report.common.ApiException.class,()->new AnalysisEvidence.Source("id","origin","KNOWLEDGE","定义","1","A","当前","😀正文",1));
    }
}
