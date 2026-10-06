package com.example.report.investigation;

import com.example.report.common.Digests;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** 真实OpenAI兼容工具调用传输；每次使用剩余时限，关闭SDK自动重试和内部工具执行，不继承其他客户端工具。 */
@Component
@ConditionalOnProperty(name="security.enabled", havingValue="true")
public class InvestigationOpenAiModel implements InvestigationModel {
    private final InvestigationProperties props;
    private final String baseUrl,apiKey,defaultModel,path;
    public InvestigationOpenAiModel(InvestigationProperties props,
            @Value("${spring.ai.openai.base-url:}") String baseUrl,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:}") String defaultModel,
            @Value("${spring.ai.openai.chat.completions-path:/v1/chat/completions}") String path) {
        this.props=props;this.baseUrl=baseUrl;this.apiKey=apiKey;this.defaultModel=defaultModel;this.path=path;
    }
    @Override public Reply call(List<Message> messages,List<ToolCallback> tools,boolean report,Duration timeout) {
        if(!configured() || timeout.isNegative() || timeout.isZero()) throw new InvestigationFailure("MODEL_UNAVAILABLE","真实模型配置缺失或调用时限已到");
        try {
            // JDK请求传输受实际剩余时限约束，线程中断可传播到HTTP发送；没有额外未计数重试。
            var http=HttpClient.newBuilder().connectTimeout(timeout.compareTo(Duration.ofSeconds(3))<0?timeout:Duration.ofSeconds(3)).build();
            var factory=new JdkClientHttpRequestFactory(http);factory.setReadTimeout(timeout);
            var api=OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).completionsPath(path)
                    .restClientBuilder(RestClient.builder().requestFactory(factory).requestInterceptor((request,body,execution) -> {
                        if(body.length>props.getMaxInputUtf8Bytes()) throw new InvestigationFailure("BUDGET_EXHAUSTED","实际模型请求体积已达到上限");
                        return execution.execute(request,body);
                    })).build();
            var model=OpenAiChatModel.builder().openAiApi(api).retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
            var options=OpenAiChatOptions.builder().model(modelName()).temperature(0.0)
                    .maxTokens(report?props.getMaxReportOutputTokens():props.getMaxOutputTokens())
                    .internalToolExecutionEnabled(false).toolCallbacks(tools).toolNames(Set.of())
                    .extraBody(Map.of("thinking",Map.of("type",props.isThinkingEnabled()?"enabled":"disabled")));
            if(report && props.isNativeSchema()) options.outputSchema(InvestigationReportValidator.schema());
            var response=model.call(new Prompt(com.example.report.operations.ModelEgressPolicy.messages(messages),options.build()));
            if(response==null || response.getResult()==null) throw new InvestigationFailure("MODEL_UNAVAILABLE","真实模型未返回有效响应");
            var usage=new LinkedHashMap<String,Object>();
            var met=response.getMetadata().getUsage();
            // API没有返回usage时默认对象可能为零，必须结合原生计量判断是否真的有计量证据。
            boolean present=met!=null && met.getNativeUsage()!=null;
            usage.put("inputTokens",present?met.getPromptTokens():null);usage.put("outputTokens",present?met.getCompletionTokens():null);
            usage.put("usageComplete",present && met.getPromptTokens()!=null && met.getCompletionTokens()!=null);
            Integer cached=null;
            if(present) {
                var nativeUsage=com.example.report.common.JsonUtil.MAPPER.valueToTree(met.getNativeUsage());
                for(String field:List.of("prompt_cache_hit_tokens","promptCacheHitTokens")) if(nativeUsage.path(field).isIntegralNumber()) cached=nativeUsage.path(field).intValue();
                for(String field:List.of("prompt_tokens_details","promptTokensDetails")) {
                    var details=nativeUsage.path(field);for(String key:List.of("cached_tokens","cachedTokens")) if(details.path(key).isIntegralNumber()) cached=details.path(key).intValue();
                }
            }
            usage.put("cachedInputTokens",cached);usage.put("currency",props.getCurrency());usage.put("cost",cost(usage));
            return new Reply(response.getResult().getOutput(),response.getResult().getMetadata().getFinishReason(),usage);
        } catch(InvestigationFailure e) {throw e;}
        catch(Exception e) {
            String diagnostic=e instanceof org.springframework.web.client.RestClientResponseException http?"HTTP "+http.getStatusCode().value():e.getClass().getSimpleName();
            if(e instanceof org.springframework.ai.retry.NonTransientAiException) {
                var status=java.util.regex.Pattern.compile("^([1-5][0-9]{2})\\b").matcher(Objects.toString(e.getMessage(),""));
                if(status.find()) diagnostic="HTTP "+status.group(1);
                String lower=Objects.toString(e.getMessage(),"").toLowerCase(Locale.ROOT);
                if(lower.contains("api key") || lower.contains("api_key") || lower.contains("authentication")) diagnostic+=" AUTHENTICATION";
                else if(lower.contains("tool")) diagnostic+=" TOOL_OPTIONS";
                else if(lower.contains("thinking")) diagnostic+=" THINKING_OPTIONS";
                else if(lower.contains("model")) diagnostic+=" MODEL_OPTIONS";
            }
            // 仅记录异常类别或HTTP状态；供应商响应和异常消息可能回显凭据，不能写入日志。
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("调查模型调用失败 type={}",diagnostic);
            throw new InvestigationFailure("MODEL_UNAVAILABLE","真实模型请求失败（"+diagnostic+"），请检查端点、鉴权、工具能力和超时配置");
        }
    }
    private String modelName() {return props.getModel()==null || props.getModel().isBlank()?defaultModel:props.getModel();}
    private boolean configured() {
        try {var uri=URI.create(baseUrl);return apiKey!=null && !apiKey.isBlank() && modelName()!=null && !modelName().isBlank() && Set.of("http","https").contains(uri.getScheme()) && uri.getHost()!=null;}
        catch(RuntimeException missing) {return false;}
    }
    @Override public Map<String,Object> configuration() {
        var config=new LinkedHashMap<String,Object>();config.put("model",modelName());
        config.put("configured",configured());
        String endpoint=null;try {var uri=URI.create(baseUrl);if(uri.getScheme()!=null && uri.getHost()!=null) endpoint=uri.getScheme()+"://"+uri.getHost()+(uri.getPort()<0?"":":"+uri.getPort())+Objects.toString(uri.getPath(),"");} catch(RuntimeException missing) { }
        config.put("endpoint",endpoint);
        config.put("completionsPath",path);config.put("businessTimezone",props.getBusinessTimezone());
        config.put("nativeSchema",props.isNativeSchema());config.put("thinkingEnabled",props.isThinkingEnabled());
        config.put("parserSource","MODEL");config.put("promptHash",Digests.sha256(InvestigationAgent.INSTRUCTIONS));
        var prices=new LinkedHashMap<String,Object>();prices.put("inputPerMillion",props.getInputPricePerMillion());prices.put("cachedInputPerMillion",props.getCachedInputPricePerMillion());prices.put("outputPerMillion",props.getOutputPricePerMillion());prices.put("currency",props.getCurrency());
        config.put("prices",prices);config.put("priceConfigHash",Digests.sha256(com.example.report.common.JsonUtil.toJson(prices)));
        config.put("toolSchemaHash",Digests.sha256(InvestigationTools.schemaJson()));config.put("reportSchemaHash",Digests.sha256(InvestigationReportValidator.schema()));
        config.put("contextVersion",InvestigationContext.VERSION);
        var budgets=new LinkedHashMap<String,Object>();budgets.put("maxItems",props.getMaxItems());budgets.put("maxModelCalls",props.getMaxModelCalls());budgets.put("maxCollectionCalls",props.getMaxCollectionCalls());budgets.put("maxToolCalls",props.getMaxToolCalls());budgets.put("maxMcpCalls",props.getMaxMcpCalls());
        budgets.put("maxOutputTokens",props.getMaxOutputTokens());budgets.put("maxReportOutputTokens",props.getMaxReportOutputTokens());budgets.put("maxInputUtf8Bytes",props.getMaxInputUtf8Bytes());budgets.put("contextTargetUtf8Bytes",props.getContextTargetUtf8Bytes());budgets.put("maxToolResultUtf8Bytes",props.getMaxToolResultUtf8Bytes());
        budgets.put("runTimeoutSeconds",props.getRunTimeoutSeconds());budgets.put("modelTimeoutSeconds",props.getModelTimeoutSeconds());budgets.put("mcpTimeoutSeconds",props.getMcpTimeoutSeconds());budgets.put("queueTimeoutSeconds",props.getQueueTimeoutSeconds());config.put("budgets",budgets);return config;
    }
    /** 仅计量和配置均充分时计算费用；缓存口径未知且价格不同，费用保持null。 */
    private java.math.BigDecimal cost(Map<String,Object> usage) {
        if(!Boolean.TRUE.equals(usage.get("usageComplete")) || props.getInputPricePerMillion()==null || props.getOutputPricePerMillion()==null || props.getCurrency()==null) return null;
        Integer cached=(Integer)usage.get("cachedInputTokens");var input=props.getInputPricePerMillion();var cachedRate=props.getCachedInputPricePerMillion();
        if(cached==null && (cachedRate==null || cachedRate.compareTo(input)!=0)) return null;
        int hit=cached==null?0:cached;int tokens=((Number)usage.get("inputTokens")).intValue();
        if(hit<0 || hit>tokens || (hit>0 && cachedRate==null)) return null;
        return input.multiply(java.math.BigDecimal.valueOf(tokens-hit)).add((cachedRate==null?input:cachedRate).multiply(java.math.BigDecimal.valueOf(hit)))
                .add(props.getOutputPricePerMillion().multiply(java.math.BigDecimal.valueOf(((Number)usage.get("outputTokens")).longValue()))).divide(java.math.BigDecimal.valueOf(1_000_000));
    }
}
