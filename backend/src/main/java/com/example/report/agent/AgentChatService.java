package com.example.report.agent;

import com.example.report.permission.CurrentUser;
import com.example.report.dispatch.RecordKey;
import com.example.report.semantic.SemanticConversationService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import java.util.List;

/** 最终演示版对话入口；自由文本统一进入V1语义服务，不切换为旧工具对话或关键词兜底。 */
@Service
public class AgentChatService {
    private final SemanticConversationService semantic;
    private final String modelName;
    public AgentChatService(SemanticConversationService semantic,ChatModel model) {
        this.semantic=semantic;
        String configured=model.getDefaultOptions()==null?null:model.getDefaultOptions().getModel();
        this.modelName=configured==null?model.getClass().getSimpleName():configured;
    }
    /** 返回实际语义模型名称供页面展示，不据此推断模型或业务服务已经可用。 */
    public String modelName() {return semantic.modelName(modelName);}
    /** 复合记录键只在所属预览内生效；确认派单使用独立接口，不能由模型文本触发。 */
    public Flux<ServerSentEvent<Object>> chat(CurrentUser user,String conversationId,String message,String uiPreviewId,List<RecordKey> excludedRecords) {
        return semantic.chat(user,conversationId,message,uiPreviewId,excludedRecords,modelName());
    }
}
