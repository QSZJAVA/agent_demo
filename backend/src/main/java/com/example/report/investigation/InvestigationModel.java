package com.example.report.investigation;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 一次真实模型请求的边界；循环由调查Agent控制，传输测试可独立注入响应但不代表真实模型验收。 */
public interface InvestigationModel {
    /**
     * 模型的可观察响应，不包含内部思考。
     * @param message 助手文本和工具请求，工具由程序校验后执行
     * @param finishReason 供应商结束原因；length表示输出未完成
     * @param usage 允许的用量字段；缺失计量用null表示
     */
    record Reply(AssistantMessage message, String finishReason, Map<String,Object> usage) { }
    /** 单次调用不得自动重试，timeout包含传输等待；report阶段不注册任何工具。 */
    Reply call(List<Message> messages,List<ToolCallback> tools,boolean report,Duration timeout);
    /** 返回不含凭据的实际模型配置，写入运行和评估清单。 */
    Map<String,Object> configuration();
}
