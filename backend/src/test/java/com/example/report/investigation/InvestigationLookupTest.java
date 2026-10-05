package com.example.report.investigation;

import com.example.report.common.*;
import com.example.report.dispatch.DispatchGateway.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.example.report.investigation.InvestigationTestSupport.*;

/** 批量只读核对的反例回归；参数纠正不得消耗其他条目机会，中断也不得丢弃已取得的事实。 */
class InvestigationLookupTest {
    @Test void terminalItemAtEndRejectsWholeBatchBeforeReadingNewItem() throws Exception {
        for(var status:List.of(LookupStatus.SUCCESS,LookupStatus.FAILED,LookupStatus.NOT_FOUND)) {
            var props=new InvestigationProperties();var repo=repository();
            var tools=tools(repo,props,Map.of("req-I1",new Lookup(status,null,"已核实"),"req-I2",new Lookup(LookupStatus.SUCCESS,null,"已核实")));
            var s=session(List.of(item("I1","UNKNOWN",null),item("I2","UNKNOWN",null)),props);
            tools.execute(s,"investigation_dispatch_lookup",JsonUtil.MAPPER.readTree("{\"itemRefs\":[\"I1\"]}"));
            assertThrows(ApiException.class,() -> tools.execute(s,"investigation_dispatch_lookup",JsonUtil.MAPPER.readTree("{\"itemRefs\":[\"I2\",\"I1\"]}")));
            assertFalse(s.lookupAttempts.containsKey("I2"));verify(repo,times(1)).countMcp(anyString(),anyString());
            var corrected=tools.execute(s,"investigation_dispatch_lookup",JsonUtil.MAPPER.readTree("{\"itemRefs\":[\"I2\"]}"));
            assertTrue(JsonUtil.toJson(corrected).contains("SUCCESS"));assertTrue(s.evidence.values().stream().anyMatch(e -> e.refs().equals(List.of("I2"))));
            verify(repo,times(2)).countMcp(anyString(),anyString());
        }
    }
    @Test void exhaustedUnknownAndDuplicateOrOutOfScopeRefsHaveNoBatchSideEffects() throws Exception {
        var props=new InvestigationProperties();var repo=repository();var tools=tools(repo,props,Map.of());
        var s=session(List.of(item("I1","UNKNOWN",null),item("I2","UNKNOWN",null)),props);
        for(int n=0;n<2;n++) tools.execute(s,"investigation_dispatch_lookup",JsonUtil.MAPPER.readTree("{\"itemRefs\":[\"I1\"]}"));
        for(String args:List.of("{\"itemRefs\":[\"I2\",\"I1\"]}","{\"itemRefs\":[\"I2\",\"I2\"]}","{\"itemRefs\":[\"I2\",\"I99\"]}"))
            assertThrows(ApiException.class,() -> tools.execute(s,"investigation_dispatch_lookup",JsonUtil.MAPPER.readTree(args)));
        assertFalse(s.lookupAttempts.containsKey("I2"));verify(repo,times(2)).countMcp(anyString(),anyString());
    }
    @Test void budgetStopKeepsEarlierSuccessAndDoesNotCountUnqueriedItem() throws Exception {
        var props=new InvestigationProperties();props.setMaxMcpCalls(1);var repo=repository();
        var tools=tools(repo,props,Map.of("req-I1",new Lookup(LookupStatus.SUCCESS,null,"已核实")));
        var s=session(List.of(item("I1","UNKNOWN",null),item("I2","UNKNOWN",null)),props);
        assertEquals("BUDGET_EXHAUSTED",assertThrows(InvestigationFailure.class,() -> tools.execute(s,"investigation_dispatch_lookup",JsonUtil.MAPPER.readTree("{\"itemRefs\":[\"I1\",\"I2\"]}"))).reason());
        assertEquals(1,s.evidence.size());assertTrue(JsonUtil.toJson(s.evidence).contains("SUCCESS"));assertFalse(s.lookupAttempts.containsKey("I2"));
        verify(repo,times(1)).countMcp(anyString(),anyString());
    }
    @Test void missingRequestDoesNotConsumeRemoteBudgetAndBothRowsHaveReferences() throws Exception {
        var props=new InvestigationProperties();var repo=repository();var missing=item("I1","UNKNOWN",null);missing.put("requestId",null);
        var s=session(List.of(missing,item("I2","UNKNOWN",null)),props);
        var result=tools(repo,props,Map.of()).execute(s,"investigation_dispatch_lookup",JsonUtil.MAPPER.readTree("{\"itemRefs\":[\"I1\",\"I2\"]}"));
        assertEquals(2,((List<?>)result.get("evidenceIds")).size());assertEquals(2,s.evidence.size());verify(repo,times(1)).countMcp(anyString(),anyString());
    }
    @Test void smallResponseBudgetKeepsAllStatusesAndReturnsBoundedEvidenceReceipt() throws Exception {
        var props=new InvestigationProperties();props.setMaxToolResultUtf8Bytes(512);props.validate();var repo=repository();
        var items=new ArrayList<Map<String,Object>>();var results=new HashMap<String,Lookup>();
        for(int i=1;i<=5;i++) {items.add(item("I"+i,"UNKNOWN",null));results.put("req-I"+i,new Lookup(LookupStatus.SUCCESS,"中".repeat(200),"中".repeat(200)));}
        var s=session(items,props);var result=tools(repo,props,results).execute(s,"investigation_dispatch_lookup",JsonUtil.MAPPER.readTree("{\"itemRefs\":[\"I1\",\"I2\",\"I3\",\"I4\",\"I5\"]}"));
        assertTrue(JsonUtil.toJson(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=512);assertEquals(5,s.evidence.size());
        for(var evidence:s.evidence.values()) {assertTrue(JsonUtil.toJson(evidence.data()).contains("SUCCESS"));assertTrue(JsonUtil.toJson(evidence.data()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=256);}
    }
}
