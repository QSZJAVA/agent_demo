package com.example.report.semantic;

import com.example.report.common.ApiException;
import com.example.report.dispatch.RecordKey;
import com.example.report.investigation.InvestigationJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 接收载荷和读取状态的无数据库回归；真正并发、事务和删除恢复由独立MySQL联合夹具验证。 */
class ChatTurnReceiptContractTest {
    private ChatTurnReceiptStore.Input input(String conversation,String message,String preview,List<RecordKey> excluded) {
        return new ChatTurnReceiptStore.Input(conversation,message,preview,excluded,null,null);
    }
    @Test void completeOriginalPayloadAndNullConversationParticipateInHash() {
        var baseline=input(null,"查询销售数据",null,List.of());String hash=InvestigationJson.hash(baseline);
        assertEquals(hash,InvestigationJson.hash(input(null,"查询销售数据",null,List.of())));
        assertNotEquals(hash,InvestigationJson.hash(input("server-created-id","查询销售数据",null,List.of())));
        assertNotEquals(hash,InvestigationJson.hash(input(null,"查询销售数据 ",null,List.of())));
        assertNotEquals(hash,InvestigationJson.hash(input(null,"查询销售数据","different-preview",List.of())));
        assertNotEquals(hash,InvestigationJson.hash(input(null,"查询销售数据",null,null)));
        assertNotEquals(hash,InvestigationJson.hash(input(null,"查询销售数据",null,List.of(new RecordKey("rpt-sales-order","one")))));
    }
    @Test void inputCopiesExclusionsWithoutTruncationOrSelectionLoss() {
        var values=new ArrayList<RecordKey>();for(int i=0;i<5000;i++)values.add(new RecordKey("rpt-sales-order","row-"+i));
        var accepted=input(null,"排除已选择项","preview",values);values.clear();assertEquals(5000,accepted.excludedRecords().size());
        assertThrows(UnsupportedOperationException.class,()->accepted.excludedRecords().clear());
    }
    @Test void invalidOrOversizedInputIsRejectedBeforeReceiptCreation() {
        assertThrows(ApiException.class,()->input(null," ",null,null));assertThrows(ApiException.class,()->input(null,"问".repeat(2001),null,null));
        assertThrows(ApiException.class,()->input(null,"查询",null,Collections.nCopies(5001,new RecordKey("rpt-sales-order","row"))));
        assertThrows(ApiException.class,()->input(null,"查询",null,Arrays.asList((RecordKey)null)));
    }
    @Test void expiredPlanningIsOnlyDerivedAsInterruptedAndOriginalRecordIsUnchanged() {
        var row=new HashMap<String,Object>();row.put("status","PLANNING");row.put("processing_until",java.time.LocalDateTime.ofInstant(Instant.now().minusSeconds(1),java.time.ZoneOffset.UTC));
        assertEquals("INTERRUPTED",ChatTurnReceiptStore.status(row));assertEquals("PLANNING",row.get("status"));
        row.put("processing_until",java.time.LocalDateTime.ofInstant(Instant.now().plusSeconds(60),java.time.ZoneOffset.UTC));assertEquals("PLANNING",ChatTurnReceiptStore.status(row));
        row.remove("processing_until");assertEquals("INTERRUPTED",ChatTurnReceiptStore.status(row));
    }
    @ParameterizedTest @ValueSource(strings={"RECEIVED","READY","SUBMITTED","COMPLETED","INTERRUPTED","FAILED"})
    void stableStateCannotBeReclassifiedByOldProcessingDeadline(String status) {
        assertEquals(status,ChatTurnReceiptStore.status(Map.of("status",status,"processing_until",java.time.LocalDateTime.ofInstant(Instant.now().minusSeconds(1),java.time.ZoneOffset.UTC))));
    }
}
