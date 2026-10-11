package com.example.report.dispatch;

import com.example.report.common.ApiException;
import com.example.report.dispatch.store.PlanRepository;
import com.example.report.dispatch.store.PreviewRepository;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPreview;
import com.example.report.permission.CurrentUser;
import com.example.report.support.DispatchHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.example.report.support.DispatchHarness.candidate;
import static com.example.report.support.TestCatalog.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 状态摘要与详细记录使用同一业务授权；终态、混合范围会话及权限恢复均通过真实状态服务验证。 */
class CardStateAuthorizationTest {
    private static final CurrentUser OWNER=new CurrentUser("T001","user1","owner",Set.of("A","B"),DEMO_PERMISSIONS,false);
    private final DispatchHarness h=new DispatchHarness()
            .put(SALES,candidate(SALES,"1","SO1","A","sales"))
            .put(EXPENSE,candidate(EXPENSE,"2","EX1","B","expense"));
    private final CardStateService cards=new CardStateService(h.previews,h.plans,h.store.previews(),h.store.plans());

    private PreviewSnapshot preview(String report,String company) {
        return h.previews.preview(OWNER,"conversation",new PreviewCommand(null,"api",null,List.of(report),
                new PreviewCommand.Filters(company),null)).snapshot();
    }

    static Stream<Arguments> revokedReadersAndStates() {
        return Stream.of(DispatchPlan.PENDING,DispatchPlan.EXECUTING,DispatchPlan.EXECUTED,
                DispatchPlan.CANCELLED,DispatchPlan.REVIEW_REQUIRED).flatMap(status->Stream.of(
                Arguments.of(new CurrentUser("T001","user1","reader",Set.of("A"),DEMO_PERMISSIONS,false),status),
                Arguments.of(new CurrentUser("T001","user1","reader",Set.of("A","B"),Set.of("report:sales"),false),status)));
    }

    @ParameterizedTest
    @MethodSource("revokedReadersAndStates")
    void everyPlanStateAndSourcePreviewRequireCurrentBusinessGrants(CurrentUser restricted,String status) {
        var source=preview(EXPENSE,"B");
        String planId=h.plans.create(OWNER,"conversation",source.preview().getId(),List.of(),null).plan().getId();
        h.store.updatePlan(planId,plan->{plan.setStatus(status);plan.setSuccessCount(1);plan.setFailedCount(2);});
        if(!DispatchPlan.PENDING.equals(status))h.store.updatePreview(source.preview().getId(),p->p.setStatus(DispatchPreview.CONSUMED));

        assertEquals(403,assertThrows(ApiException.class,()->cards.planStates(restricted,List.of(planId))).getCode());
        assertEquals(403,assertThrows(ApiException.class,()->cards.previewStates(restricted,List.of(source.preview().getId()))).getCode());
        assertEquals(status,h.store.plans().find(planId).orElseThrow().getStatus(),"无读取权限的请求不能顺便使清单失效");
        assertEquals(status,cards.planStates(OWNER,List.of(planId)).get(planId).status(),"恢复完整授权后仍读取原有状态");
    }

    @Test
    void wholeConversationRemainsHiddenWhenAnotherCardHasRevokedScope() {
        var first=preview(SALES,"A");
        String planId=h.plans.create(OWNER,"conversation",first.preview().getId(),List.of(),null).plan().getId();
        preview(EXPENSE,"B");
        var restricted=new CurrentUser("T001","user1","reader",Set.of("A"),DEMO_PERMISSIONS,false);
        assertNotNull(cards.planStates(restricted,List.of(planId)).get(planId),"单独的 A 公司清单仍在授权范围内");
        assertEquals(403,assertThrows(ApiException.class,()->cards.conversationStates(restricted,"conversation")).getCode());
    }

    @Test
    void businessQueryHistoryIsCheckedEvenWithoutAnyPreviewCards() {
        var previews=mock(PreviewService.class);
        var previewRows=mock(PreviewRepository.class);
        var planRows=mock(PlanRepository.class);
        doThrow(ApiException.notFound("会话权限已变化")).when(previews).requireConversationReadable(OWNER,"query-only");
        var states=new CardStateService(previews,mock(PlanService.class),previewRows,planRows);
        assertEquals(404,assertThrows(ApiException.class,()->states.conversationStates(OWNER,"query-only")).getCode());
        verifyNoInteractions(previewRows,planRows);
    }

    @Test
    void foreignCardIdentifiersNeverReturnStatusMetadata() {
        var source=preview(SALES,"A");
        String planId=h.plans.create(OWNER,"conversation",source.preview().getId(),List.of(),null).plan().getId();
        for(var reader:List.of(USER2,new CurrentUser("T002","user1","foreign",Set.of("A","B"),DEMO_PERMISSIONS,true))) {
            assertTrue(cards.previewStates(reader,List.of(source.preview().getId())).isEmpty());
            assertTrue(cards.planStates(reader,List.of(planId)).isEmpty());
        }
    }
}
