package com.example.report.operations;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import org.springframework.ai.chat.messages.*;
import java.util.*;
import java.util.regex.Pattern;

/** 模型出站的确定性边界；凭据样式输入在调用前阻断，业务上下文在语义与调查两条链路统一脱敏。 */
public final class ModelEgressPolicy {
    private ModelEgressPolicy() { }
    private static final List<Pattern> CREDENTIALS=List.of(
        Pattern.compile("(?is)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"),
        Pattern.compile("(?i)(?<![A-Za-z0-9])(?:sk[-_](?:live[-_]|proj[-_])?[A-Za-z0-9_-]{12,}|AKIA[A-Z0-9]{16})(?![A-Za-z0-9])"),
        Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]{8,}={0,2}"),
        Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"),
        Pattern.compile("(?i)(?:api[ _-]?key|access[ _-]?token|client[ _-]?secret|password|passwd|secret[ _-]?key|密码|口令|密钥|令牌)[\\\"']?\\s*(?:[:=：]|是|为)\\s*[\\\"']?[^\\s\\\"',;，；}]{4,}")
    );
    /** 用户输入中有明确凭据时拒绝调用；错误信息不包含凭据，正常业务字段和记录编号不因此重写。 */
    public static void requireSafeText(String input) {
        if(input!=null && CREDENTIALS.stream().anyMatch(p->p.matcher(input).find()))
            throw new ApiException(422,"输入包含疑似密码、令牌或密钥，未发送给模型，请移除后重试");
    }
    /** 对日志、工具结果及展示上下文删除明确的凭据样式；不输出原始值。 */
    public static String redact(String input) {
        if(input==null)return null;
        String safe=input;
        for(var pattern:CREDENTIALS)safe=pattern.matcher(safe).replaceAll("[凭据已脱敏]");
        return safe;
    }
    /** 保持工具调用与回复配对标识，清理所有实际发送的消息内容；SDK鉴权配置不属于业务消息。 */
    public static List<Message> messages(List<Message> source) {
        return source.stream().map(ModelEgressPolicy::message).toList();
    }
    private static Message message(Message message) {
        if(message instanceof ToolResponseMessage tool)
            return ToolResponseMessage.builder().responses(tool.getResponses().stream().map(r->new ToolResponseMessage.ToolResponse(r.id(),r.name(),content(r.responseData()))).toList()).build();
        if(message instanceof AssistantMessage assistant)
            return AssistantMessage.builder().content(content(assistant.getText())).toolCalls(assistant.getToolCalls().stream().map(c->new AssistantMessage.ToolCall(c.id(),c.type(),c.name(),content(c.arguments()))).toList()).build();
        if(message instanceof UserMessage)return new UserMessage(content(message.getText()));
        if(message instanceof SystemMessage)return new SystemMessage(content(message.getText()));
        throw new ApiException(422,"模型消息类型不受支持");
    }
    /** 结构化消息按字段名清理，普通文本按内容清理；空消息保持为空，不放宽为默认业务事实。 */
    private static String content(String text) {
        if(text==null)return "";
        try {return JsonUtil.toJson(SensitiveData.forModel(JsonUtil.MAPPER.readTree(text)));}
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid){return SensitiveData.text(text);}
    }
}
