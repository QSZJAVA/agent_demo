package com.example.report.analysis;

import com.example.report.assistant.*;
import com.example.report.common.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;
import static org.junit.jupiter.api.Assertions.*;

/** 使用显式模型响应检验多路RAG的确定性契约、预算和拒绝路径；这些单元测试不冒充真实模型语义质量验收。 */
class KnowledgeRagTest {
    static AnalysisEvidence.Source source(String id,String origin,String title,String text){return new AnalysisEvidence.Source(id,origin,"KNOWLEDGE",title,"1","A / rpt-sales-order","2026-10-12",text,0);}
    static KnowledgeService.Snapshot snapshot(List<AnalysisEvidence.Source> sources){return new KnowledgeService.Snapshot(null,new SemanticDictionaryService.Entry(new SemanticDictionary.Scope(BusinessQuery.Domain.REPORT,"A","rpt-sales-order"),0,SemanticDictionary.Definition.empty()),sources,"published-fingerprint",Instant.parse("2026-10-12T00:00:00Z"));}
    static Map<Integer,KnowledgeRag.Retrieved> search(String question,List<KnowledgeRag.Topic> topics,List<AnalysisEvidence.Source> sources,RecordingModel model){return KnowledgeRag.search(question,topics,snapshot(sources),model);}
    static KnowledgeRag.Retrieved search(String question,List<AnalysisEvidence.Source> sources,RecordingModel model){return search(question,List.of(new KnowledgeRag.Topic(0,question)),sources,model).get(0);}
    /** 受控响应遵守两个可空字段；第三条必须作为非法字段发出，不能在测试端先被截掉。 */
    static Map<String,Object> rewritten(int index,List<String> queries){var value=new LinkedHashMap<String,Object>();value.put("index",index);value.put("firstQuery",queries.isEmpty()?null:queries.get(0));value.put("secondQuery",queries.size()<2?null:queries.get(1));if(queries.size()>2)value.put("thirdQuery",queries.get(2));return value;}

    @Test void semanticRewriteCanRetrieveAnOtherwiseUnmatchedLanguageWithoutBecomingAnAlias() {
        var doc=source("lodging","policy","差旅制度","差旅住宿补助按已发布制度核定。");var model=new RecordingModel();model.rewrites=List.of("差旅住宿补助");
        assertTrue(KnowledgeSearch.search("overnight accommodation",List.of(doc),SemanticDictionary.Definition.empty(),12).sources().isEmpty());
        var result=search("overnight accommodation",List.of(doc),model);
        assertEquals(List.of("RAG_REWRITE","RAG_RERANK"),model.stages);assertEquals(List.of(doc),result.result().sources());
        assertEquals("overnight accommodation",result.retrieval().queries().get(0).text());assertEquals("REWRITE",result.retrieval().queries().get(1).kind());
        assertTrue(result.result().expansions().isEmpty());assertTrue(result.result().hits().get(0).directTerms().isEmpty());assertTrue(result.result().hits().get(0).aliasTerms().isEmpty());
        assertEquals(List.of(1),result.retrieval().ranking().get(0).routes());assertEquals(doc.text(),result.result().sources().get(0).text());
    }
    @Test void overlappingChannelsCountUniqueFragmentsAndOrigins() {
        var sources=List.of(source("p1","doc","金额","订单金额amount"),source("p2","doc","金额","amount不是已收款"),source("p3","other","定义","amount支持其他定义"));
        var model=new RecordingModel();model.rewrites=List.of("amount","ＡＭＯＵＮＴ");
        var result=search("amount",sources,model);assertEquals(1,result.retrieval().queries().size());assertEquals(3,result.result().searched());assertEquals(3,result.result().matched());
        assertEquals(new KnowledgeSearch.Coverage(2,2,2),result.result().coverage());assertEquals(3,result.result().sources().size());assertFalse(result.result().truncated());
    }
    @Test void originalQuestionRemainsSearchableEvenIfTheModelDrifts() {
        var model=new RecordingModel();model.rewrites=List.of("unrelated");
        var result=search("target",List.of(source("right","right","target","target rule"),source("drift","drift","unrelated","unrelated rule")),model);
        assertTrue(result.result().sources().stream().anyMatch(s->s.id().equals("right")));assertEquals("ORIGINAL",result.retrieval().queries().get(0).kind());
    }
    @Test void sixKnowledgeTopicsShareOnlyTwoModelCallsAndTheirActualStepNumbers() {
        var topics=new ArrayList<KnowledgeRag.Topic>();for(int i=0;i<6;i++)topics.add(new KnowledgeRag.Topic(i,"topic"+i));
        var model=new RecordingModel();var result=search("shared",topics,List.of(source("shared","doc","shared","shared topic0 topic1 topic2 topic3 topic4 topic5")),model);
        assertEquals(6,result.size());assertEquals(2,model.stages.size());for(int i=0;i<6;i++)assertEquals(1,result.get(i).result().sources().size());
    }
    @Test void emptyKnowledgePlanDoesNotCallTheModel() {
        var model=new RecordingModel();assertTrue(search("data only",List.of(),List.of(),model).isEmpty());assertTrue(model.stages.isEmpty());
    }
    @Test void noMatchIsDifferentFromUnexecutedReranking() {
        var model=new RecordingModel();var result=search("astronomy",List.of(source("tax","tax","tax","tax applies")),model);
        assertEquals(List.of("RAG_REWRITE"),model.stages);assertEquals(1,result.result().searched());assertEquals(0,result.result().matched());assertFalse(result.retrieval().reranked());assertFalse(result.result().truncated());
    }
    @Test void anUnknownOrMissingRewriteTopicIsRejectedWithoutLexicalFallback() {
        for(Object invalid:List.of(Map.of("topics",List.of()),Map.of("topics",List.of(rewritten(5,List.of()))),Map.of("topics",List.of(rewritten(0,List.of()),rewritten(0,List.of()))))) {
            var model=new RecordingModel();model.rewriteOutput=ignored->invalid;
            assertEquals(422,assertThrows(ApiException.class,()->search("tax",List.of(source("p","d","tax","tax applies")),model)).getCode());assertEquals(List.of("RAG_REWRITE"),model.stages);
        }
    }
    @Test void malformedAndOversizedRewritesAreRejected() {
        var doc=source("p","d","tax","tax applies");
        for(Object invalid:List.of(Map.of("topics",List.of(rewritten(0,List.of("x".repeat(301))))),Map.of("topics",List.of(rewritten(0,List.of("one","two","three")))))) {
            var model=new RecordingModel();model.rewriteOutput=ignored->invalid;assertThrows(ApiException.class,()->search("tax",List.of(doc),model));
        }
        assertThrows(ApiException.class,()->KnowledgeRag.search("tax",List.of(new KnowledgeRag.Topic(0,"tax")),snapshot(List.of(doc)),(stage,instructions,input,schema)->"{\"topics\":[],\"authority\":\"admin\"}"));
    }
    @Test void twoNullableRewriteFieldsConstrainCountWithoutSilentlyTrimmingExtraOutput() {
        assertEquals(List.of(),new KnowledgeRag.Rewritten(0,null,null).queries());
        assertEquals(List.of("tax excludes fees"),new KnowledgeRag.Rewritten(0,null,"tax excludes fees").queries());
        assertEquals(List.of("tax included","tax excluded"),new KnowledgeRag.Rewritten(0,"tax included","tax excluded").queries());
        assertThrows(ApiException.class,()->new KnowledgeRag.Rewritten(0,"  ",null));
        var stages=new ArrayList<String>();
        assertThrows(ApiException.class,()->KnowledgeRag.search("tax",List.of(new KnowledgeRag.Topic(0,"tax")),snapshot(List.of(source("p","d","tax","tax applies"))),(stage,instructions,input,schema)->{
            stages.add(stage);var properties=JsonUtil.fromJson(schema,JsonNode.class).path("properties").path("topics").path("items").path("properties");
            assertTrue(properties.has("firstQuery"));assertTrue(properties.has("secondQuery"));assertFalse(properties.has("queries"));
            return "{\"topics\":[{\"index\":0,\"firstQuery\":\"tax included\",\"secondQuery\":\"tax excluded\",\"thirdQuery\":\"another phrase\"}]}";
        }));
        assertEquals(List.of("RAG_REWRITE"),stages);
    }
    @Test void rerankerCannotDropDuplicateOrInventSources() {
        for(var ids:List.of(List.<String>of(),List.of("c0","c0"),List.of("c0","invented"))) {
            var model=new RecordingModel();model.rankOutput=ignored->Map.of("topics",List.of(Map.of("index",0,"sourceIds",ids)));
            assertEquals(422,assertThrows(ApiException.class,()->search("tax",List.of(source("first","a","tax","tax applies"),source("second","b","tax","tax does not apply")),model)).getCode());
            assertEquals(2,model.stages.size());
        }
    }
    @Test void rerankerCannotBorrowAnActualButUnmatchedSourceFromAnotherTopic() {
        var model=new RecordingModel();model.rankOutput=ignored->Map.of("topics",List.of(Map.of("index",0,"sourceIds",List.of("c0","c1")),Map.of("index",1,"sourceIds",List.of("c1"))));
        assertThrows(ApiException.class,()->search("question",List.of(new KnowledgeRag.Topic(0,"alpha"),new KnowledgeRag.Topic(1,"beta")),List.of(source("left","a","alpha","alpha rule"),source("right","b","beta","beta rule")),model));
    }
    @Test void modelTransportFailurePropagatesEvenWhenLexicalEvidenceExists() {
        var stages=new ArrayList<String>();
        assertThrows(ApiException.class,()->KnowledgeRag.search("tax",List.of(new KnowledgeRag.Topic(0,"tax")),snapshot(List.of(source("p","d","tax","tax applies"))),(stage,instructions,input,schema)->{stages.add(stage);throw new ApiException(503,"真实模型不可用");}));assertEquals(List.of("RAG_REWRITE"),stages);
    }
    @Test void sourceDiversityPreservesTheContraryDocumentDespiteLongSourcePreference() {
        var corpus=new ArrayList<AnalysisEvidence.Source>();for(int i=0;i<30;i++)corpus.add(source("long-"+i,"long","tax","tax included in amount"));corpus.add(source("opposite","short","tax","tax not included in amount"));
        var model=new RecordingModel();model.longFirst=true;var result=search("tax",corpus,model);
        assertTrue(result.result().sources().stream().anyMatch(s->s.id().equals("opposite")));assertEquals(2,result.result().coverage().returnedSources());
        assertEquals(31,result.result().matched());assertTrue(result.result().truncated());assertTrue(result.retrieval().contextTruncated());
    }
    @Test void utf8ContextAndCandidateBudgetsAreMeasuredBeforeCallingReranker() {
        var corpus=new ArrayList<AnalysisEvidence.Source>();for(int i=0;i<80;i++)corpus.add(source("p"+i,"d"+i,"tax","tax "+"知".repeat(1850)));
        var model=new RecordingModel();var result=search("tax",corpus,model);var input=model.inputs.get(1);
        assertTrue(JsonUtil.toJson(input).getBytes(StandardCharsets.UTF_8).length<=48*1024);assertTrue(input.path("sources").size()<=24);
        assertTrue(result.retrieval().contextTruncated());assertTrue(result.result().truncated());assertEquals(80,result.result().matched());
        for(var s:input.path("sources"))assertEquals(1854,s.path("text").asText().length());
    }
    @Test void theSameSnapshotProducesStableFusedOrderAndExactQuotes() {
        var corpus=List.of(source("b","b","tax","tax excludes fees"),source("a","a","tax","tax includes fees"));var left=search("tax",corpus,new RecordingModel());var right=search("tax",corpus,new RecordingModel());
        assertEquals(left,right);var sources=left.result().sources();var draft=new AnalysisEvidence.Draft("CONFLICTING",List.of(new AnalysisEvidence.DraftClaim("资料口径存在分歧",sources.stream().map(AnalysisEvidence.Source::id).toList())),List.of("需业务确认"));
        var answer=AnalysisEvidence.resolve(draft,sources);assertEquals(sources.get(0).text(),answer.claims().get(0).citations().get(0).quote());
    }
    @Test void publicLexicalPreviewDoesNotGainAnUnboundedLimit() {assertThrows(ApiException.class,()->KnowledgeSearch.search("tax",List.of(),SemanticDictionary.Definition.empty(),13));}

    @Test void sixTopicsSharingTwentyFourLongIdsHaveBoundedModelOutputAndRestoreRealIds() {
        var corpus=new ArrayList<AnalysisEvidence.Source>();for(int i=0;i<24;i++)corpus.add(source("source-"+i+"-"+"x".repeat(110),"origin-"+i,"tax","tax rules"));
        var topics=new ArrayList<KnowledgeRag.Topic>();for(int i=0;i<6;i++)topics.add(new KnowledgeRag.Topic(i,"tax"));
        var model=new RecordingModel();var results=search("tax",topics,corpus,model);var topicOutputs=new ArrayList<Map<String,Object>>();
        for(var topic:model.inputs.get(1).path("topics")){var ids=new ArrayList<String>();topic.path("sourceIds").forEach(id->{assertTrue(id.asText().matches("c(?:[0-9]|1[0-9]|2[0-3])"));ids.add(id.asText());});assertEquals(24,ids.size());topicOutputs.add(Map.of("index",topic.path("index").asInt(),"sourceIds",ids));}
        assertTrue(JsonUtil.toJson(Map.of("topics",topicOutputs)).length()<2048);for(var result:results.values())assertTrue(result.result().sources().stream().allMatch(s->s.id().startsWith("source-")&&s.id().length()>100));
    }

    /** 模型端口测试替身仅回显显式输入编号，用于契约验证；不生成业务答案或假装语义能力。 */
    static final class RecordingModel implements KnowledgeRag.ModelCall {
        final List<String> stages=new ArrayList<>();final List<JsonNode> inputs=new ArrayList<>();List<String> rewrites=List.of();boolean longFirst;
        UnaryOperator<Object> rewriteOutput=value->value,rankOutput=value->value;
        @Override public String call(String stage,String instructions,Object input,String schema) {
            stages.add(stage);var node=JsonUtil.MAPPER.valueToTree(input);inputs.add(node);var topics=new ArrayList<Map<String,Object>>();
            for(var topic:node.path("topics")) {
                int index=topic.path("index").asInt();if("RAG_REWRITE".equals(stage))topics.add(rewritten(index,rewrites));
                else {var ids=new ArrayList<String>();topic.path("sourceIds").forEach(id->ids.add(id.asText()));if(longFirst){var preferred=new HashSet<String>();node.path("sources").forEach(s->{if(s.path("originId").asText().equals("long"))preferred.add(s.path("id").asText());});ids.sort(Comparator.comparing(id->preferred.contains(id)?0:1));}topics.add(Map.of("index",index,"sourceIds",ids));}
            }
            var response=Map.of("topics",topics);return JsonUtil.toJson(("RAG_REWRITE".equals(stage)?rewriteOutput:rankOutput).apply(response));
        }
    }
}
