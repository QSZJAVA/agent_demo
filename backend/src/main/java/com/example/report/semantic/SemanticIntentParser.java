package com.example.report.semantic;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Unambiguous domain commands compile directly; other syntax is interpreted by the model. */
@Primary
@Component
public class SemanticIntentParser implements IntentParser {
    private final DomainIntentParser domain=new DomainIntentParser();
    private final ModelIntentParser model;
    public SemanticIntentParser(ModelIntentParser model) { this.model=model; }
    @Override public SemanticIntent parse(String message,Context context) { return interpret(message,context).intent(); }
    @Override public Interpretation interpret(String message,Context context) {
        return domain.parse(message,context).map(intent->new Interpretation(intent,Source.DOMAIN))
                .orElseGet(()->model.interpret(message,context));
    }
}
