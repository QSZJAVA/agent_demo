package com.example.report.investigation;

import com.example.report.common.JsonUtil;
import com.example.report.dispatch.DispatchGateway.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.report.investigation.InvestigationTestSupport.*;

/** 真实模型长轨迹验收；用户明确要求分页与回读，所有工具选择仍由真实模型生成，业务事实及远端结果为合成夹具。 */
@EnabledIfEnvironmentVariable(named="INVESTIGATION_CONTEXT_LIVE",matches="true")
class InvestigationContextLiveTest {
    private static final String CASE="context_long_events_readback";
    private static final String QUESTION="""
            请调查I1、I2、I3、I4。需要核查四项的完整执行事件，请先并行分别读取每项事件，每项独立分页，
            使用每页nextCursor继续直至完整；不要把四项合成一个分页范围。然后读取条目状态和规则快照，
            对本地未明条目核对远端结果。只有实际看到独立的程序笔记消息（JSON顶层kind为PROGRAM_EVIDENCE_NOTES）
            才通过evidence_read回读；本问题提到这个名称不代表已经整理，在笔记出现前不要回读。
            回读笔记中E编号最小且type=EXECUTION_EVENT的那份事件证据，核查首条事件备注。
            不能读取RULE_SNAPSHOT来代替事件回读，也不能用重新查询事件来代替回读。
            收到回读结果后再结束收集。报告按本次明确状态和实际E编号生成，规则因果尚不明确时列为待查。
            """;

    /** 默认生产预算下执行明确的长历史任务；失败先归档再断言，不能把未触发压缩的普通成功当作验收通过。 */
    @Test @SuppressWarnings("unchecked") void realModelCompactsAndReadsSavedEvidence() throws Exception {
        var props=new InvestigationProperties();props.setModel(System.getenv("INVESTIGATION_MODEL"));
        props.setNativeSchema(Boolean.parseBoolean(System.getenv("INVESTIGATION_NATIVE_SCHEMA")));props.setThinkingEnabled(Boolean.parseBoolean(System.getenv("INVESTIGATION_THINKING_ENABLED")));props.validate();
        int repeats=Integer.parseInt(System.getenv().getOrDefault("INVESTIGATION_REPEATS","3"));assertTrue(repeats>=1 && repeats<=10);
        var real=new InvestigationOpenAiModel(props,System.getenv().getOrDefault("LLM_BASE_URL","https://dashscope.aliyuncs.com/compatible-mode"),System.getenv("LLM_API_KEY"),System.getenv().getOrDefault("LLM_MODEL","deepseek-v4.1-flash"),"/v1/chat/completions");
        var test=task();var source=sourceItems();var remote=Map.of("req-I1",new Lookup(LookupStatus.SUCCESS,null,"核对成功"),"req-I2",new Lookup(LookupStatus.FAILED,"BUSINESS_REJECTED","明确失败"),"req-I3",new Lookup(LookupStatus.UNKNOWN,null,"仍未明"));
        var config=new LinkedHashMap<>(real.configuration());config.put("backendArtifactHash",InvestigationEvaluation.artifactHash(Path.of("target","classes")));config.put("evaluationHarnessHash",InvestigationEvaluation.artifactHash(Path.of("target","test-classes")));
        config.put("evaluationParallelism",1);config.put("evaluationMinBatchIntervalSeconds",11);
        var manifest=InvestigationEvaluation.manifest(config);manifest.put("evidenceScope","REAL_MODEL_CONTEXT_WITH_SYNTHETIC_FACTS_NO_BUSINESS_DB");
        String fixtureHash=InvestigationJson.hash(Map.of("task",test,"sourceItems",source,"remote",remote));
        // 专项实际执行自己的长事件任务；不能沿用通用40题语料哈希，否则改问法仍可能被对比工具当成同题。
        manifest.put("corpusHash",fixtureHash);manifest.put("contextAcceptanceVersion",3);manifest.put("contextFixtureHash",fixtureHash);
        manifest.put("workflowScope","USER_REQUESTED_PAGINATION_AND_READBACK_NOT_UNPROMPTED_TOOL_SELECTION");
        var planned=new LinkedHashSet<String>();for(int i=1;i<=repeats;i++) planned.add(CASE+":"+i);
        var root=Path.of("target","investigation-context-evaluation");
        try(var journal=new InvestigationEvaluationJournal(root,manifest,planned,List.of())) {
            Files.writeString(journal.directory.resolve("fixture.json"),JsonUtil.toJson(Map.of("task",test,"sourceItems",source,"remote",remote)));
            System.out.println("Context acceptance archive: "+journal.directory.toAbsolutePath());boolean blocked=false;long previousStart=0;
            for(int repeat=1;repeat<=repeats && !blocked;repeat++) {
                long delay=previousStart+Duration.ofSeconds(11).toNanos()-System.nanoTime();if(previousStart>0 && delay>0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(delay);previousStart=System.nanoTime();
                String key=CASE+":"+repeat;journal.start(key);
                var observer=new ObservedModel(real,journal.directory.resolve("model-requests-"+repeat+".jsonl"));
                var result=InvestigationEvaluation.runWithFacts(test,observer,props,repeat,source,remote);
                var checks=assessContext(result,observer);result.put("contextAssessment",checks);result.put("passed",Boolean.TRUE.equals(result.get("passed")) && Boolean.TRUE.equals(checks.get("passed")));
                journal.complete(key,result);System.out.println("Context repeat="+repeat+" passed="+result.get("passed")+" checks="+JsonUtil.toJson(checks));
                blocked="MODEL_UNAVAILABLE".equals(result.get("error"));
            }
            InvestigationEvaluation.write(root,journal,journal.results(),repeats,blocked);
            assertFalse(blocked,"真实模型不可用，失败与未执行题次已保留");
            assertEquals(repeats,journal.results().stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count(),"并非每次都满足状态、压缩、回读及协议约束，详见专项归档");
        }
    }

    /** 每项20个有界长事件，与实际快照上限相同；仅事件说明变长，状态和规则不会通过用户问题泄露答案。 */
    static List<Map<String,Object>> sourceItems() {
        var items=new ArrayList<Map<String,Object>>();
        for(int i=1;i<=4;i++) {
            String ref="I"+i;var row=item(ref,i==4?"SKIPPED":"UNKNOWN",i==4?"RECORD_CHANGED":"RESULT_UNKNOWN");
            var events=new ArrayList<Map<String,Object>>();
            for(int n=0;n<20;n++) {
                String message="CTX_EVENT_"+ref+"_"+n+"：执行事件的补充记录，供核查当时的处理过程。"+"业务状态以结构化字段为准。".repeat(20);
                events.add(Map.of("eventId",ref+"-"+n,"type","AUDIT","outcome",row.get("status"),"attemptCount",1,"at","2026-10-06T00:00:00Z","message",message.substring(0,200)));
            }
            row.put("events",events);row.put("eventCount",20L);items.add(row);
        }return items;
    }
    private static Map<String,Object> task() {
        return Map.of("caseId",CASE,"split","context-acceptance","question",QUESTION,
                "items",List.of(Map.of("status","UNKNOWN","lookup","SUCCESS"),Map.of("status","UNKNOWN","lookup","FAILED"),Map.of("status","UNKNOWN","lookup","UNKNOWN"),Map.of("status","SKIPPED")),
                "expectedReasons",List.of("REMOTE_SUCCESS_LOCAL_UNRESOLVED","BUSINESS_REJECTED","RESULT_UNKNOWN","PRECHECK_SKIPPED"));
    }
    /** 要求真正的缩小、全部80个事件、压缩后的旧证据回读以及模型下一轮确实收到完整回读内容。 */
    @SuppressWarnings("unchecked") private static Map<String,Object> assessContext(Map<String,Object> run,ObservedModel model) {
        var steps=(List<Map<String,Object>>)run.get("steps");var events=new HashSet<String>();
        var evidence=(Map<String,InvestigationEvidenceStore.Evidence>)run.get("evidence");
        for(var entry:evidence.values()) if("EXECUTION_EVENT".equals(entry.type()) && entry.data().get("items") instanceof List<?> rows)
            for(var row:rows) events.add(((Map<?,?>)row).get("eventId").toString());
        var compactions=steps.stream().filter(step -> "CONTEXT_COMPACT".equals(step.get("toolName")) && "SUCCEEDED".equals(step.get("status"))).toList();
        boolean smaller=!compactions.isEmpty() && compactions.stream().allMatch(step -> ((Number)((Map<?,?>)step.get("result")).get("afterBytes")).intValue()<((Number)((Map<?,?>)step.get("arguments")).get("beforeBytes")).intValue());
        boolean read=false,omittedDetailsRecalled=false;
        for(var step:steps) if("investigation_evidence_read".equals(step.get("toolName")) && "SUCCEEDED".equals(step.get("status"))) {
            String id=JsonUtil.toMap(((Map<?,?>)step.get("arguments")).get("arguments").toString()).get("evidenceId").toString();
            var entry=evidence.get(id);
            read|=entry!=null && "EXECUTION_EVENT".equals(entry.type()) && compactions.stream().anyMatch(c -> ((Number)c.get("seq")).intValue()<((Number)step.get("seq")).intValue()
                    && ((Map<?,?>)((Map<?,?>)((Map<?,?>)c.get("result")).get("notes")).get("evidence")).containsKey(id));
            if(entry!=null && "EXECUTION_EVENT".equals(entry.type()) && entry.data().get("items") instanceof List<?> rows && !rows.isEmpty()) {
                String marker=((Map<?,?>)rows.get(0)).get("message").toString().split("：",2)[0];
                omittedDetailsRecalled|=model.readInputs.getOrDefault(id,List.of()).stream().anyMatch(input -> !input.contains(marker));
            }
        }
        var checks=new LinkedHashMap<String,Object>();checks.put("compactions",compactions.size());checks.put("allCompactionsSmaller",smaller);checks.put("uniqueEventsRead",events.size());
        checks.put("oldEventEvidenceReadAfterCompaction",read);checks.put("omittedEventDetailsRecalled",omittedDetailsRecalled);checks.put("notesDeliveredToRealModel",model.notesDelivered);checks.put("readbackDeliveredToRealModel",model.readbackDelivered);checks.put("protocolAndOriginalQuestionPreserved",model.protocolValid);
        checks.put("passed",smaller && events.size()==80 && read && omittedDetailsRecalled && model.notesDelivered && model.readbackDelivered && model.protocolValid);return checks;
    }

    /** 只观察真实调用，不改写消息、工具或回复；保存安全夹具的逻辑请求及工具调用，不保存供应商内部思考或凭据。 */
    private static final class ObservedModel implements InvestigationModel {
        private final InvestigationModel real;private final Path file;private int calls;
        private boolean notesDelivered,readbackDelivered,protocolValid=true;
        private final Map<String,List<String>> readInputs=new HashMap<>();
        ObservedModel(InvestigationModel real,Path file) {this.real=real;this.file=file;}
        public Map<String,Object> configuration() {return real.configuration();}
        public Reply call(List<Message> messages,List<ToolCallback> tools,boolean report,Duration timeout) {
            calls++;var pending=new HashMap<String,String>();
            protocolValid&=messages.get(0).getText().startsWith(InvestigationAgent.INSTRUCTIONS) && QUESTION.equals(JsonUtil.toMap(messages.get(1).getText()).get("question"));
            var logMessages=new ArrayList<Map<String,Object>>();
            for(var message:messages) {
                String text=Objects.toString(message.getText(),"");
                // 原问题本身会提到笔记名称；只有实际发送的结构化笔记对象才能证明模型收到压缩结果。
                if(message instanceof UserMessage && text.startsWith("{")) notesDelivered|="PROGRAM_EVIDENCE_NOTES".equals(JsonUtil.toMap(text).get("kind"));
                var line=new LinkedHashMap<String,Object>();line.put("role",message.getMessageType().name());line.put("text",text);
                if(message instanceof AssistantMessage assistant) {protocolValid&=pending.isEmpty();for(var call:assistant.getToolCalls()) protocolValid&=pending.put(call.id(),call.name())==null;line.put("toolCalls",assistant.getToolCalls());}
                if(message instanceof ToolResponseMessage responses) {
                    for(var response:responses.getResponses()) {protocolValid&=Objects.equals(pending.remove(response.id()),response.name());readbackDelivered|=response.name().equals("investigation_evidence_read") && response.responseData().contains("CTX_EVENT_");}
                    protocolValid&=pending.isEmpty();line.put("toolResponses",responses.getResponses());
                }logMessages.add(line);
            }
            protocolValid&=pending.isEmpty();append(Map.of("event","REQUEST","call",calls,"phase",report?"REPORT":"COLLECTION","inputUtf8Bytes",InvestigationContext.bytes(messages),"messages",logMessages));
            var reply=real.call(messages,tools,report,timeout);
            for(var tool:reply.message().getToolCalls()) if("investigation_evidence_read".equals(tool.name())) {
                try {String id=Objects.toString(JsonUtil.toMap(tool.arguments()).get("evidenceId"),"");readInputs.computeIfAbsent(id,ignored -> new ArrayList<>()).add(JsonUtil.toJson(logMessages));}
                catch(RuntimeException invalidArguments) { /* 参数错误由正式工具循环处理，观察器不能替模型修正请求。 */ }
            }
            append(Map.of("event","RESPONSE","call",calls,"finishReason",Objects.toString(reply.finishReason(),""),"toolCalls",reply.message().getToolCalls(),"usage",reply.usage()));return reply;
        }
        private void append(Object value) {try {Files.writeString(file,JsonUtil.toJson(value)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(java.io.IOException failure){throw new IllegalStateException("真实模型观察记录保存失败",failure);}}
    }
}
