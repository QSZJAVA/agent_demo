package com.example.report.investigation;

import java.util.List;
import java.util.Map;

/** 调查当前协议 DTO；仅面向最新格式，不提供旧数据转换或旧字段兜底。 */
public final class InvestigationTypes {
    private InvestigationTypes() { }
    /**
     * 页面明确绑定清单和目标条目，不接受模型或浏览器指定身份。
     * @param planId 当前派单清单标识
     * @param itemIds 条目主键字符串数组；null 表示全部异常，空数组非法
     * @param question 本轮问题，最多1000字符；只调整关注点，不扩大范围
     */
    public record Request(String planId, List<String> itemIds, String question) { }
    /**
     * 当前授权用户的调查展示快照。
     * @param id 调查运行标识
     * @param planId 绑定清单标识
     * @param status QUEUED、RUNNING或当前终态
     * @param stopReason 终止原因；运行中为空
     * @param message 脱敏状态摘要；无摘要时为空
     * @param idempotencyKey 创建稳定键，供丢失响应恢复
     * @param itemRefs 调查覆盖的条目引用与展示摘要
     * @param report 已校验报告；尚无合法报告时为空
     * @param sourceChangedSinceRun 当前来源是否已不同于执行快照
     * @param activeStep 尚在执行的步骤概要；无时为空
     * @param usage 用量、完整性与计数；缺失token为null
     * @param createdAt UTC创建时间的ISO文本
     * @param finishedAt UTC结束时间文本；未结束为空
     */
    public record Run(String id, String planId, String status, String stopReason, String message, String idempotencyKey,
                      List<Map<String,Object>> itemRefs, Object report, boolean sourceChangedSinceRun,
                      Object activeStep, Object usage, String createdAt, String finishedAt) { }
    /**
     * 有界游标读取结果。
     * @param records 当前授权范围内的记录
     * @param nextCursor 下一页位置字符串；无后续为空
     */
    public record Page(List<Map<String,Object>> records, String nextCursor) { }
}
