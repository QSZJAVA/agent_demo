package com.example.report.investigation;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.report.investigation.InvestigationTestSupport.*;

/** 长日志压缩与上下文隔离回归；直接构造确定性轨迹，不用模型输出替代事实。 */
class InvestigationContextTest {
    List<Message> history() {
        var messages=new ArrayList<Message>();messages.add(new SystemMessage("不得执行写入"));messages.add(new UserMessage("原问题"));
        for(int n=1;n<=4;n++) {
            messages.add(AssistantMessage.builder().content("").toolCalls(List.of(InvestigationAgentTest.tool("call"+n,"execution_events","{}"))).build());
            messages.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("call"+n,"investigation_execution_events","长日志".repeat(3000)))).build());
        }return messages;
    }
    @Test void compactionKeepsSystemQuestionLatestPairAndBothConflictingObservations() {
        var s=session(List.of(item("I1","UNKNOWN",null)),new InvestigationProperties());
        for(String status:List.of("UNKNOWN","SUCCESS")) s.evidence.put("E"+(s.evidence.size()+1),new InvestigationEvidenceStore.Evidence("MCP_LOOKUP",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","status",status))),false));
        var messages=history();int before=InvestigationContext.bytes(messages);
        assertTrue(InvestigationContext.compact(messages,s,12000));assertTrue(InvestigationContext.bytes(messages)<before/2);
        assertEquals("不得执行写入",messages.get(0).getText());assertEquals("原问题",messages.get(1).getText());
        assertTrue(messages.get(2).getText().contains("SUCCESS"));assertTrue(messages.get(2).getText().contains("UNKNOWN"));
        assertEquals("call4",((AssistantMessage)messages.get(3)).getToolCalls().get(0).id());
        assertEquals("call4",((ToolResponseMessage)messages.get(4)).getResponses().get(0).id());
    }
    @Test void shortContextAndUnpairedCallsAreNotCompacted() {
        var s=session(List.of(item("I1","UNKNOWN",null)),new InvestigationProperties());var messages=history();
        assertFalse(InvestigationContext.compact(messages,s,Integer.MAX_VALUE));messages.remove(messages.size()-1);
        assertFalse(InvestigationContext.compact(messages,s,100));
    }
    @Test void newRunDoesNotInheritEvidenceNotesOrLookupMemory() {
        var props=new InvestigationProperties();var first=session(List.of(item("I1","UNKNOWN",null)),props);first.lookupAttempts.put("I1",2);
        var next=session(List.of(item("I1","UNKNOWN",null)),props);assertTrue(next.lookupAttempts.isEmpty());assertTrue(InvestigationContext.project(next).isEmpty());
    }
    @Test void projectedRuleDetailsRemainAvailableViaEvidenceReadAndKeepMissingFlag() {
        var props=new InvestigationProperties();var repo=repository();var tools=tools(repo,props,Map.of());var row=item("I1","SKIPPED","RULE");row.put("rule",null);
        var s=session(List.of(row),props);tools.execute(s,"investigation_rule_snapshots",com.example.report.common.JsonUtil.MAPPER.valueToTree(Map.of("itemRefs",List.of("I1"))));
        assertTrue(com.example.report.common.JsonUtil.toJson(InvestigationContext.project(s)).contains("\"missing\":true"));
        var read=tools.execute(s,"investigation_evidence_read",com.example.report.common.JsonUtil.MAPPER.valueToTree(Map.of("evidenceId","E1")));
        assertEquals(s.evidence.get("E1").data(),read.get("data"));assertEquals(1,s.evidence.size());
    }
    @Test @SuppressWarnings("unchecked") void orderedIndexAndReadResponseDistinguishEventFromRuleEvidence() {
        var props=new InvestigationProperties();props.setMaxToolResultUtf8Bytes(512);
        var s=session(List.of(item("I1","UNKNOWN",null)),props);var tools=tools(repository(),props,Map.of());
        tools.execute(s,"investigation_rule_snapshots",com.example.report.common.JsonUtil.MAPPER.valueToTree(Map.of("itemRefs",List.of("I1"))));
        tools.execute(s,"investigation_execution_events",com.example.report.common.JsonUtil.MAPPER.valueToTree(Map.of("itemRefs",List.of("I1"),"size",1)));
        var index=(List<Map<String,Object>>)InvestigationContext.notes(s).get("availableEvidence");
        assertEquals(List.of("E1","E2"),index.stream().map(e -> e.get("evidenceId")).toList());
        assertEquals(List.of("RULE_SNAPSHOT","EXECUTION_EVENT"),index.stream().map(e -> e.get("type")).toList());
        for(var entry:index) {
            var result=tools.execute(s,"investigation_evidence_read",com.example.report.common.JsonUtil.MAPPER.valueToTree(Map.of("evidenceId",entry.get("evidenceId"))));
            assertEquals(entry.get("type"),result.get("sourceType"));assertTrue(com.example.report.common.JsonUtil.toJson(result).getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=512);
        }
        assertEquals(2,s.evidence.size());
    }
    @Test void actualLoopPersistsCompactionAndFinalReportStillUsesOriginalStatusEvidence() {
        var props=new InvestigationProperties();props.setContextTargetUtf8Bytes(1024);var repo=repository();var row=item("I1","UNKNOWN",null);
        var events=new ArrayList<Map<String,Object>>();for(int i=0;i<10;i++) events.add(Map.of("eventId","event"+i,"outcome","UNKNOWN","attemptCount",1,"message","日志说明".repeat(60)));
        row.put("events",events);row.put("eventCount",10L);
        var s=session(List.of(row),props);var model=org.mockito.Mockito.mock(InvestigationModel.class);var round=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyBoolean(),org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            int n=round.incrementAndGet();
            if(n==1) return InvestigationAgentTest.reply("",List.of(InvestigationAgentTest.tool("items","plan_items","{\"size\":10}")));
            if(n==2) return InvestigationAgentTest.reply("",List.of(InvestigationAgentTest.tool("events","execution_events","{\"size\":10,\"itemRefs\":[\"I1\"]}")));
            if(n==3) return InvestigationAgentTest.reply("",List.of(InvestigationAgentTest.tool("rules","rule_snapshots","{\"itemRefs\":[\"I1\"]}")));
            List<Message> messages=call.getArgument(0);
            if(n==4) {assertTrue(messages.stream().anyMatch(m -> Objects.toString(m.getText(),"").contains("PROGRAM_EVIDENCE_NOTES")));return InvestigationAgentTest.reply("已收集",List.of());}
            assertEquals(true,call.getArgument(2));assertTrue(messages.get(1).getText().contains("E1"));
            return InvestigationAgentTest.reply(report("I1","RESULT_UNKNOWN","VERIFIED",List.of("E1")),List.of());
        });
        assertNotNull(new InvestigationAgent(model,tools(repo,props,Map.of()),new InvestigationReportValidator(),repo,props).investigate(s,"分析"));
        org.mockito.Mockito.verify(repo).startStep(org.mockito.ArgumentMatchers.eq("run"),org.mockito.ArgumentMatchers.eq("token"),org.mockito.ArgumentMatchers.eq("CONTROL"),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.eq("CONTEXT_COMPACT"),org.mockito.ArgumentMatchers.any());
    }
}
