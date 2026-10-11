package com.example.report.analysis;

import com.example.report.common.ModelUsage;
import java.time.Duration;
import java.util.Map;

/** 有限分析模型端口；只返回结构化文本与实际计量，工具执行始终由受控分析器负责。 */
public interface AnalysisModel {
    /**
     * 单次真实模型结果；缺失用量保留未知，不推算费用。
     * @param json 结构化响应文本，尚须按对应阶段严格验证
     * @param usage 原生token用量
     * @param elapsedMs 本次真实传输耗时，毫秒
     * @param model 实际配置的模型名称
     */
    record Reply(String json,ModelUsage usage,long elapsedMs,String model) { }
    Reply call(String instructions,Object input,String schema,Duration timeout);
    Map<String,Object> configuration();
}
