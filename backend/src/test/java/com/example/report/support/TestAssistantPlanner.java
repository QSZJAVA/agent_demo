package com.example.report.support;

import com.example.report.assistant.*;
import com.example.report.catalog.CatalogEntry;
import com.example.report.semantic.DialogueState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.*;

/** 既有派单程序回归的显式路由替身；只在测试源码和测试属性下装配，不证明真实模型路由能力。 */
@Component @Primary
@ConditionalOnProperty(prefix="agent.llm",name="mock",havingValue="true")
public class TestAssistantPlanner implements AssistantPlanner {
    @Override public AssistantPlan plan(String message,DialogueState state,List<CatalogEntry> reports,Set<String> companies) {
        return new AssistantPlan(AssistantPlan.Route.DISPATCH,null,false,null);
    }
}
