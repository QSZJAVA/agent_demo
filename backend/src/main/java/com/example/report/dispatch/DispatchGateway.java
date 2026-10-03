package com.example.report.dispatch;

import com.example.report.catalog.CatalogEntry;
import com.example.report.rule.Candidate;

/**
 * 现有派单接口的抽象：真实系统在这里调用已有的派单服务
 */
public interface DispatchGateway {

    Outcome dispatch(DispatchRequest request);

    /** 按幂等请求号查询外部结果；真实网关必须实现，查不到时返回 UNKNOWN 而不能猜测。 */
    default Lookup lookup(String tenantId, String externalRequestId) {
        return new Lookup(LookupStatus.UNKNOWN, null, "外部系统未提供请求号查询");
    }

    enum LookupStatus { SUCCESS, FAILED, NOT_FOUND, UNKNOWN }

    /**
     * 稳定请求号对应的业务核对结论。
     * @param status SUCCESS明确成功、FAILED明确失败、NOT_FOUND未受理或UNKNOWN仍待核对
     * @param errorCode 业务失败原因编码
     * @param message 可展示的操作摘要或失败原因，禁止包含凭据
     */
    record Lookup(LookupStatus status, String errorCode, String message) { }

    /**
     * 受控网关派单请求，必须绑定已确认清单及执行轮次。
     * 网关须在业务写入的同一事务复核来源公司的权限、待派单状态及规则版本，单独先查询无法消除并发窗口。
     * @param tenantId 数据所属租户标识，来自服务端身份
     * @param externalRequestId 外部派单稳定幂等请求号，核对与重试沿用
     * @param report 经过权限与版本校验的目录定义
     * @param record 本次发送所对应的候选记录快照
     * @param enforceRules 是否按自动规则复核；人工选定范围仍须权限与状态校验
     * @param executionVersion 认领时冻结的清单执行轮次，旧轮次不能发送或回写
     */
    record DispatchRequest(String tenantId, String externalRequestId, CatalogEntry report, Candidate record,
                           boolean enforceRules, long executionVersion) {
        /** 本地适配器的旧构造入口；带认证的远程写入必须显式传入有效执行版本。 */
        public DispatchRequest(String tenantId, String externalRequestId, CatalogEntry report, Candidate record,
                           boolean enforceRules) {
            this(tenantId, externalRequestId, report, record, enforceRules, 0);
        }
    }

    /**
     * 业务网关的明确执行结果。
     * @param success 是否得到业务服务明确的成功结果
     * @param errorCode 业务失败原因编码
     * @param message 可展示的操作摘要或失败原因，禁止包含凭据
     */
    record Outcome(boolean success, String errorCode, String message) {
        public static Outcome ok() {
            return new Outcome(true, null, "派单成功");
        }

        public static Outcome fail(String errorCode, String message) {
            return new Outcome(false, errorCode, message);
        }
    }
}
