package com.example.report.investigation;

import com.example.report.common.JsonUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 评估中断及并发检查点回归；只操作独立临时目录，不访问模型或业务库。 */
class InvestigationEvaluationJournalTest {
    @TempDir Path root;
    Map<String,Object> result(String name,int repeat,boolean passed) {return Map.of("caseId",name,"repeat",repeat,"passed",passed,"runId",UUID.randomUUID().toString());}
    /** 仅构造控制变量相同的合成归档，不发出真实模型请求。 */
    static Map<String,Object> resumeManifest() {
        var config=new LinkedHashMap<String,Object>();
        for(String group:List.of("configurationControls","comparisonVariables")) for(String key:InvestigationEvaluation.controls(group)) config.put(key,"same");
        return Map.of("configuration",config,"journalVersion",2,"graderVersion",3,"fixtureVersion",2,"corpusHash","same","evidenceScope","OFFLINE");
    }
    Path parentArchive(Set<String> planned) throws Exception {
        try(var journal=new InvestigationEvaluationJournal(root,resumeManifest(),planned,List.of())) {
            journal.start("one:1");journal.complete("one:1",result("one",1,false));return journal.directory;
        }
    }
    @Test void twoResumersCannotExecuteSameParentAndClosedParentCannotBranchAgain() throws Exception {
        var planned=Set.of("one:1","two:1");Path parent=parentArchive(planned),child;
        String original=Files.readString(parent.resolve("checkpoint.json"));
        try(var first=InvestigationEvaluationJournal.resume(root,parent,resumeManifest(),planned)) {
            child=first.directory;first.start("two:1");
            assertThrows(IllegalStateException.class,() -> InvestigationEvaluationJournal.resume(root,parent,resumeManifest(),planned));
            // 另一JVM也必须受操作系统文件锁约束，而不只是当前JVM的重叠锁检测。
            var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),InvestigationEvaluationJournalTest.class.getName(),root.toString(),parent.toString()).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            } finally {process.destroyForcibly();}
            first.complete("two:1",result("two",1,true));
        }
        assertTrue(assertThrows(IllegalStateException.class,() -> InvestigationEvaluationJournal.resume(root,parent,resumeManifest(),planned)).getMessage().contains(child.toAbsolutePath().toString()));
        assertEquals(original,Files.readString(parent.resolve("checkpoint.json")));
        assertEquals(2,InvestigationEvaluationJournal.readCompleted(child,planned).size());
    }
    /** 跨进程竞争探针：预期父归档已被第一位续跑者占用，若取得执行权则进程失败。 */
    public static void main(String[] args) throws Exception {
        try(var unexpected=InvestigationEvaluationJournal.resume(Path.of(args[0]),Path.of(args[1]),resumeManifest(),Set.of("one:1","two:1"))) {
            throw new AssertionError("第二个进程取得了相同父归档的执行权");
        } catch(IllegalStateException expected) {
            if(!expected.getMessage().contains("仍在运行")) throw expected;
        }
    }
    @Test void successorCanContinueOnlyUnstartedRunsAndPreservesOriginalFailures() throws Exception {
        var planned=Set.of("one:1","two:1","three:1");Path parent=parentArchive(planned),child;
        try(var first=InvestigationEvaluationJournal.resume(root,parent,resumeManifest(),planned)) {
            child=first.directory;first.start("two:1");first.complete("two:1",result("two",1,true));
        }
        try(var next=InvestigationEvaluationJournal.resume(root,child,resumeManifest(),planned)) {
            assertEquals(2,next.results().size());assertEquals(false,next.results().get(0).get("passed"));
            assertThrows(IllegalArgumentException.class,() -> next.start("two:1"));next.start("three:1");next.complete("three:1",result("three",1,true));
            assertEquals(3,next.results().size());
        }
    }
    @Test void interruptedSuccessorCannotBeBypassedUsingOriginalParent() throws Exception {
        var planned=Set.of("one:1","two:1");Path parent=parentArchive(planned),child;
        try(var first=InvestigationEvaluationJournal.resume(root,parent,resumeManifest(),planned)) {child=first.directory;first.start("two:1");}
        assertThrows(IllegalStateException.class,() -> InvestigationEvaluationJournal.resume(root,parent,resumeManifest(),planned));
        assertTrue(assertThrows(IllegalStateException.class,() -> InvestigationEvaluationJournal.resume(root,child,resumeManifest(),planned)).getMessage().contains("禁止自动重放"));
    }
    @Test void configurationRejectionReleasesSourceAndDoesNotConsumeParent() throws Exception {
        var planned=Set.of("one:1","two:1");Path parent=parentArchive(planned);var changed=new LinkedHashMap<>(resumeManifest());changed.put("corpusHash","different");
        assertThrows(IllegalArgumentException.class,() -> InvestigationEvaluationJournal.resume(root,parent,changed,planned));
        assertFalse(Files.exists(parent.resolve("continuation.json")));
        try(var accepted=InvestigationEvaluationJournal.resume(root,parent,resumeManifest(),planned)) {assertEquals(1,accepted.results().size());}
    }
    @Test @SuppressWarnings("unchecked") void resumePreservesExactConfiguredDecimalPrices() throws Exception {
        var manifest=resumeManifest();var prices=Map.of("inputPerMillion",new java.math.BigDecimal("0.1234567890123456789"));
        ((Map<String,Object>)manifest.get("configuration")).put("prices",prices);Path parent;
        try(var original=new InvestigationEvaluationJournal(root,manifest,Set.of("one:1"),List.of())) {parent=original.directory;}
        try(var resumed=InvestigationEvaluationJournal.resume(root,parent,manifest,Set.of("one:1"))) {resumed.start("one:1");}
    }
    @Test void configurationIsWrittenBeforeStartAndCompletedFailuresSurviveResume() throws Exception {
        Path archive;var failed=result("one",1,false);var manifest=Map.<String,Object>of("configuration",Map.of("model","actual","evaluationParallelism",2),"graderVersion",3);
        try(var journal=new InvestigationEvaluationJournal(root,manifest,Set.of("one:1","two:1"),List.of())) {
            archive=journal.directory;assertEquals(manifest.get("configuration"),JsonUtil.toMap(Files.readString(archive.resolve("manifest.json"))).get("configuration"));
            journal.start("one:1");journal.complete("one:1",failed);
            assertThrows(IllegalStateException.class,() -> InvestigationEvaluationJournal.readCompleted(archive,Set.of("one:1","two:1")));
        }
        assertEquals(List.of(failed),InvestigationEvaluationJournal.readCompleted(archive,Set.of("one:1","two:1")));
        assertThrows(IllegalStateException.class,() -> InvestigationEvaluationJournal.readCompleted(archive,Set.of("one:1")));
    }
    @Test void startedWithoutResultCannotBeSilentlyReplayed() throws Exception {
        Path archive;
        try(var journal=new InvestigationEvaluationJournal(root,Map.of(),Set.of("one:1"),List.of())) {archive=journal.directory;journal.start("one:1");}
        assertTrue(assertThrows(IllegalStateException.class,() -> InvestigationEvaluationJournal.readCompleted(archive,Set.of("one:1"))).getMessage().contains("禁止自动重放"));
    }
    @Test void concurrentCompletionKeepsBothRunsAndRejectsDuplicateCalls() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);Path archive;
        try(var journal=new InvestigationEvaluationJournal(root,Map.of(),Set.of("one:1","two:1"),List.of())) {
            archive=journal.directory;journal.start("one:1");journal.start("two:1");
            var a=pool.submit(() -> {journal.complete("one:1",result("one",1,true));return null;});
            var b=pool.submit(() -> {journal.complete("two:1",result("two",1,false));return null;});a.get();b.get();
            assertThrows(IllegalArgumentException.class,() -> journal.start("one:1"));
            assertThrows(IllegalArgumentException.class,() -> journal.complete("one:1",result("one",1,true)));
        } finally {pool.shutdownNow();}
        assertEquals(2,InvestigationEvaluationJournal.readCompleted(archive,Set.of("one:1","two:1")).size());
    }
    @Test void incompleteRepeatsAreNotAllPassedAndCheckpointPreservesDecimalCost() throws Exception {
        Path archive;var first=new LinkedHashMap<>(result("one",1,true));first.put("usage",Map.of("cost",new java.math.BigDecimal("0.1234567890123456789")));
        try(var journal=new InvestigationEvaluationJournal(root,Map.of(),Set.of("one:1","one:2"),List.of())) {
            archive=journal.directory;journal.start("one:1");journal.complete("one:1",first);assertEquals(0,journal.fullyPassedCases());
            journal.start("one:2");journal.complete("one:2",result("one",2,true));assertEquals(1,journal.fullyPassedCases());
        }
        var restored=InvestigationEvaluationJournal.readCompleted(archive,Set.of("one:1","one:2"));
        assertEquals(new java.math.BigDecimal("0.1234567890123456789"),((Map<?,?>)restored.get(0).get("usage")).get("cost"));
    }
    @Test void lateCompletionCannotWriteAfterJournalLockHasBeenReleased() throws Exception {
        var journal=new InvestigationEvaluationJournal(root,Map.of(),Set.of("one:1"),List.of());journal.start("one:1");journal.close();
        String before=Files.readString(journal.directory.resolve("checkpoint.json"));
        assertThrows(IllegalStateException.class,() -> journal.complete("one:1",result("one",1,true)));
        assertEquals(before,Files.readString(journal.directory.resolve("checkpoint.json")));
    }
}
