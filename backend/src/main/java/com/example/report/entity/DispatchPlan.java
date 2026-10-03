package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待确认派单清单：PENDING → EXECUTING → EXECUTED；执行中断时进入 REVIEW_REQUIRED，核对后才决定重试。
 * 未执行清单可 CANCELLED / EXPIRED；读取到待核对状态不能视为从未执行。
 */
@Data
@TableName("dispatch_plan")
public class DispatchPlan {

    public static final String PENDING = "PENDING";
    public static final String EXECUTING = "EXECUTING";
    public static final String REVIEW_REQUIRED = "REVIEW_REQUIRED";
    public static final String EXECUTED = "EXECUTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";

    /** 服务端生成的派单清单主键。 */
    @TableId(type = IdType.INPUT)
    private String id;
    /** 证据持久化协议版本：0历史清单，1状态与追溯事件原子保存。*/
    private Integer evidenceVersion = 0;
    /** 关联 dispatch_preview.id 的预览快照标识。 */
    private String previewId;
    /** 数据所属租户标识；查询和写入必须限定租户。*/
    private String tenantId;
    /** 记录所属用户标识，结合租户确定数据归属。 */
    private String userId;
    /** 关联 agent_conversation.id 的会话标识；允许为空，表示尚无该项数据。*/
    private String conversationId;
    /** 清单状态：PENDING待确认、EXECUTING执行、REVIEW_REQUIRED待核对、EXECUTED结束、CANCELLED取消、EXPIRED失效。 */
    private String status;
    /** 当前状态的原因编码，供展示和恢复判断；允许为空，表示尚无该项数据。*/
    private String statusReason;
    /** 清单执行轮次；每次认领执行或重试递增，旧轮次不得回写。 */
    private Long executionVersion = 0L;
    /** 用户排除的单据号JSON数组；无排除时可为空。*/
    private String excludeJson;
    /** 清单内条目总数。 */
    private Integer itemCount;
    /** 已成功派单的条目数。*/
    private Integer successCount;
    /** 当前执行汇总中的非成功条目数；具体状态与可重试数量须读取条目。 */
    private Integer failedCount;
    /** 创建清单幂等键；同租户同键只生成一份清单。*/
    private String idempotencyKey;
    /** 记录创建时间。 */
    private LocalDateTime createdAt;
    /** 有效期截止时间；到期后不得继续认领或使用。*/
    private LocalDateTime expiresAt;
    /** 用户显式确认的时间；未确认时为空。 */
    private LocalDateTime confirmedAt;
    /** 显式确认清单的用户标识；未确认时为空。*/
    private String confirmedBy;
    /** 执行结束时间；尚未结束时为空。 */
    private LocalDateTime finishedAt;
    /** 记录最后更新时间。*/
    private LocalDateTime updatedAt;
}
