package com.example.report.catalog.query;

import java.time.LocalDateTime;

/**
 * 能把记录标记为已派单的适配器（模拟派单接口用）。真实系统由派单接口负责状态回写，不需要实现它。
 */
public interface DispatchStatusWriter {

    /** 在调用方事务内更新唯一的待派单记录；非唯一影响行数必须抛异常回滚。 */
    boolean markDispatched(String tenantId, String recordId, LocalDateTime dispatchedAt);

    /** Lock the source row, check fresh facts and write within the caller's transaction.
     * Adapters without this guarantee must not fall back to an unchecked write. */
    default boolean markDispatchedGuarded(String tenantId, String recordId, String companyCode,
                                          LocalDateTime dispatchedAt, java.util.function.Predicate<FactRow> eligible) {
        throw new UnsupportedOperationException("报表适配器尚未实现原子派单复核");
    }
}
