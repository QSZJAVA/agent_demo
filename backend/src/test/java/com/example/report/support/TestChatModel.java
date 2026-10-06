package com.example.report.support;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 仅供程序回归装配的模型占位符；不实现旧工具协议，意图样本由测试解析器提供。 */
@Component
@Primary
@ConditionalOnProperty(prefix="agent.llm",name="mock",havingValue="true")
public class TestChatModel implements ChatModel {
    @Override public ChatOptions getDefaultOptions() { return ChatOptions.builder().model("test-only").build(); }
    @Override public ChatResponse call(Prompt prompt) { throw new IllegalStateException("测试占位模型不得接管真实解析调用"); }
}
