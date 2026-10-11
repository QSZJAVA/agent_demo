package com.example.report.assistant;

import com.example.report.common.*;
import com.example.report.operations.SensitiveData;
import org.springframework.ai.chat.model.ChatResponse;
import java.util.*;
import java.util.function.Supplier;

/** 单轮语义阶段计量；只记录有界元数据和已校验原因，不保存模型提示、原始输出或认证信息。 */
public final class PlanningDiagnostics {
    private final long started=System.nanoTime();
    private final List<Map<String,Object>> calls=new ArrayList<>();
    private final List<Map<String,Object>> validations=new ArrayList<>();
    private SemanticReview acceptedReview;
    /**
     * 执行一次模型调用并记录实际响应计量；缺少供应商原生usage时保持未知，不能把SDK默认零当作真实用量。
     * @param stage PURPOSE、PLAN或REVIEW，分别计次
     * @param instructions 本阶段固定契约，仅保存摘要
     * @param input 已经统一出站脱敏的模型输入，仅统计字节数
     * @param call 实际模型调用，不增加额外重试
     * @return 原始文本只交给当前轮严格解码器，不进入诊断载荷
     */
    public String model(String stage,String instructions,String input,Supplier<ChatResponse> call){
        long begin=System.nanoTime();var row=new LinkedHashMap<String,Object>();
        row.put("stage",stage);row.put("attempt",calls.stream().filter(c->stage.equals(c.get("stage"))).count()+1);
        row.put("contractHash",Digests.sha256(instructions));row.put("inputBytes",bytes(input));row.put("outputBytes",null);
        row.put("inputTokens",null);row.put("outputTokens",null);row.put("usageComplete",false);row.put("model",null);row.put("status","FAILED");row.put("failure",null);
        calls.add(row);
        try{
            var response=call.get();if(response==null || response.getResult()==null || response.getResult().getOutput()==null)throw new ApiException(503,"真实模型未返回有效响应");
            String text=response.getResult().getOutput().getText();row.put("outputBytes",bytes(text));
            var metadata=response.getMetadata();var usage=metadata==null?null:metadata.getUsage();
            if(metadata!=null)row.put("model",metadata.getModel());
            var measured=ModelUsage.read(usage);
            row.put("usageComplete",measured.complete());row.put("inputTokens",measured.inputTokens());row.put("outputTokens",measured.outputTokens());
            row.put("status","RETURNED");return text;
        }catch(RuntimeException failure){row.put("failure",failure instanceof ApiException api?"HTTP_"+api.getCode():failure.getClass().getSimpleName());throw failure;}
        finally{row.put("durationMs",Math.max(0,(System.nanoTime()-begin)/1_000_000));}
    }
    /** 记录程序预检或结构/语义反馈；不把可解析输出自动计为语义正确。 */
    public void validation(String stage,String outcome,String detail){
        if(validations.size()>=40)return;
        String safe=SensitiveData.text(Objects.toString(detail,""));if(safe.length()>512)safe=safe.substring(0,512);
        validations.add(Map.of("stage",stage,"outcome",outcome,"detail",safe));
    }
    /** 完整复核通过后只在当前轮保留依据，用于生成可读条件解释；不会使期望直接成为可执行计划。 */
    public void accepted(SemanticReview review){acceptedReview=review;}
    public SemanticReview review(){return acceptedReview;}
    /**
     * 生成可持久化元数据；费用缺少可靠价格表时明确未知。
     * @param requestId 服务端轮次标识
     * @param outcome 规划/查询阶段结论，不代表实际派单已经执行
     * @param dictionaryHash 本轮授权词典指纹
     * @return 有界诊断对象，原提示和响应均不包含在内
     */
    public Map<String,Object> snapshot(String requestId,String outcome,String dictionaryHash){
        var result=new LinkedHashMap<String,Object>();result.put("requestId",requestId);result.put("outcome",outcome);result.put("dictionaryHash",dictionaryHash);
        result.put("schemaHashes",Map.of("PURPOSE",Digests.sha256(AssistantSchema.purposeSchema()),"PLAN",Digests.sha256(AssistantSchema.planSchema()),"REVIEW",Digests.sha256(AssistantSchema.reviewSchema())));
        result.put("durationMs",Math.max(0,(System.nanoTime()-started)/1_000_000));result.put("modelCalls",calls.size());
        int repair=0;for(String stage:List.of("PURPOSE","PLAN","REVIEW"))repair+=Math.max(0,(int)calls.stream().filter(c->stage.equals(c.get("stage"))).count()-1);
        result.put("repairCalls",repair);result.put("firstPass",!calls.isEmpty() && repair==0 && outcome.equals("ACCEPTED"));
        boolean complete=!calls.isEmpty() && calls.stream().allMatch(c->Boolean.TRUE.equals(c.get("usageComplete")));
        result.put("usageComplete",complete);result.put("inputTokens",complete?calls.stream().mapToLong(c->((Number)c.get("inputTokens")).longValue()).sum():null);
        result.put("outputTokens",complete?calls.stream().mapToLong(c->((Number)c.get("outputTokens")).longValue()).sum():null);
        result.put("cost",null);result.put("costComplete",false);result.put("calls",calls.stream().map(LinkedHashMap::new).toList());result.put("validations",List.copyOf(validations));return result;
    }
    private static int bytes(String value){return value==null?0:value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
}
