package com.example.report.catalog.query;

import java.time.LocalDateTime;

/**
 * 本仓库业务表的派单状态写入边界；仅允许在业务事务内锁定记录、复核当前条件并回写。
 */
public interface DispatchStatusWriter {

    /**
     * 在调用方事务中锁定唯一来源记录，强制租户与公司范围并执行当前事实复核；不支持时拒绝写入。
     */
    default boolean markDispatchedGuarded(String tenantId, String recordId, String companyCode,
                                          LocalDateTime dispatchedAt, java.util.function.Predicate<FactRow> eligible) {
        throw new UnsupportedOperationException("报表适配器尚未实现原子派单复核");
    }
}
