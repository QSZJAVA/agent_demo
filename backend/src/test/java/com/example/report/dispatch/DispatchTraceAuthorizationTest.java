package com.example.report.dispatch;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.example.report.common.ApiException;
import com.example.report.conversation.ConversationService;
import com.example.report.conversation.TraceMessage;
import com.example.report.entity.DispatchRule;
import com.example.report.mapper.DispatchAuditMapper;
import com.example.report.mapper.DispatchRuleMapper;
import com.example.report.permission.CurrentUser;
import com.example.report.support.DispatchHarness;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.example.report.support.DispatchHarness.candidate;
import static com.example.report.support.TestCatalog.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispatchTraceAuthorizationTest {
    private static final CurrentUser OWNER = new CurrentUser("T001", "user1", "owner",
            Set.of("A", "B"), DEMO_PERMISSIONS, false);
    private final DispatchHarness h = new DispatchHarness()
            .put(SALES, candidate(SALES, "1", "SO1", "A", "sales"))
            .put(EXPENSE, candidate(EXPENSE, "2", "EX1", "B", "expense"));
    private final ConversationService conversations = mock(ConversationService.class);
    private final DispatchAuditMapper audits = mock(DispatchAuditMapper.class);
    private final DispatchRuleMapper rules = mock(DispatchRuleMapper.class);
    private final DispatchTraceService trace = new DispatchTraceService(h.store.plans(), h.store.previews(),
            audits, rules, conversations, h.previews);

    @BeforeAll
    static void initMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "trace-test"), DispatchRule.class);
    }

    private String mixedConversation() {
        var sales = h.previews.preview(OWNER, "c1", command(SALES, "A")).snapshot();
        String id = h.plans.create(OWNER, "c1", sales.preview().getId(), List.of(), null).plan().getId();
        h.previews.preview(OWNER, "c1", command(EXPENSE, "B"));
        return id;
    }

    private static PreviewCommand command(String reportId, String company) {
        return new PreviewCommand(null, "api", null, List.of(reportId), new PreviewCommand.Filters(company), null, null);
    }

    static Stream<CurrentUser> restrictedReaders() {
        return Stream.of(false, true).flatMap(admin -> Stream.of(
                new CurrentUser("T001", admin ? "admin" : "user1", "reader", Set.of("A", "B"), Set.of("report:sales"), admin),
                new CurrentUser("T001", admin ? "admin" : "user1", "reader", Set.of("A"), DEMO_PERMISSIONS, admin)));
    }

    @ParameterizedTest
    @MethodSource("restrictedReaders")
    void refusesMixedConversationWhenAnotherReportOrCompanyIsRevoked(CurrentUser reader) {
        String id = mixedConversation();
        var plan = h.store.plans().find(id).orElseThrow();
        assertDoesNotThrow(() -> h.previews.requireReadable(reader, h.store.previews().find(plan.getPreviewId()).orElseThrow()));
        assertEquals(403, assertThrows(ApiException.class, () -> trace.trace(reader, id)).getCode());
        verifyNoInteractions(conversations, audits, rules);
    }

    static Stream<CurrentUser> authorizedReaders() { return Stream.of(OWNER, ADMIN); }

    @ParameterizedTest
    @MethodSource("authorizedReaders")
    void ownerAndFullScopeAdminCanStillReadTheTrace(CurrentUser reader) {
        String id = mixedConversation();
        var message = new TraceMessage(1L, "assistant", null, "authorized history", null, null, null, LocalDateTime.now());
        when(conversations.traceMessages(eq("c1"), any(), any())).thenReturn(List.of(message));
        assertEquals(List.of(message), trace.trace(reader, id).get("messages"));
    }

    @Test
    void otherUserAndForeignTenantAdminCannotReadThePlan() {
        String id = mixedConversation();
        for (CurrentUser reader : List.of(USER2,
                new CurrentUser("T002", "admin", "admin", Set.of("A", "B"), Set.of(CurrentUser.ALL), true))) {
            assertEquals(404, assertThrows(ApiException.class, () -> trace.trace(reader, id)).getCode());
        }
        verifyNoInteractions(conversations, audits, rules);
    }
}
