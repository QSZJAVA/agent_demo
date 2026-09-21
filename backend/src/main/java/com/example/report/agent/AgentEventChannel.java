package com.example.report.agent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次对话请求的结构化事件通道：工具在执行中把 preview / plan / result 事件推进来，
 * AgentChatService 把它与模型文本流合并后作为 SSE 输出。
 */
public class AgentEventChannel {

    public static final String CONTEXT_KEY = "eventChannel";

    private final Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

    /** 本轮已发出过的事件类型，供服务端兜底校验（例如判断是否真的生成过待确认清单） */
    private final Set<String> emittedTypes = ConcurrentHashMap.newKeySet();

    /** 本轮真正生成过的预览编号，用来识别模型复述或编造的编号 */
    private final Set<String> previewIds = ConcurrentHashMap.newKeySet();

    public synchronized void emit(String type, Object data) {
        emittedTypes.add(type);
        if (AgentEvent.PREVIEW.equals(type) && data instanceof PreviewPayload payload && payload.previewId() != null) {
            previewIds.add(payload.previewId().toLowerCase(Locale.ROOT));
        }
        sink.tryEmitNext(new AgentEvent(type, data));
    }

    /** 本轮是否发过某类结构化事件 */
    public boolean hasEmitted(String type) {
        return emittedTypes.contains(type);
    }

    /** 本轮真实生成过的预览编号（小写） */
    public Set<String> previewIds() {
        return Set.copyOf(previewIds);
    }

    public synchronized void complete() {
        sink.tryEmitComplete();
    }

    public Flux<AgentEvent> asFlux() {
        return sink.asFlux();
    }
}
