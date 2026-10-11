package com.example.report.analysis;

import com.example.report.common.*;
import com.example.report.operations.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
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

/** 真实模型结构化传输；沿用当前端点和脱敏边界，关闭内部工具执行及隐式重试，单次调用受剩余时限约束。 */
@Component
@ConditionalOnProperty(name="security.enabled",havingValue="true")
public class AnalysisOpenAiModel implements AnalysisModel {
    private final String baseUrl,apiKey,model,path;
    public AnalysisOpenAiModel(@Value("${spring.ai.openai.base-url:}") String baseUrl,@Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${spring.ai.openai.chat.options.model:}") String model,@Value("${spring.ai.openai.chat.completions-path:/v1/chat/completions}") String path) {
        this.baseUrl=baseUrl;this.apiKey=apiKey;this.model=model;this.path=path;
    }
    /** 原文中的受保护实体只在本次内存中保留映射；模型只见占位符，结构化响应恢复后仍须校验授权及引用。 */
    @Override public Reply call(String instructions,Object input,String schema,Duration timeout) {
        if(!configured()||timeout.isZero()||timeout.isNegative())throw new ApiException(503,"分析模型未配置或调用时限已到");
        long start=System.nanoTime();
        try {
            var protectedInput=SensitiveData.modelText(JsonUtil.toJson(input));
            var transport=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3).compareTo(timeout)<0?Duration.ofSeconds(3):timeout).build();
            var factory=new JdkClientHttpRequestFactory(transport);factory.setReadTimeout(timeout);
            var api=OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).completionsPath(path).restClientBuilder(RestClient.builder().requestFactory(factory)
                    .requestInterceptor((request,body,execution)->{if(body.length>128*1024)throw new ApiException(422,"分析模型输入超过128KiB预算，请缩小问题");return execution.execute(request,body);})).build();
            var client=OpenAiChatModel.builder().openAiApi(api).retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
            var options=OpenAiChatOptions.builder().model(model).temperature(0.0).maxTokens(4096).outputSchema(schema)
                    .internalToolExecutionEnabled(false).toolCallbacks(List.of()).toolNames(Set.of()).extraBody(Map.of("thinking",Map.of("type","disabled"))).build();
            var messages=ModelEgressPolicy.messages(List.of(new SystemMessage(instructions+"\n输出JSON Schema：\n"+schema),new UserMessage(protectedInput.text())));
            var response=client.call(new Prompt(messages,options));
            if(response==null||response.getResult()==null||response.getResult().getOutput()==null||response.getResult().getOutput().hasToolCalls())throw new ApiException(502,"分析模型没有返回合法结构化响应");
            if(!"stop".equalsIgnoreCase(Objects.toString(response.getResult().getMetadata().getFinishReason(),"")))throw new ApiException(502,"分析模型输出未完整结束");
            String text=response.getResult().getOutput().getText();if(text==null||text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>128*1024)throw new ApiException(502,"分析模型输出缺失或超过预算");
            return new Reply(protectedInput.restore(text),ModelUsage.read(response.getMetadata()==null?null:response.getMetadata().getUsage()),(System.nanoTime()-start)/1_000_000,model);
        } catch(ApiException known){throw known;}
        catch(Exception unavailable){org.slf4j.LoggerFactory.getLogger(getClass()).warn("有限分析模型调用未完成 type={}",unavailable.getClass().getSimpleName());throw new ApiException(503,"真实模型调用未完成，请核对端点、鉴权、Schema能力和超时配置");}
    }
    private boolean configured(){try{var uri=URI.create(baseUrl);return apiKey!=null&&!apiKey.isBlank()&&model!=null&&!model.isBlank()&&Set.of("http","https").contains(uri.getScheme())&&uri.getHost()!=null;}catch(Exception invalid){return false;}}
    /** 配置证据不包含API密钥；变更端点、提示或预算后排队任务不能沿用旧配置声明。 */
    @Override public Map<String,Object> configuration(){String endpoint="";try{var uri=URI.create(baseUrl);endpoint=uri.getScheme()+"://"+uri.getHost()+(uri.getPort()<0?"":":"+uri.getPort())+Objects.toString(uri.getPath(),"");}catch(Exception ignored){ }
        return Map.of("model",Objects.toString(model,""),"endpoint",endpoint,"path",path,"configured",configured(),"nativeSchema",true,"thinkingEnabled",false,"parserSource","MODEL");}
}
