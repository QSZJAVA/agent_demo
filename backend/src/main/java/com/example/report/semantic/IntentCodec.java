package com.example.report.semantic;

import com.example.report.catalog.TextNormalizer;
import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Schema shape and source grounding are checked separately from business permissions. */
@Component
public class IntentCodec {
    private final ObjectMapper mapper = JsonUtil.MAPPER.copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public String schema() {
        try (var in = new ClassPathResource("semantic/intent-v1.schema.json").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException("语义协议加载失败", error); }
    }
    public SemanticIntent decode(String json, String message) {
        if (json == null || json.length() > 16000) throw invalid();
        String content = json.trim();
        if (content.startsWith("```json") && content.endsWith("```")) content = content.substring(7, content.length()-3).trim();
        try {
            var intent = mapper.readValue(content, SemanticIntent.class);
            validate(intent, message);
            return intent;
        } catch (Exception failure) { throw new InvalidOutput(content); }
    }
    public void validate(SemanticIntent intent, String message) {
        if (intent == null || intent.version() != 1 || intent.action() == null || intent.clarify() == null) throw invalid();
        for (var change : java.util.Arrays.asList(intent.company(), intent.reports(), intent.exclusions())) {
            if (change == null || change.operation() == null || change.mentions() == null || change.evidence() == null
                    || change.mentions().size() > 20 || change.evidence().length() > 1000) throw invalid();
            if (change.operation() == SemanticIntent.Operation.KEEP) {
                if (!change.mentions().isEmpty() || !change.evidence().isEmpty()) throw invalid();
                continue;
            }
            if (change.evidence().isBlank() || !contains(message, change.evidence())) throw invalid();
            if (change.operation() == SemanticIntent.Operation.CLEAR) {
                if (!change.mentions().isEmpty()) throw invalid();
            } else if (change.mentions().isEmpty()) throw invalid();
            for (String mention : change.mentions()) {
                if (mention == null || mention.isBlank() || mention.length() > 160
                        || !contains(change.evidence(), mention)) throw invalid();
            }
        }
        if (!Set.of(SemanticIntent.Operation.KEEP, SemanticIntent.Operation.REPLACE, SemanticIntent.Operation.CLEAR)
                .contains(intent.company().operation()) || intent.company().mentions().size() > 1) throw invalid();
        if ((intent.action() == SemanticIntent.Action.CLARIFY) != (intent.clarify() != SemanticIntent.Clarify.NONE)) throw invalid();
        if (Set.of(SemanticIntent.Action.HELP, SemanticIntent.Action.CANCEL_PLAN, SemanticIntent.Action.SHOW_RESULT).contains(intent.action())
                && (intent.company().operation()!=SemanticIntent.Operation.KEEP || intent.reports().operation()!=SemanticIntent.Operation.KEEP
                || intent.exclusions().operation()!=SemanticIntent.Operation.KEEP)) throw invalid();
    }
    private static boolean contains(String source, String part) {
        return source != null && TextNormalizer.normalize(source).contains(TextNormalizer.normalize(part));
    }
    private static ApiException invalid() { return new ApiException(422, "未能可靠识别本次操作，请明确要查询的公司、报表或派单动作"); }
    /** Available to the opt-in synthetic-corpus evaluation, never included in user responses. */
    static final class InvalidOutput extends ApiException {
        private final String output;
        InvalidOutput(String output) { super(422,"未能可靠识别本次操作，请明确要查询的公司、报表或派单动作");this.output=output; }
        String output() { return output; }
    }
}
