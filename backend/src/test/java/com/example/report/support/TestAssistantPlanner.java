package com.example.report.support;

import com.example.report.assistant.*;
import com.example.report.catalog.CatalogEntry;
import com.example.report.semantic.DialogueState;
import com.example.report.semantic.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.*;

/** 派单程序回归的完整任务替身；只在测试属性下用测试解析器构造协议，不作为生产语义回退或真实模型证据。 */
@Component @Primary
@ConditionalOnProperty(prefix="agent.llm",name="mock",havingValue="true")
public class TestAssistantPlanner implements AssistantPlanner {
    private final IntentParser parser;
    public TestAssistantPlanner(IntentParser parser){this.parser=parser;}
    @Override public IntentParser.Source source(){return IntentParser.Source.MOCK;}
    @Override public AssistantPlan plan(String message,DialogueState state,List<CatalogEntry> reports,Set<String> companies) {
        var intent=parser.parse(message,new IntentParser.Context(state,reports.stream().map(CatalogEntry::ref).toList(),List.of(),
                SemanticCapabilities.selectors(reports),draft->{},SemanticCapabilities.fields(reports)));
        if(intent.action()==SemanticIntent.Action.HELP)return new AssistantPlan(AssistantPlan.Route.HELP,null,false,null);
        if(intent.action()==SemanticIntent.Action.CLARIFY)return new AssistantPlan(AssistantPlan.Route.CLARIFY,null,false,"请明确本轮对象及操作");
        boolean plan=intent.action()==SemanticIntent.Action.CANCEL_PLAN || intent.action()==SemanticIntent.Action.SHOW_RESULT;
        var source=plan?DispatchDirective.Source.PLAN:state.getPreviewId()!=null?DispatchDirective.Source.PREVIEW:DispatchDirective.Source.EXPLICIT_SCOPE;
        String ref=plan?AssistantReferences.planRef(state):source==DispatchDirective.Source.PREVIEW?AssistantReferences.previewRef(state):null;
        return AssistantPlan.dispatch(new DispatchDirective(intent,source,ref,List.of(),message));
    }
}
