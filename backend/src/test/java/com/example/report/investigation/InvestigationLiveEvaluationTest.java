package com.example.report.investigation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 显式启用的真实模型调查评估；事实和工具查询为合成夹具，另以HTTP MCP集成证明业务边界。 */
@EnabledIfEnvironmentVariable(named="INVESTIGATION_LIVE",matches="true")
class InvestigationLiveEvaluationTest {
    @Test void independentTaskEvaluation() throws Exception {
        var props=new InvestigationProperties();props.setModel(System.getenv("INVESTIGATION_MODEL"));props.setNativeSchema(Boolean.parseBoolean(System.getenv("INVESTIGATION_NATIVE_SCHEMA")));props.setThinkingEnabled(Boolean.parseBoolean(System.getenv("INVESTIGATION_THINKING_ENABLED")));
        var model=new InvestigationOpenAiModel(props,System.getenv().getOrDefault("LLM_BASE_URL","https://dashscope.aliyuncs.com/compatible-mode"),System.getenv("LLM_API_KEY"),System.getenv().getOrDefault("LLM_MODEL","deepseek-v4.1-flash"),"/v1/chat/completions");
        var configuration=new LinkedHashMap<>(model.configuration());configuration.put("backendArtifactHash",InvestigationEvaluation.artifactHash(Path.of("target","classes")));configuration.put("evaluationHarnessHash",InvestigationEvaluation.artifactHash(Path.of("target","test-classes")));
        String split=System.getenv().getOrDefault("INVESTIGATION_SPLIT","holdout"),filter=System.getenv().getOrDefault("INVESTIGATION_CASE_FILTER","");
        int repeats=Integer.parseInt(System.getenv().getOrDefault("INVESTIGATION_REPEATS","3"));assertTrue(repeats>=1 && repeats<=10);
        var cases=InvestigationEvaluation.corpus().stream().filter(c -> "all".equals(split) || split.equals(c.get("split"))).filter(c -> filter.isEmpty() || c.get("caseId").toString().matches(filter)).toList();assertFalse(cases.isEmpty());
        int parallelism=Integer.parseInt(System.getenv().getOrDefault("INVESTIGATION_PARALLELISM","2"));assertTrue(parallelism>=1 && parallelism<=2);
        configuration.put("evaluationParallelism",parallelism);configuration.put("evaluationMinBatchIntervalSeconds",parallelism==2?21:11);
        var planned=new HashSet<String>();for(var test:cases) for(int repeat=1;repeat<=repeats;repeat++) planned.add(test.get("caseId")+":"+repeat);
        var runs=new ArrayList<Map<String,Object>>();var done=new HashSet<String>();String resume=System.getenv().getOrDefault("INVESTIGATION_RESUME","");
        var root=Path.of("target","investigation-evaluation");
        var manifest=InvestigationEvaluation.manifest(configuration);
        // 续跑入口负责在读取父归档前加锁，并在调用前登记唯一后继；不能先读检查点再独立执行。
        try(var journal=resume.isBlank()?new InvestigationEvaluationJournal(root,manifest,planned,List.of()):InvestigationEvaluationJournal.resume(root,Path.of(resume),manifest,planned)) {
        for(var run:journal.results()) {assertTrue(done.add(run.get("caseId")+":"+run.get("repeat")));runs.add(run);}
        System.out.println("Investigation archive: "+journal.directory.toAbsolutePath());
        var tasks=new ArrayList<java.util.concurrent.Callable<Map<String,Object>>>();for(var test:cases) for(int repeat=1;repeat<=repeats;repeat++) {int attempt=repeat;String key=test.get("caseId")+":"+attempt;if(!done.contains(key)) tasks.add(() -> {
            journal.start(key);var result=InvestigationEvaluation.run(test,model,props,attempt);journal.complete(key,result);return result;
        });}
        // 恢复只补原批次未执行项，失败记录保留；禁止用新成功结果替换已执行失败。
        boolean blocked=false;var pool=java.util.concurrent.Executors.newFixedThreadPool(parallelism);
        try {
            // 合成事实评估也不无限并发：最多2个运行，批次起点间隔使单账号任务速率低于6/分钟。
            long priorStart=0,interval=java.util.concurrent.TimeUnit.SECONDS.toNanos(parallelism==2?21:11);
            for(int offset=0;offset<tasks.size() && !blocked;offset+=parallelism) {
                long delay=priorStart+interval-System.nanoTime();if(priorStart>0 && delay>0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(delay);priorStart=System.nanoTime();
                var futures=pool.invokeAll(tasks.subList(offset,Math.min(tasks.size(),offset+parallelism)));
                for(var future:futures) {var run=future.get();System.out.println("Investigation "+run.get("caseId")+" repeat="+run.get("repeat")+" passed="+run.get("passed"));if("MODEL_UNAVAILABLE".equals(run.get("error"))) blocked=true;}
            }
        } finally {pool.shutdownNow();}
        runs=new ArrayList<>(journal.results());
        InvestigationEvaluation.write(root,journal,runs,cases.size()*repeats,blocked);
        assertFalse(blocked,"真实模型配置或端点阻断，详见评估报告");
        long passed=runs.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();assertTrue((double)passed/runs.size()>=.9,"任务完成率低于90%，详见target/investigation-evaluation");
        }
    }
}
