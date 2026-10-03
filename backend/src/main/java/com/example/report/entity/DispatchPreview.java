package com.example.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 派单预览快照（服务端状态的唯一事实来源）：ACTIVE → SUPERSEDED / EXPIRED / CONSUMED
 */
@Data
@TableName("dispatch_preview")
public class DispatchPreview {

    public static final String ACTIVE = "ACTIVE";
    public static final String BUILDING = "BUILDING";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String EXPIRED = "EXPIRED";
    public static final String CONSUMED = "CONSUMED";

    /** 本表记录主键。 */
    @TableId(type = IdType.INPUT)
    private String id;
    /** 数据所属租户标识；查询和写入必须限定租户。*/
    private String tenantId;
    /** 记录所属用户标识，结合租户确定数据归属。 */
    private String userId;
    /** 关联 agent_conversation.id 的会话标识；允许为空，表示尚无该项数据。*/
    private String conversationId;
    /** 预览入口来源，含semantic、agent、manual、selection、api。 */
    private String source;
    /** 范围内稳定报表标识JSON数组，按目录顺序。*/
    private String reportIds;
    /** 预览生效的公司代码JSON数组。 */
    private String companyCodes;
    /** 统一预览命令JSON，含操作、报表范围、筛选、排除及范围修改方式。*/
    private String queryJson;
    /** 预览汇总JSON；升级前快照可为空。 */
    private String summaryJson;
    /** 范围内报表目录版本的聚合指纹。*/
    private String catalogVersion;
    /** 范围内生效规则的聚合版本指纹。 */
    private String ruleVersion;
    /** 用户公司与报表权限的版本指纹，用于识别授权变化。*/
    private String permissionVersion;
    /** 快照状态：BUILDING构建中、ACTIVE有效、SUPERSEDED被替代、EXPIRED失效、CONSUMED已执行。 */
    private String status;
    /** 当前状态的原因编码，供展示和恢复判断；允许为空，表示尚无该项数据。*/
    private String statusReason;
    /** 统计记录总数。 */
    private Integer totalCount;
    /** 候选记录金额合计；小数精度2位，币种沿用来源账本。*/
    private BigDecimal totalAmount;
    /** 有效期截止时间；到期后不得继续认领或使用。 */
    private LocalDateTime expiresAt;
    /** 记录创建时间。*/
    private LocalDateTime createdAt;
    /** 记录最后更新时间。 */
    private LocalDateTime updatedAt;
}
