package com.example.report.semantic;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** One interpretation path for all free text; UI commands use deterministic REST services. */
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
