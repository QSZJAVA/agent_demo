package com.example.report.mock;

import com.example.report.common.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地演示用的关键词模拟模型（agent.llm.mock=true 时启用，配合 --spring.profiles.active=mock）。
 * 用与真实模型完全相同的工具调用协议驱动 DispatchTools，让前后端流程无需 API Key 即可跑通。
 * 真实环境下不会加载。
 */
@Slf4j
@Primary
@Component
@ConditionalOnProperty(prefix = "agent.llm", name = "mock", havingValue = "true")
public class MockChatModel implements ChatModel {

    private static final Pattern DOC_NO = Pattern.compile("(?i)(SO\\d{6,}|INV-\\d{4}-\\d{4}|EXP-\\d{4}-\\d{4})");
    private static final Pattern COMPANY = Pattern.compile("(?i)(?:我|我的)?([A-Z][A-Z0-9_-]{0,31})公司");
    /** 排除意图词 */
    private static final Pattern REMOVE_WORD = Pattern.compile("(?:删掉|删除|去掉|移除|排除|不要)");
    /** 后置语序的报表排除：应收的也删掉 / 应收报表去掉 */
    private static final Pattern REMOVE_REPORT_AFTER =
            Pattern.compile("(销售|应收|费用)报表?(?:的)?(?:也|都)?(?:删掉|删除|去掉|移除|排除)");
    /** 前置语序的报表排除：不要费用报表 / 排除应收报表 */
    private static final Pattern REMOVE_REPORT_BEFORE =
            Pattern.compile("(?:删掉|删除|去掉|移除|排除|不要)(销售|应收|费用)报表?");
    /** "把云服务删掉" 里被删掉的对象，作为摘要关键词传给预览 */
    private static final Pattern REMOVE_KEYWORD =
            Pattern.compile("(?:把|将)([^,，。;；\\s]{1,12}?)(?:删掉|删除|去掉|移除|排除)");

    private final ToolCallingManager toolCallingManager = DefaultToolCallingManager.builder().build();
    private final ChatOptions defaultOptions = ToolCallingChatOptions.builder().model("mock-keyword-model").build();

    @Override
    public ChatOptions getDefaultOptions() {
        return defaultOptions;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = generate(prompt);
        if (ToolCallingChatOptions.isInternalToolExecutionEnabled(prompt.getOptions()) && response.hasToolCalls()) {
            ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
            if (result.returnDirect()) {
                return ChatResponse.builder().from(response).generations(ToolExecutionResult.buildGenerations(result)).build();
            }
            return call(new Prompt(result.conversationHistory(), prompt.getOptions()));
        }
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 真实模型是逐块流式输出；这里把最终回复切成小块，让前端的流式拼接逻辑同样被验证
        return Flux.defer(() -> {
            ChatResponse full = call(prompt);
            String text = full.getResult() == null ? "" : full.getResult().getOutput().getText();
            if (text == null || text.isEmpty()) {
                return Flux.just(full);
            }
            List<ChatResponse> chunks = new ArrayList<>();
            for (int i = 0; i < text.length(); i += 6) {
                String piece = text.substring(i, Math.min(text.length(), i + 6));
                boolean last = i + 6 >= text.length();
                chunks.add(new ChatResponse(List.of(new Generation(new AssistantMessage(piece))),
                        last ? full.getMetadata() : ChatResponseMetadata.builder().model("mock-keyword-model").build()));
            }
            return Flux.fromIterable(chunks);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 模拟一次模型推理：根据最后一条消息决定回复文本或工具调用 */
    private ChatResponse generate(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        Message last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (last instanceof ToolResponseMessage toolResponse) {
            return text(summarizeToolResult(toolResponse));
        }
        String userText = messages.stream()
                .filter(m -> m.getMessageType() == MessageType.USER)
                .reduce((a, b) -> b).map(Message::getText).orElse("");
        String t = userText.trim();

        boolean mentionsDispatch = t.contains("派单") || t.contains("派一下") || t.contains("派了") || t.contains("都派") || t.contains("派掉");
        boolean wantsExecute = mentionsDispatch && (t.contains("剩下") || t.contains("其余") || t.contains("其他") || t.contains("全部")
                || t.contains("都") || t.contains("帮我派") || t.contains("发起") || t.contains("执行") || t.contains("确认") || t.contains("派掉"));
        boolean wantsRemove = REMOVE_WORD.matcher(t).find();
        boolean wantsPreview = (t.contains("查") || t.contains("看") || t.contains("有哪些") || t.contains("哪些") || t.contains("预览")
                || t.contains("列") || t.contains("待派") || t.contains("可以派") || t.contains("能派")
                || t.contains("加上") || t.contains("还要") || t.contains("再加上") || wantsRemove) && !wantsExecute;

        if (wantsExecute) {
            Set<String> excludes = new LinkedHashSet<>();
            if (t.contains("不") || t.contains("除") || t.contains("排除") || t.contains("别")) {
                Matcher m = DOC_NO.matcher(t);
                while (m.find()) {
                    excludes.add(m.group(1).toUpperCase());
                }
            }
            Map<String, Object> args = excludes.isEmpty() ? Map.of() : Map.of("excludeDocNos", new ArrayList<>(excludes));
            return toolCall("dispatch", args);
        }
        if (wantsPreview || mentionsDispatch) {
            Map<String, Object> args = new java.util.LinkedHashMap<>();
            List<String> reportTypes = wantsRemove ? remainingReportTypes(messages, t) : mentionedReportTypes(t);
            if (!reportTypes.isEmpty()) args.put("reportType", String.join(",", reportTypes));
            Matcher companyMatcher = COMPANY.matcher(t);
            String companyCode = companyMatcher.find() ? companyMatcher.group(1).toUpperCase() : "";
            if (!companyCode.isEmpty()) args.put("companyCode", companyCode);
            List<String> keywords = removeKeywords(t);
            if (!keywords.isEmpty()) args.put("excludeDocNos", keywords);
            return toolCall("previewDispatchable", args);
        }
        return text("我是派单助手（本地模拟模型）。你可以说：\"查一下我有哪些可以派单\"，或者\"我不想派 SO2026002，剩下的帮我派单吧\"。");
    }

    /** 用户一句话里明确提到的报表类型 */
    private static List<String> mentionedReportTypes(String t) {
        List<String> reportTypes = new ArrayList<>();
        if (t.contains("销售")) reportTypes.add("sales");
        if (t.contains("应收")) reportTypes.add("receivable");
        if (t.contains("费用")) reportTypes.add("expense");
        return reportTypes;
    }

    /** 排除场景：先按历史推断当前预览范围，再减去用户明确要删掉的报表 */
    private static List<String> remainingReportTypes(List<Message> messages, String t) {
        Set<String> scope = new LinkedHashSet<>();
        for (Message m : messages) {
            if (m.getMessageType() == MessageType.USER && m.getText() != null && t.equals(m.getText().trim())) {
                break;
            }
            if (m.getMessageType() == MessageType.TOOL) {
                continue;
            }
            String text = m.getText();
            if (text == null) {
                continue;
            }
            if (text.contains("销售")) scope.add("sales");
            if (text.contains("应收")) scope.add("receivable");
            if (text.contains("费用")) scope.add("expense");
        }
        scope.removeAll(excludedReportTypes(t));
        return new ArrayList<>(scope);
    }

    /** 用户明确要删掉/排除的报表类型 */
    private static List<String> excludedReportTypes(String t) {
        List<String> excluded = new ArrayList<>();
        Matcher after = REMOVE_REPORT_AFTER.matcher(t);
        while (after.find()) {
            String code = codeOf(after.group(1));
            if (code != null && !excluded.contains(code)) {
                excluded.add(code);
            }
        }
        Matcher before = REMOVE_REPORT_BEFORE.matcher(t);
        while (before.find()) {
            String code = codeOf(before.group(1));
            if (code != null && !excluded.contains(code)) {
                excluded.add(code);
            }
        }
        return excluded;
    }

    /** "把云服务删掉" 里被删掉的对象，作为摘要关键词传给预览 */
    private static List<String> removeKeywords(String t) {
        List<String> keys = new ArrayList<>();
        Matcher m = REMOVE_KEYWORD.matcher(t);
        while (m.find()) {
            String key = m.group(1).trim();
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static String codeOf(String word) {
        if (word.startsWith("销售")) return "sales";
        if (word.startsWith("应收")) return "receivable";
        if (word.startsWith("费用")) return "expense";
        return null;
    }

    private static ChatResponse toolCall(String name, Map<String, Object> args) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_" + JsonUtil.newId().substring(0, 8), "function", name, JsonUtil.toJson(args))))
                .build();
        return new ChatResponse(List.of(new Generation(message)), metadata());
    }

    private static ChatResponse text(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))), metadata());
    }

    private static ChatResponseMetadata metadata() {
        return ChatResponseMetadata.builder().model("mock-keyword-model").usage(new DefaultUsage(0, 0)).build();
    }

    /** 把工具返回的 JSON 变成一段像模型会说的话 */
    @SuppressWarnings("unchecked")
    private static String summarizeToolResult(ToolResponseMessage toolResponse) {
        StringBuilder sb = new StringBuilder();
        for (ToolResponseMessage.ToolResponse r : toolResponse.getResponses()) {
            Map<String, Object> data = JsonUtil.toMap(r.responseData());
            String status = String.valueOf(data.get("status"));
            if ("error".equals(status)) {
                sb.append("操作没有完成：").append(data.get("message")).append('\n');
                continue;
            }
            if ("previewDispatchable".equals(r.name())) {
                sb.append("已按当前生效的派单规则查询完毕，共找到 ").append(data.get("total")).append(" 条应派单记录：\n");
                Object byReport = data.get("byReport");
                if (byReport instanceof List<?> list) {
                    for (Object o : list) {
                        Map<String, Object> rep = (Map<String, Object>) o;
                        sb.append("- ").append(rep.get("reportName")).append("：").append(rep.get("count")).append(" 条");
                        if (rep.get("ruleDescription") != null) {
                            sb.append("（规则：").append(rep.get("ruleDescription")).append("）");
                        }
                        sb.append('\n');
                    }
                }
                sb.append("完整清单见上方表格。你可以说\"剩下的帮我派单吧\"，或先告诉我不想派哪些单据。");
            } else if ("dispatch".equals(r.name())) {
                if ("pending_confirm".equals(status)) {
                    sb.append("已生成待确认的派单清单，共 ").append(data.get("count")).append(" 条");
                    Object excluded = data.get("excluded");
                    if (excluded instanceof List<?> ex && !ex.isEmpty()) {
                        sb.append("，已排除：").append(String.join("、", ex.stream().map(String::valueOf).toList()));
                    }
                    sb.append("。请在上方确认卡片点击\"确认派单\"后才会真正执行。");
                } else {
                    sb.append("派单已执行：成功 ").append(data.get("successCount")).append(" 条，失败 ").append(data.get("failedCount")).append(" 条。");
                }
            } else {
                sb.append(r.responseData());
            }
        }
        return sb.toString().trim();
    }
}
