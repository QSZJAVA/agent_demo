package com.example.report.semantic;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** 派单子流程的唯一语义解析入口；统一助手先判定业务焦点，界面确定性操作继续使用独立REST服务。 */
@Primary
@Component
public class SemanticIntentParser implements IntentParser {
    private final ModelIntentParser model;
    public SemanticIntentParser(ModelIntentParser model) { this.model=model; }
    @Override public SemanticIntent parse(String message,Context context) { return interpret(message,context).intent(); }
    @Override public Interpretation interpret(String message,Context context) {
        return model.interpret(message,context);
    }
}
