package com.example.report.mock;

import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.ReportCatalog;
import com.example.report.catalog.TextNormalizer;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 本地演示用的关键词模拟模型（agent.llm.mock=true 时启用，配合 --spring.profiles.active=mock）。
 * 用与真实模型完全相同的工具调用协议驱动 DispatchTools，让前后端流程无需 API Key 即可跑通。
 * 和真实模型一样只把用户对报表的原话交给 reportQuery，报表由服务端在目录内解析；
 * 只在“删掉的是一张报表还是一条记录”这件事上查一下报表目录的说法索引（真实模型靠上下文理解做到这一点）。
 * 真实环境下不会加载。
 */
@Slf4j
@Primary
@Component
@ConditionalOnProperty(prefix = "agent.llm", name = "mock", havingValue = "true")
public class MockChatModel implements ChatModel {

    private static final Pattern DOC_NO = Pattern.compile("(?i)(SO\\d{6,}|INV-\\d{4}-\\d{4}|EXP-\\d{4}-\\d{4}|PO-\\d{4}-\\d{4})");
    private static final Pattern COMPANY = Pattern.compile("(?i)(?:我|我的)?([A-Z][A-Z0-9_-]{0,31})公司");
    /** 排除意图词 */
    private static final Pattern REMOVE_WORD = Pattern.compile("(?:删掉|删除|去掉|移除|排除|不要)");
    /** “把云服务删掉”“把应收报表删掉”里被删掉的对象*/
    private static final Pattern REMOVE_WITH_BA =
            Pattern.compile("(?:把|将)([^,，。;；\\s]{1,12}?)(?:的)?(?:也|都)?(?:删掉|删除|去掉|移除|排除)");
    /** 句首的对象：“应收的也删掉”“应收报表去掉” */
    private static final Pattern REMOVE_LEADING =
            Pattern.compile("^(?:那)?([^,，。;；\\s把将]{1,12}?)(?:的)?(?:也|都)?(?:删掉|删除|去掉|移除|排除)");
    /** 前置语序：“不要费用报表”“排除应收报表”*/
    private static final Pattern REMOVE_TRAILING =
            Pattern.compile("(?:删掉|删除|去掉|移除|排除|不要)([^,，。;；\\s]{1,12}?)(?:的)?(?:吧)?$");

    private final ToolCallingManager toolCallingManager = DefaultToolCallingManager.builder().build();
    private final ChatOptions defaultOptions = ToolCallingChatOptions.builder().model("mock-keyword-model").build();
    private final ReportCatalog catalog;

    public MockChatModel(ReportCatalog catalog) {
        this.catalog = catalog;
    }

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
            Map<String, Object> args = new LinkedHashMap<>();
            String withoutCompany = COMPANY.matcher(t).replaceAll("");
            String removed = wantsRemove ? removedObject(withoutCompany) : null;
            if (removed != null && mentionsReport(removed)) {
                // 删掉的是一张报表：只传要去掉的报表并声明 scopeMode=remove，由服务端从上一轮预览范围中减去
                args.put("reportQuery", removed);
                args.put("scopeMode", "remove");
            } else {
                String rest = withoutCompany;
                if (removed != null) {
                    // 删掉的是一条记录：把描述作为摘要关键词传给预览，剩下的部分再看有没有说报表
                    args.put("excludeDocNos", List.of(removed));
                    rest = rest.replace(removed, "");
                }
                String reportQuery = reportQueryOf(rest);
                if (reportQuery != null) {
                    args.put("reportQuery", reportQuery);
                }
            }
            Matcher companyMatcher = COMPANY.matcher(t);
            if (companyMatcher.find()) {
                args.put("companyCode", companyMatcher.group(1).toUpperCase());
            }
            return toolCall("previewDispatchable", args);
        }
        return text("我是派单助手（本地模拟模型）。你可以说：\"查一下我有哪些可以派单\"，或者\"我不想派 SO2026002，剩下的帮我派单吧\"。");
    }

    /**
     * 用户对报表的原话：句子里提到了目录中的报表说法就整句交给服务端解析；
     * 没提到时去掉通用查询词，还剩内容（可能是错别字或目录里没有的报表）也交给服务端，什么都不剩就是泛问“全部报表”。
     */
    private String reportQueryOf(String text) {
        if (mentionsReport(text)) {
            return text;
        }
        String rest = TextNormalizer.meaningfulRemainder(text);
        return rest.length() >= 2 ? rest : null;
    }

    private boolean mentionsReport(String text) {
        Set<String> published = catalog.all().stream().filter(CatalogEntry::published).map(CatalogEntry::reportId)
                .collect(Collectors.toSet());
        return !catalog.terms().scan(TextNormalizer.normalize(text), published).isEmpty();
    }

    private static String removedObject(String t) {
        for (Pattern p : List.of(REMOVE_WITH_BA, REMOVE_LEADING, REMOVE_TRAILING)) {
            Matcher m = p.matcher(t);
            if (m.find()) {
                String key = m.group(1).trim();
                if (!key.isEmpty()) {
                    return key;
                }
            }
        }
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
            if ("not_found".equals(status)) {
                sb.append("没有找到匹配的报表，请补充完整的报表名称或业务域。\n");
                continue;
            }
            if ("ambiguous".equals(status)) {
                Object candidates = data.get("candidates");
                String names = candidates instanceof List<?> list
                        ? list.stream().map(String::valueOf).collect(Collectors.joining("、")) : "";
                sb.append("找到多个相关报表：").append(names).append("，请在下方卡片中选择要查询的报表。\n");
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
                String note = String.valueOf(data.getOrDefault("note", ""));
                if (note.contains("最接近")) {
                    sb.append("你说的报表没有精确匹配，已按最接近的报表查询，请确认是否正确。\n");
                }
                sb.append("完整清单见下方表格。你可以说\"剩下的帮我派单吧\"，或先告诉我不想派哪些单据。");
            } else if ("dispatch".equals(r.name())) {
                if ("pending_confirm".equals(status)) {
                    sb.append("已生成待确认的派单清单，共 ").append(data.get("count")).append(" 条");
                    Object excluded = data.get("excluded");
                    if (excluded instanceof List<?> ex && !ex.isEmpty()) {
                        sb.append("，已排除：").append(String.join("、", ex.stream().map(String::valueOf).toList()));
                    }
                    sb.append("。请在下方确认卡片点击\"确认派单\"后才会真正执行。");
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
