package com.example.report.investigation;

import com.example.report.common.*;
import com.example.report.dispatch.DispatchGateway.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.example.report.investigation.InvestigationTestSupport.*;

/** 当前格式任务级评估；真实模型使用合成只读事实，判断条目结论和必要证据，不规定唯一工具顺序。 */
final class InvestigationEvaluation {
    static final int GRADER_VERSION=3;
    /** 与Node对比工具共享控制变量契约；评估变更项允许对比，但同一次续跑也必须保持一致。 */
    static List<String> controls(String group) {
        try(var in=InvestigationEvaluation.class.getResourceAsStream("/investigation/evaluation-controls.json")) {
            var values=JsonUtil.MAPPER.readTree(in).get(group);var result=new ArrayList<String>();values.forEach(v -> result.add(v.asText()));return result;
        } catch(Exception failure) {throw new IllegalStateException("评估控制变量契约不可读",failure);}
    }
    /** 父锁内检查最新归档版本、语料、判定器和事实范围，缺失字段不允许猜测或补默认值。 */
    @SuppressWarnings("unchecked")
    static void requireResumeManifest(Map<String,Object> prior,Map<String,Object> current) {
        requireSame(prior,current,controls("manifestControls"));
        requireResumeConfiguration((Map<String,Object>)prior.get("configuration"),(Map<String,Object>)current.get("configuration"));
    }
    /** 续跑只接受同一批次控制变量；缺字段也拒绝，防止历史结果被重标为新配置。 */
    static void requireResumeConfiguration(Map<String,Object> prior,Map<String,Object> current) {
        var fields=new ArrayList<>(controls("configurationControls"));fields.addAll(controls("comparisonVariables"));requireSame(prior,current,fields);
    }
    private static void requireSame(Map<String,Object> prior,Map<String,Object> current,List<String> fields) {
        for(String control:fields)
            if(prior==null || current==null || prior.get(control)==null || current.get(control)==null || !Objects.equals(InvestigationJson.canonical(prior.get(control)),InvestigationJson.canonical(current.get(control))))
                throw new IllegalArgumentException("恢复批次控制变量不一致："+control);
    }
    @SuppressWarnings("unchecked")
    static List<Map<String,Object>> corpus() throws Exception {
        try(var in=InvestigationEvaluation.class.getResourceAsStream("/investigation/investigation-cases.json")) {return JsonUtil.MAPPER.readValue(in,List.class);}
    }
    @SuppressWarnings("unchecked")
    static Map<String,Object> run(Map<String,Object> test,InvestigationModel model,InvestigationProperties props,int repeat) {
        var result=new LinkedHashMap<String,Object>();result.put("runId",UUID.randomUUID().toString());result.put("startedAt",Instant.now().toString());result.put("caseId",test.get("caseId"));result.put("split",test.get("split"));result.put("repeat",repeat);result.put("passed",false);result.put("parserSource","MODEL");
        var repo=repository();var sourceItems=new ArrayList<Map<String,Object>>();var remote=new HashMap<String,Lookup>();int index=0;
        for(var value:(List<Map<String,Object>>)test.get("items")) {
            String ref="I"+(++index);var row=item(ref,value.get("status").toString(),(String)value.get("errorCode"));
            if(Boolean.TRUE.equals(test.get("missingRequest"))) row.put("requestId",null);
            if(Boolean.TRUE.equals(test.get("missingRule"))) row.put("rule",null);
            if(Boolean.TRUE.equals(test.get("missingEvents"))) {row.put("events",List.of());row.put("eventCount",0L);}
            if(Boolean.TRUE.equals(test.get("truncatedEvents"))) {row.put("eventsTruncated",true);row.put("eventCount",100L);}
            if(test.get("attack")!=null) {
                if(test.get("caseId").toString().contains("rule_injection")) {var rule=new LinkedHashMap<>((Map<String,Object>)row.get("rule"));rule.put("description",test.get("attack"));row.put("rule",rule);}
                else {row.put("label",test.get("attack"));row.put("errorMessage",test.get("attack"));}
            }
            if(Boolean.TRUE.equals(test.get("largeId"))) row.put("itemId","900719925474099"+index);
            sourceItems.add(row);
            if(value.get("lookup")!=null) remote.put("req-"+ref,new Lookup(LookupStatus.valueOf(value.get("lookup").toString()),"FAILED".equals(value.get("lookup"))?"BUSINESS_REJECTED":null,"核对事实"));
        }
        var session=session(sourceItems,props);result.put("factsHash",InvestigationJson.hash(session.snapshot));var tools=tools(repo,props,remote);long start=System.nanoTime();
        var traces=new TreeMap<Integer,Map<String,Object>>();
        org.mockito.Mockito.when(repo.startStep(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.nullable(String.class),org.mockito.ArgumentMatchers.nullable(String.class),org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            int seq=traces.size()+1;var trace=new LinkedHashMap<String,Object>();
            trace.put("runId",result.get("runId"));trace.put("seq",seq);trace.put("kind",call.getArgument(2));trace.put("toolCallId",call.getArgument(3));trace.put("toolName",call.getArgument(4));
            trace.put("arguments",archiveValue(call.getArgument(5)));trace.put("status","STARTED");trace.put("startedAt",Instant.now().toString());traces.put(seq,trace);return seq;
        });
        org.mockito.Mockito.doAnswer(call -> {
            var trace=Objects.requireNonNull(traces.get(call.<Integer>getArgument(2)),"步骤结束前必须保存开始记录");
            trace.put("status",call.getArgument(3));trace.put("result",archiveValue(call.getArgument(4)));trace.put("error",call.getArgument(5));trace.put("durationMs",call.getArgument(6));trace.put("usage",archiveValue(call.getArgument(7)));trace.put("finishedAt",Instant.now().toString());return null;
        }).when(repo).endStep(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.nullable(String.class),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
        try {
            var report=new InvestigationAgent(model,tools,new InvestigationReportValidator(),repo,props).investigate(session,test.get("question").toString());
            result.put("report",report);result.put("assessment",InvestigationReportGrader.assess(test,report,session));result.put("passed",grade(test,report,session));result.put("stopReason",Objects.toString(session.partialReason,"NORMAL"));
        } catch(InvestigationFailure error) {result.put("error",error.reason());result.put("message",error.getMessage());}
        if(!result.containsKey("assessment")) result.put("assessment",InvestigationReportGrader.assess(test,null,session));
        result.put("finishedAt",Instant.now().toString());result.put("durationMs",(System.nanoTime()-start)/1_000_000);result.put("usage",session.budget.summary());result.put("steps",new ArrayList<>(traces.values()));result.put("evidence",session.evidence);return result;
    }
    /** 归档仅保存脱敏且有界的展示副本；中断步骤保留STARTED，不虚构完成结果。 */
    private static Object archiveValue(Object value) {
        if(value==null) return null;
        String safe=com.example.report.operations.SensitiveData.text(JsonUtil.toJson(value));
        if(safe.length()>16000) return Map.of("truncated",true,"preview",safe.substring(0,16000));
        return JsonUtil.fromJson(safe,Object.class);
    }
    /** 独立评分同时检查条目结论、正文事实与证据；生产校验通过不能替代任务评分。 */
    static boolean grade(Map<String,Object> test,Map<String,Object> report,InvestigationSession session) {
        return Boolean.TRUE.equals(InvestigationReportGrader.assess(test,report,session).get("passed"));
    }
    /** 在模型调用前采集配置及代码依据，不能用执行结束时的文件改动替代原执行来源。 */
    static Map<String,Object> manifest(Map<String,Object> config) throws Exception {
        String corpusHash;
        try(var input=InvestigationEvaluation.class.getResourceAsStream("/investigation/investigation-cases.json")) {corpusHash=Digests.sha256(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}
        var manifest=new LinkedHashMap<String,Object>();manifest.put("createdAt",Instant.now().toString());manifest.put("evidenceScope","REAL_MODEL_WITH_SYNTHETIC_READ_FACTS_NO_BUSINESS_DB");manifest.put("configuration",config);manifest.put("corpusHash",corpusHash);manifest.put("journalVersion",2);
        manifest.put("commit",git("rev-parse","HEAD"));manifest.put("workspaceDiffHash",Digests.sha256(git("diff","--no-ext-diff")));manifest.put("workspaceStatusHash",Digests.sha256(git("status","--porcelain")));manifest.put("graderVersion",GRADER_VERSION);manifest.put("fixtureVersion",2);
        var source=new StringBuilder();try(var paths=Files.walk(Path.of("src","main"))) {for(var path:paths.filter(Files::isRegularFile).sorted().toList()) source.append(path).append('\n').append(Digests.sha256(new String(Files.readAllBytes(path),java.nio.charset.StandardCharsets.UTF_8))).append('\n');}manifest.put("backendSourceHash",Digests.sha256(source.toString()));
        return manifest;
    }
    /** 完成后由检查点生成汇总；归档原地收尾，顶层文件仅作为最新一次的便捷副本。 */
    static void write(Path root,InvestigationEvaluationJournal journal,List<Map<String,Object>> runs,int expected,boolean blocked) throws Exception {
        Path dir=journal.directory;var manifest=journal.manifest();
        manifest.put("startedAt",runs.stream().map(r -> r.get("startedAt").toString()).min(String::compareTo).orElse(null));
        manifest.put("finishedAt",runs.stream().map(r -> r.get("finishedAt").toString()).max(String::compareTo).orElse(null));
        journal.finishManifest(manifest);
        var rows=new StringBuilder();for(var run:runs) rows.append(JsonUtil.toJson(run)).append('\n');Files.writeString(dir.resolve("runs.jsonl"),rows);
        long passed=runs.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();var durations=runs.stream().mapToLong(r -> ((Number)r.get("durationMs")).longValue()).sorted().toArray();
        var summary=new LinkedHashMap<String,Object>();summary.put("status",blocked?"BLOCKED":"COMPLETED");summary.put("expected",expected);summary.put("executed",runs.size());summary.put("skipped",expected-runs.size());summary.put("passed",passed);summary.put("failed",runs.size()-passed);
        summary.put("completionRate",runs.isEmpty()?null:(double)passed/runs.size());summary.put("p50Ms",durations.length==0?null:durations[(durations.length-1)/2]);summary.put("p95Ms",durations.length==0?null:durations[(int)Math.ceil(durations.length*.95)-1]);
        summary.put("usageCompleteRuns",runs.stream().filter(r -> Boolean.TRUE.equals(((Map<?,?>)r.get("usage")).get("usageComplete"))).count());
        boolean priced=!runs.isEmpty() && runs.stream().allMatch(r -> ((Map<?,?>)r.get("usage")).get("cost") instanceof java.math.BigDecimal);
        summary.put("cost",priced?runs.stream().map(r -> (java.math.BigDecimal)((Map<?,?>)r.get("usage")).get("cost")).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add):null);summary.put("currency",priced?((Map<?,?>)runs.get(0).get("usage")).get("currency"):null);
        summary.put("maxMs",durations.length==0?null:durations[durations.length-1]);summary.put("reportInvalidRuns",runs.stream().filter(r -> "REPORT_INVALID".equals(r.get("error"))).count());summary.put("illegalToolRuns",runs.stream().filter(r -> "INVALID_TOOL".equals(r.get("error"))).count());
        var latencyGroups=new LinkedHashMap<String,Object>();for(boolean success:List.of(true,false)) {var times=runs.stream().filter(r -> Boolean.valueOf(success).equals(r.get("passed"))).mapToLong(r -> ((Number)r.get("durationMs")).longValue()).sorted().toArray();if(times.length>0) latencyGroups.put(success?"passed":"failed",Map.of("count",times.length,"p50Ms",times[(times.length-1)/2],"p95Ms",times[(int)Math.ceil(times.length*.95)-1],"maxMs",times[times.length-1]));}summary.put("latencyGroups",latencyGroups);
        var perCase=new LinkedHashMap<String,Object>();for(var run:runs) {String id=run.get("caseId").toString();@SuppressWarnings("unchecked") var attempts=(List<Object>)perCase.computeIfAbsent(id,key -> new ArrayList<>());attempts.add(Map.of("repeat",run.get("repeat"),"passed",run.get("passed"),"durationMs",run.get("durationMs"),"stopReason",Objects.toString(run.getOrDefault("error",run.get("stopReason")),"UNKNOWN")));}summary.put("perCase",perCase);
        for(String metric:List.of("modelCalls","toolCalls","mcpCalls")) summary.put(metric,runs.stream().mapToLong(r -> ((Number)((Map<?,?>)r.get("usage")).get(metric)).longValue()).sum());
        summary.put("allRepeatsPassedCases",journal.fullyPassedCases());
        summary.put("caseCount",perCase.size());
        // 分子分母独立归档；无报告的运行仍计入应判定条目数，正文指标仅覆盖实际展示断言。
        for(String metric:List.of("correctItems","expectedItems","supportedFacts","assertedFacts","legalCitations","totalCitations"))
            summary.put(metric,runs.stream().mapToLong(r -> ((Number)((Map<?,?>)r.get("assessment")).get(metric)).longValue()).sum());
        summary.put("textReviewScope","CONTROLLED_NARRATIVE_ASSERTIONS_WITH_INDEPENDENT_FIXTURE_AND_EVIDENCE_CHECKS");
        Files.writeString(dir.resolve("summary.json"),JsonUtil.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        Files.writeString(dir.resolve("report.md"),"# 调查真实模型工具评估\n\n证据范围：真实模型与合成只读事实；未访问业务数据库，不代表真实HTTP MCP或外部ERP验收。\n\n状态："+summary.get("status")+"；计划 "+expected+"，实际 "+runs.size()+"，通过 "+passed+"，失败 "+(runs.size()-passed)+"，跳过 "+(expected-runs.size())+"。\n\n逐次轨迹及证据见 runs.jsonl，逐例重复分布见 summary.json，配置见 manifest.json。费用只有计量和价格配置充分时才有值，空值不能报告为零。\n");
        for(String file:List.of("manifest.json","runs.jsonl","summary.json","report.md")) Files.copy(dir.resolve(file),root.resolve(file),StandardCopyOption.REPLACE_EXISTING);
        for(var run:runs) {var task=dir.resolve(run.get("runId").toString());Files.createDirectories(task);Files.writeString(task.resolve("steps.json"),JsonUtil.toJson(run.get("steps")));Files.writeString(task.resolve("evidence.json"),JsonUtil.toJson(run.get("evidence")));}
    }
    private static String git(String... args) {try {var cmd=new ArrayList<String>();cmd.add("git");cmd.addAll(List.of(args));var process=new ProcessBuilder(cmd).start();return new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).trim();} catch(Exception e) {return "unavailable";}}
    /** 对本次实际编译产物取摘要，避免运行期间源码编辑被误当作已执行的版本。 */
    static String artifactHash(Path root) throws Exception {
        var digest=java.security.MessageDigest.getInstance("SHA-256");try(var paths=Files.walk(root)) {for(var file:paths.filter(Files::isRegularFile).sorted().toList()) {digest.update(root.relativize(file).toString().replace('\\','/').getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);digest.update(Files.readAllBytes(file));}}return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
