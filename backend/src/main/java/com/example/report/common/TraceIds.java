package com.example.report.common;

import org.slf4j.MDC;

import java.util.regex.Pattern;

/**
 * 请求链路号：每个请求一个，写进日志（MDC）和审计记录，派单结果能反查到当次请求的全部日志
 */
public final class TraceIds {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private TraceIds() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 上游传入的链路号合法就沿用，否则新生成 */
    public static String accept(String incoming) {
        return incoming != null && VALID.matcher(incoming).matches() ? incoming : JsonUtil.newId().substring(0, 16);
    }
}
