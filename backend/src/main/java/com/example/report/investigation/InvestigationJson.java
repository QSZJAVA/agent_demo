package com.example.report.investigation;

import com.example.report.common.Digests;
import com.example.report.common.JsonUtil;
import com.fasterxml.jackson.databind.SerializationFeature;

/** 调查专用确定性JSON；递归排序对象键，避免不同JVM的Map迭代顺序改变来源指纹、幂等摘要和工具契约哈希。 */
public final class InvestigationJson {
    private static final com.fasterxml.jackson.databind.ObjectWriter WRITER=JsonUtil.MAPPER.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writer();
    private InvestigationJson() { }
    /** 数组顺序保留业务含义，对象键顺序不影响事实；仅用于当前调查格式，不转换旧数据。 */
    public static String canonical(Object value) {
        try {return WRITER.writeValueAsString(value);} catch(Exception error) {throw new IllegalStateException("调查JSON序列化失败",error);}
    }
    public static String hash(Object value) {return Digests.sha256(canonical(value));}
}
