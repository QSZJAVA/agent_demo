package com.example.report.semantic;

import com.example.report.catalog.TextNormalizer;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.example.report.semantic.SemanticIntent.*;

/**
 * 真实模型输出的协议与原文证据校验边界；拒绝未知字段、缺失字段、隐式类型转换及多余JSON。
 * 只检验结构和业务不变量，不用关键词构造意图；历史证据的读取兼容不改变当前V2模型协议。
 */
@Component
public class IntentCodec {
    private final ObjectMapper mapper = JsonUtil.MAPPER.copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    public String schema() {
        try (var in = new ClassPathResource("semantic/intent-v2.schema.json").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("语义协议加载失败", error); }
    }
    /** 解析有界JSON并校验意图；任何解析或协议失败都返回 InvalidOutput，交由模型格式修复或对话澄清处理。 */
    public SemanticIntent decode(String json, String message) {
        if (json == null || json.length() > 24000) throw invalid();
        String content = json.trim();
        if (content.startsWith("```json") && content.endsWith("```")) content = content.substring(7, content.length()-3).trim();
        try {
            var intent = mapper.readValue(content, SemanticIntent.class);
            validate(intent, message);
            return intent;
        } catch (Exception failure) { throw new InvalidOutput(content); }
    }
    /** 要求每次修改和禁止都有本轮连续原文证据，并校验范围顺序、单家公司、修改数量及动作冲突。*/
    public void validate(SemanticIntent intent, String message) {
        if (intent == null || intent.version()!=2 || intent.action()==null || intent.clarify()==null
                || intent.scopeChanges()==null || intent.restrictions()==null
                || intent.scopeChanges().size()>8 || intent.restrictions().size()>7) throw invalid();
        boolean recordsStarted=false;
        int companies=0;
        for (var c : intent.scopeChanges()) {
            if (c==null || c.target()==null || c.operation()==null || c.operation()==Operation.KEEP
                    || c.mentions()==null || c.mentions().size()>20 || !grounded(message,c.evidence())) throw invalid();
            if (c.operation()==Operation.CLEAR ? !c.mentions().isEmpty() : c.mentions().isEmpty()) throw invalid();
            for (String mention : c.mentions())
                if (mention==null || mention.isBlank() || mention.length()>160 || !contains(c.evidence(),mention)) throw invalid();
            if (c.target()==Target.COMPANY && (++companies>1 || c.mentions().size()>1
                    || !Set.of(Operation.REPLACE,Operation.CLEAR).contains(c.operation()))) throw invalid();
            if (c.target()==Target.RECORDS) recordsStarted=true;
            else if (recordsStarted) throw invalid();
        }
        Set<Action> forbidden=new HashSet<>();
        for (var r : intent.restrictions()) {
            if (r==null || r.action()==null || r.action()==Action.CLARIFY || r.scope()!=RestrictionScope.THIS_TURN
                    || !grounded(message,r.evidence()) || !forbidden.add(r.action())) throw invalid();
        }
        if (intent.forbids(intent.action())) throw new ApiException(422,"本次动作与明确禁止的动作冲突，请说明是仅查询还是生成清单");
        if ((intent.action()==Action.CLARIFY)!=(intent.clarify()!=Clarify.NONE)) throw invalid();
        if (Set.of(Action.HELP,Action.CANCEL_PLAN,Action.SHOW_RESULT,Action.CLARIFY).contains(intent.action())
                && !intent.scopeChanges().isEmpty()) throw invalid();
    }
    private static boolean grounded(String source,String evidence) {
        return evidence!=null && !evidence.isBlank() && evidence.length()<=1000 && contains(source,evidence);
    }
    private static boolean contains(String source, String part) {
        return source != null && TextNormalizer.normalize(source).contains(TextNormalizer.normalize(part));
    }
    /** Read-only compatibility for stored V1 evidence. Live model output must always be V2. */
    static SemanticIntent fromStoredV1(JsonNode node) throws java.io.IOException {
        return new SemanticIntent(2, Action.valueOf(node.required("action").asText()),
                JsonUtil.MAPPER.treeToValue(node.required("company"),Change.class),
                JsonUtil.MAPPER.treeToValue(node.required("reports"),Change.class),
                JsonUtil.MAPPER.treeToValue(node.required("exclusions"),Change.class),
                Clarify.valueOf(node.required("clarify").asText()));
    }
    private static ApiException invalid() { return new ApiException(422, "未能可靠识别本次操作，请明确要查询的公司、报表或派单动作"); }
    static final class InvalidOutput extends ApiException {
        private final String output;
        InvalidOutput(String output) { super(422,"未能可靠识别本次操作，请明确要查询的公司、报表或派单动作");this.output=output; }
        String output() { return output; }
    }
}
