package com.example.report.analysis;

import com.example.report.assistant.BusinessAssistantService;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.example.report.investigation.InvestigationJson;
import com.example.report.support.TestCatalog;
import com.example.report.workspace.QueryWorkspaceService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static com.example.report.analysis.AnalysisContract.Mode.*;
import static com.example.report.analysis.AnalysisContractTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 读取模式的协议、执行与恢复边界；程序测试核对不越界访问，不替代真实模型及HTTP业务验收。 */
class AnalysisModeTest {
    private static final String SNAPSHOT="a".repeat(32),OTHER="b".repeat(32);
    private static AnalysisContract.Request request(AnalysisContract.Mode mode,String snapshot){return new AnalysisContract.Request("A",TestCatalog.SALES,"按原要求分析",mode,snapshot);}
    private static AnalysisContract.Step snapshot(){return new AnalysisContract.Step("SNAPSHOT",null,null,null,null,null,null);}
    private static AnalysisContract.Step knowledge(){return new AnalysisContract.Step("KNOWLEDGE",null,null,null,null,null,"说明业务口径");}

    @Test void modeIsRequiredAndNeverCoercedOrInferredFromSnapshot() {
        var valid=request(CURRENT_DATA,null);var json=(ObjectNode)JsonUtil.MAPPER.valueToTree(valid);
        var missing=json.deepCopy();missing.remove("mode");assertThrows(ApiException.class,()->AnalysisContract.decode(missing.toString(),AnalysisContract.Request.class));
        for(var mode:List.of(JsonUtil.MAPPER.getNodeFactory().nullNode(),JsonUtil.MAPPER.getNodeFactory().textNode("current_data"),JsonUtil.MAPPER.getNodeFactory().textNode("UNKNOWN"),JsonUtil.MAPPER.getNodeFactory().numberNode(1))) {
            var invalid=json.deepCopy();invalid.set("mode",mode);assertThrows(ApiException.class,()->AnalysisContract.decode(invalid.toString(),AnalysisContract.Request.class));
        }
        assertThrows(ApiException.class,()->request(null,SNAPSHOT));
        assertThrows(ApiException.class,()->AnalysisContract.decode("null",AnalysisContract.Request.class));
        assertThrows(ApiException.class,()->request(KNOWLEDGE_ONLY,SNAPSHOT));
        assertThrows(ApiException.class,()->request(SNAPSHOT_ONLY,null));
        assertEquals(valid,AnalysisContract.decode(JsonUtil.toJson(valid),AnalysisContract.Request.class));
    }

    @Test void knowledgeAndSnapshotPlansHaveNoPathToForbiddenActions() {
        assertDoesNotThrow(()->AnalysisContract.validate(request(KNOWLEDGE_ONLY,null),plan(knowledge()),FIELDS));
        assertThrows(ApiException.class,()->AnalysisContract.validate(request(KNOWLEDGE_ONLY,null),plan(query("A")),FIELDS));
        assertThrows(ApiException.class,()->AnalysisContract.validate(request(KNOWLEDGE_ONLY,null),plan(snapshot()),FIELDS));
        var selected=request(SNAPSHOT_ONLY,SNAPSHOT);
        assertDoesNotThrow(()->AnalysisContract.validate(selected,plan(snapshot(),group(0),rank(1),new AnalysisContract.Step("DRILLDOWN",0,2,null,null,null,null),knowledge()),FIELDS));
        assertThrows(ApiException.class,()->AnalysisContract.validate(selected,plan(query("A")),FIELDS));
        assertThrows(ApiException.class,()->AnalysisContract.validate(selected,plan(snapshot(),snapshot(),new AnalysisContract.Step("COMPARE",0,1,null,null,null,null)),FIELDS));
    }

    @Test void currentDataRetainsExplicitSnapshotToCurrentComparison() {
        var graph=plan(snapshot(),query("A"),new AnalysisContract.Step("COMPARE",0,1,null,null,null,null));
        assertDoesNotThrow(()->AnalysisContract.validate(request(CURRENT_DATA,SNAPSHOT),graph,FIELDS));
        assertThrows(ApiException.class,()->AnalysisContract.validate(request(CURRENT_DATA,null),graph,FIELDS));
        assertNotEquals(InvestigationJson.hash(request(CURRENT_DATA,null)),InvestigationJson.hash(request(KNOWLEDGE_ONLY,null)));
        assertNotEquals(InvestigationJson.hash(request(CURRENT_DATA,SNAPSHOT)),InvestigationJson.hash(request(SNAPSHOT_ONLY,SNAPSHOT)));
    }

    @Test void schemasHideForbiddenActionsAndTheirParameters() throws Exception {
        var schemas=new LinkedHashSet<String>();
        for(var mode:AnalysisContract.Mode.values()) {
            String schema=AnalysisContract.planSchema(mode);schemas.add(AnalysisContract.schemaHash(schema));
            var step=JsonUtil.MAPPER.readTree(schema).at("/properties/steps/items/properties");
            assertEquals(JsonUtil.MAPPER.valueToTree(mode.allowedActions()),step.at("/action/enum"));
            if(mode!=CURRENT_DATA)assertEquals("null",step.at("/query/type").asText());
            if(mode==KNOWLEDGE_ONLY){for(String field:List.of("source","other","analysis","rank"))assertEquals("null",step.at("/"+field+"/type").asText());assertEquals("string",step.at("/knowledgeQuestion/type").asText());}
        }
        assertEquals(3,schemas.size());
        var frozen=(Map<?,?>)AnalysisModelUsageTest.engine(null).configuration().get("planSchemas");
        assertEquals(Set.of("KNOWLEDGE_ONLY","CURRENT_DATA","SNAPSHOT_ONLY"),frozen.keySet());
        for(var mode:AnalysisContract.Mode.values())assertEquals(AnalysisContract.schemaHash(AnalysisContract.planSchema(mode)),frozen.get(mode.name()));
    }

    @Test void executionRechecksModeBeforeTouchingCurrentBusinessData() {
        var assistant=mock(BusinessAssistantService.class);var workspace=mock(QueryWorkspaceService.class);
        var engine=new AnalysisEngine(null,null,null,assistant,workspace,null);
        for(var request:List.of(request(KNOWLEDGE_ONLY,null),request(SNAPSHOT_ONLY,SNAPSHOT))) {
            var session=new AnalysisEngine.Session("run","lease",TestCatalog.USER1,request,KnowledgeRagTest.snapshot(List.of()),()->{});
            var failure=assertThrows(ApiException.class,()->ReflectionTestUtils.invokeMethod(engine,"execute",session,query("A"),List.of(),new LinkedHashMap<>(),0));
            assertTrue(failure.getMessage().contains("不允许执行QUERY"));
        }
        verifyNoInteractions(assistant,workspace);
    }

    @Test void detailRecoveryRejectsForbiddenGraphsBeforeReadingSnapshots() {
        var workspace=mock(QueryWorkspaceService.class);var engine=new AnalysisEngine(null,null,null,null,workspace,null);
        var steps=List.<Map<String,Object>>of(Map.of("status","COMPLETED","result",Map.of("snapshotId",SNAPSHOT)));
        for(var request:List.of(request(KNOWLEDGE_ONLY,null),request(SNAPSHOT_ONLY,SNAPSHOT)))
            assertThrows(ApiException.class,()->engine.page(TestCatalog.USER1,request,plan(query("A")),steps,0,1));
        verifyNoInteractions(workspace);
    }

    @Test void restoredSnapshotMustMatchTheOriginalBindingButQuerySnapshotsRemainUsable() {
        var workspace=mock(QueryWorkspaceService.class);var engine=new AnalysisEngine(null,null,null,null,workspace,null);
        var steps=List.<Map<String,Object>>of(Map.of("status","COMPLETED","result",Map.of("snapshotId",OTHER)));
        assertEquals(409,assertThrows(ApiException.class,()->engine.page(TestCatalog.USER1,request(SNAPSHOT_ONLY,SNAPSHOT),plan(snapshot()),steps,0,1)).getCode());
        verifyNoInteractions(workspace);
        when(workspace.require(TestCatalog.USER1,OTHER)).thenThrow(new ApiException(410,"当前数据查询生成的快照已到期"));
        assertEquals(410,assertThrows(ApiException.class,()->ReflectionTestUtils.invokeMethod(engine,"requireBase",TestCatalog.USER1,request(CURRENT_DATA,null),query("A"),OTHER)).getCode());
        verify(workspace).require(TestCatalog.USER1,OTHER);
    }
}
