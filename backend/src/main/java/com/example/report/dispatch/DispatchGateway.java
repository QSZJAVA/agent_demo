package com.example.report.dispatch;

import com.example.report.catalog.CatalogEntry;
import com.example.report.rule.Candidate;

/**
 * 现有派单接口的抽象：真实系统在这里调用已有的派单服务
 */
public interface DispatchGateway {

    Outcome dispatch(DispatchRequest request);

    /**
     * @param externalRequestId 幂等请求号：同一清单条目每次调用都一样，派单接口可据此去重
     */
    record DispatchRequest(String tenantId, String externalRequestId, CatalogEntry report, Candidate record) {
    }

    record Outcome(boolean success, String errorCode, String message) {
        public static Outcome ok() {
            return new Outcome(true, null, "派单成功");
        }

        public static Outcome fail(String errorCode, String message) {
            return new Outcome(false, errorCode, message);
        }
    }
}
