package com.example.report.investigation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.report.investigation.InvestigationTestSupport.*;

/** 任务语料分割和判定器自身的回归；错结论不能因文字流畅或合法JSON而通过。 */
class InvestigationEvaluationTest {
    @Test void corpusHasFortyUniqueCasesAndSeparateHoldout() throws Exception {
        var corpus=InvestigationEvaluation.corpus();assertEquals(40,corpus.size());assertEquals(40,corpus.stream().map(c -> c.get("caseId")).distinct().count());assertEquals(16,corpus.stream().filter(c -> "holdout".equals(c.get("split"))).count());
    }
    @Test void wrongOrUnobservedRemoteOutcomeCannotPassGrader() throws Exception {
        var test=InvestigationEvaluation.corpus().stream().filter(c -> "unknown_remote_success".equals(c.get("caseId"))).findFirst().orElseThrow();
        var s=session(List.of(item("I1","UNKNOWN","TRANSPORT_TIMEOUT")),new InvestigationProperties());
        var value=com.example.report.common.JsonUtil.toMap(report("I1","REMOTE_SUCCESS_LOCAL_UNRESOLVED","VERIFIED",List.of("E1")));
        assertFalse(InvestigationEvaluation.grade(test,value,s));
        s.evidence.put("E1",new InvestigationEvidenceStore.Evidence("MCP_LOOKUP",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","status","SUCCESS"))),false));assertFalse(InvestigationEvaluation.grade(test,value,s));
        s.evidence.put("E2",new InvestigationEvidenceStore.Evidence("ITEM_SNAPSHOT",List.of("I1"),Map.of("items",List.of(Map.of("itemRef","I1","status","UNKNOWN"))),false));
        value=new InvestigationReportValidator().validate(report("I1","REMOTE_SUCCESS_LOCAL_UNRESOLVED","VERIFIED",List.of("E1","E2")),"stop",s);assertTrue(InvestigationEvaluation.grade(test,value,s));
        value=com.example.report.common.JsonUtil.toMap(report("I1","RESULT_UNKNOWN","VERIFIED",List.of("E1")));assertFalse(InvestigationEvaluation.grade(test,value,s));
    }
    @Test @SuppressWarnings("unchecked") void falseSuccessFailureAndWriteClaimsCannotPassIndependentTextGrading() throws Exception {
        var task=InvestigationEvaluation.corpus().stream().filter(c -> "business_reject".equals(c.get("caseId"))).findFirst().orElseThrow();
        var s=session(List.of(item("I1","FAILED","BUSINESS_REJECTED")),new InvestigationProperties());
        s.evidence.put("E1",new InvestigationEvidenceStore.Evidence("ITEM_SNAPSHOT",List.of("I1"),Map.of("items",s.items),false));
        var value=new InvestigationReportValidator().validate(report("I1","BUSINESS_REJECTED","VERIFIED",List.of("E1")),"stop",s);
        assertTrue(InvestigationEvaluation.grade(task,value,s));
        for(String claim:List.of("派单已经成功，系统已经自动重新发送，无需核对","已修复业务错误并更新权限","失败即代表未发送，可以立即重发")) {
            for(String field:List.of("summary","explanation","message")) {
                var bad=com.example.report.common.JsonUtil.toMap(com.example.report.common.JsonUtil.toJson(value));
                if(field.equals("summary")) bad.put(field,claim);
                if(field.equals("explanation")) ((List<Map<String,Object>>)bad.get("findings")).get(0).put(field,claim);
                if(field.equals("message")) bad.put("unresolved",List.of(Map.of("itemRef","I1","topics",List.of("MANUAL_REVIEW"),"message",claim)));
                assertFalse(InvestigationEvaluation.grade(task,bad,s),field+": "+claim);
                var score=InvestigationReportGrader.assess(task,bad,s);
                assertTrue(((Number)score.get("supportedFacts")).intValue()<((Number)score.get("assertedFacts")).intValue());
            }
        }
        var missing=InvestigationReportGrader.assess(task,null,s);assertEquals(1,missing.get("expectedItems"));assertEquals(0,missing.get("correctItems"));
    }
    @Test void resumeRejectsChangedOrMissingExecutionControls() {
        var prior=new LinkedHashMap<String,Object>();
        for(String group:List.of("configurationControls","comparisonVariables")) for(String key:InvestigationEvaluation.controls(group)) prior.put(key,"same");
        assertDoesNotThrow(() -> InvestigationEvaluation.requireResumeConfiguration(prior,new LinkedHashMap<>(prior)));
        for(String field:prior.keySet()) {
            var changed=new LinkedHashMap<>(prior);changed.put(field,"different");
            assertTrue(assertThrows(IllegalArgumentException.class,() -> InvestigationEvaluation.requireResumeConfiguration(prior,changed)).getMessage().contains(field));
            changed.remove(field);assertThrows(IllegalArgumentException.class,() -> InvestigationEvaluation.requireResumeConfiguration(prior,changed));
            changed.put(field,null);assertThrows(IllegalArgumentException.class,() -> InvestigationEvaluation.requireResumeConfiguration(changed,changed));
        }
    }
    @Test @SuppressWarnings("unchecked") void archivePairsCorrectedArgumentsAndMultipleToolsWithOriginalCallIds() throws Exception {
        var task=InvestigationEvaluation.corpus().stream().filter(c -> "business_reject".equals(c.get("caseId"))).findFirst().orElseThrow();
        var model=org.mockito.Mockito.mock(InvestigationModel.class);
        org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyBoolean(),org.mockito.ArgumentMatchers.any())).thenReturn(
                InvestigationAgentTest.reply("",List.of(InvestigationAgentTest.tool("bad","plan_items","{\"size\":\"bad\",\"email\":\"test@example.com\"}"))),
                InvestigationAgentTest.reply("",List.of(InvestigationAgentTest.tool("corrected","plan_items","{\"size\":20}"),InvestigationAgentTest.tool("rules","rule_snapshots","{\"itemRefs\":[\"I1\"]}"))),
                InvestigationAgentTest.reply("完成",List.of()),InvestigationAgentTest.reply(report("I1","BUSINESS_REJECTED","VERIFIED",List.of("E1")),List.of()));
        var run=InvestigationEvaluation.run(task,model,new InvestigationProperties(),1);assertEquals(true,run.get("passed"));
        var all=(List<Map<String,Object>>)run.get("steps");var trace=all.stream().filter(t -> "TOOL".equals(t.get("kind"))).toList();
        assertEquals(List.of("bad","corrected","rules"),trace.stream().map(t -> t.get("toolCallId")).toList());
        assertEquals("INVALID_ARGUMENT",trace.get(0).get("error"));assertEquals("FAILED",trace.get(0).get("status"));
        assertEquals("{\"size\":20}",((Map<?,?>)trace.get(1).get("arguments")).get("arguments"));
        assertEquals("investigation_rule_snapshots",trace.get(2).get("toolName"));assertNotNull(trace.get(2).get("result"));
        assertFalse(com.example.report.common.JsonUtil.toJson(all).contains("test@example.com"));
        for(var step:all) {assertEquals(run.get("runId"),step.get("runId"));assertNotNull(step.get("startedAt"));assertNotNull(step.get("finishedAt"));}
    }
    @Test @SuppressWarnings("unchecked") void modelFailureAndUnfinishedToolKeepTheirStartedTrace() throws Exception {
        var task=InvestigationEvaluation.corpus().stream().filter(c -> "business_reject".equals(c.get("caseId"))).findFirst().orElseThrow();
        var model=org.mockito.Mockito.mock(InvestigationModel.class);
        org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyBoolean(),org.mockito.ArgumentMatchers.any())).thenThrow(new InvestigationFailure("MODEL_UNAVAILABLE","模型不可用"));
        var run=InvestigationEvaluation.run(task,model,new InvestigationProperties(),1);
        var steps=(List<Map<String,Object>>)run.get("steps");assertEquals(1,steps.size());assertEquals("MODEL",steps.get(0).get("kind"));assertEquals("FAILED",steps.get(0).get("status"));assertNotNull(steps.get(0).get("arguments"));
        var props=new InvestigationProperties();props.setMaxMcpCalls(0);
        org.mockito.Mockito.reset(model);
        org.mockito.Mockito.when(model.call(org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyBoolean(),org.mockito.ArgumentMatchers.any())).thenReturn(
                InvestigationAgentTest.reply("",List.of(InvestigationAgentTest.tool("lookup","dispatch_lookup","{\"itemRefs\":[\"I1\"]}"))),
                InvestigationAgentTest.reply(report("I1","UNDETERMINED","INSUFFICIENT",List.of()),List.of()));
        run=InvestigationEvaluation.run(task,model,props,1);steps=(List<Map<String,Object>>)run.get("steps");
        var pending=steps.stream().filter(t -> "TOOL".equals(t.get("kind"))).findFirst().orElseThrow();assertEquals("STARTED",pending.get("status"));assertEquals("lookup",pending.get("toolCallId"));assertFalse(pending.containsKey("finishedAt"));
    }
    /** 只复验同一当前格式的已保存真实输出，保留原失败记录；这不是重新执行模型或旧业务数据迁移。 */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="INVESTIGATION_ARCHIVE",matches=".+")
    @SuppressWarnings("unchecked")
    void savedRealReportsRemainValidUnderFinalValidator() throws Exception {
        var archive=java.nio.file.Path.of(System.getenv("INVESTIGATION_ARCHIVE"));var manifest=com.example.report.common.JsonUtil.toMap(java.nio.file.Files.readString(archive.resolve("manifest.json")));
        assertEquals(InvestigationEvaluation.GRADER_VERSION,((Number)manifest.get("graderVersion")).intValue());assertEquals(2,((Number)manifest.get("fixtureVersion")).intValue());
        var corpus=InvestigationEvaluation.corpus();int reports=0,originalFailures=0;
        for(String line:java.nio.file.Files.readAllLines(archive.resolve("runs.jsonl"))) if(!line.isBlank()) {
            var run=com.example.report.common.JsonUtil.toMap(line);if(!Boolean.TRUE.equals(run.get("passed"))) originalFailures++;
            if(run.get("report")==null) continue;
            var task=corpus.stream().filter(test -> Objects.equals(test.get("caseId"),run.get("caseId"))).findFirst().orElseThrow();var items=new ArrayList<Map<String,Object>>();int index=0;
            for(var value:(List<Map<String,Object>>)task.get("items")) items.add(item("I"+(++index),value.get("status").toString(),(String)value.get("errorCode")));
            var session=session(items,new InvestigationProperties());
            for(var entry:((Map<String,Map<String,Object>>)run.get("evidence")).entrySet()) {
                assertEquals(Set.of("type","refs","data","truncated"),entry.getValue().keySet());var raw=entry.getValue();
                session.evidence.put(entry.getKey(),new InvestigationEvidenceStore.Evidence(raw.get("type").toString(),(List<String>)raw.get("refs"),(Map<String,Object>)raw.get("data"),Boolean.TRUE.equals(raw.get("truncated"))));
            }
            var report=(Map<String,Object>)run.get("report");assertTrue(InvestigationEvaluation.grade(task,report,session),run.get("caseId").toString());reports++;
        }
        assertTrue(reports>0);var summary=Map.of("evidenceScope","SAVED_REAL_MODEL_REPORT_REVALIDATION_NO_NEW_MODEL_CALLS","validatedReports",reports,"originalFailedRunsPreserved",originalFailures,"newModelCalls",0,"currentArtifactHash",InvestigationEvaluation.artifactHash(java.nio.file.Path.of("target","classes")));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target","investigation-evaluation","revalidated-report-summary.json"),com.example.report.common.JsonUtil.toJson(summary));
    }
}
