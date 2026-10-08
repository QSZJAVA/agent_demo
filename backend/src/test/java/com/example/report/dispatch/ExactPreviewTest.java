package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.support.DispatchHarness;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.example.report.support.DispatchHarness.candidate;
import static com.example.report.support.TestCatalog.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 精确记录预览复用原状态机：完整准备、版本及租约保护不能因为查询对象直达清单而被绕过。 */
class ExactPreviewTest {
    private DispatchHarness harness(){return new DispatchHarness().put(SALES,candidate(SALES,"1","D1","A","服务器"),candidate(SALES,"2","D2","A","交换机"));}
    private List<RecordTarget> target(String id){return List.of(new RecordTarget(new RecordKey(SALES,id),"A"));}
    @Test void exactPreviewCreatesOnlyTheRequestedPendingItemsAndRetainsItsTargetBoundary() {
        var h=harness();String id="exact";
        var snapshot=h.previews.previewRecords(USER1,id,target("2"),"query-source",n->{},p->{},h.previews.beginRequest(id)).snapshot();
        assertEquals(List.of("2"),snapshot.candidates().stream().map(c->c.recordId()).toList());assertEquals(target("2"),snapshot.targets());
        var plan=h.plans.create(USER1,id,snapshot.preview().getId(),List.of(),"task-one");
        assertEquals("PENDING",plan.plan().getStatus());assertEquals(List.of("2"),plan.items().stream().map(i->i.getRecordId()).toList());
        assertEquals(plan.plan().getId(),h.plans.create(USER1,id,snapshot.preview().getId(),List.of(),"task-one").plan().getId());
    }
    @Test void incompleteTargetSetCannotSupersedeOriginalPreviewOrPlan() {
        var h=harness();String id="preserve";
        var old=h.previews.preview(USER1,id,new PreviewCommand(null,"api",null,List.of(SALES),null,null)).snapshot();
        var plan=h.plans.create(USER1,id,old.preview().getId(),List.of(),"original");
        assertThrows(ApiException.class,()->h.previews.previewRecords(USER1,id,target("missing"),"query-source",n->{},p->{},h.previews.beginRequest(id)));
        assertEquals("ACTIVE",h.store.previews().find(old.preview().getId()).orElseThrow().getStatus());
        assertEquals(List.of(plan.plan().getId()),h.store.plans().pending(id).stream().map(p->p.getId()).toList());
    }
    @Test void laterRequestOrLeaseLossBlocksActivation() {
        var h=harness();String id="version";long earlier=h.previews.beginRequest(id);h.previews.beginRequest(id);
        assertThrows(ApiException.class,()->h.previews.previewRecords(USER1,id,target("1"),"query-source",n->{},p->{},earlier));
        assertTrue(h.previews.latest(USER1,id).isEmpty());
        assertThrows(ApiException.class,()->h.previews.previewRecords(USER1,id,target("1"),"query-source",n->{},p->{throw new ApiException(409,"租约已失效");},h.previews.beginRequest(id)));
        assertTrue(h.previews.latest(USER1,id).isEmpty());
    }
    @Test void ruleChangeDuringTargetReadCannotPublishASnapshotWithNewStampAndOldQualification() {
        var h=harness();
        when(h.candidates.findBoundCandidates(anyString(),anySet(),anyList(),anyList(),any(java.util.function.IntConsumer.class))).thenAnswer(call->{
            h.ruleVersion.set("rules-v2");return List.of(candidate(SALES,"1","D1","A","服务器"));
        });
        assertThrows(ApiException.class,()->h.previews.previewRecords(USER1,"rules",target("1"),"query-source",n->{},p->{},h.previews.beginRequest("rules")));
        assertTrue(h.previews.latest(USER1,"rules").isEmpty());
    }
}
