package com.example.report.catalog.query;

import java.time.LocalDateTime;

/**
 * 能把记录标记为已派单的适配器（模拟派单接口用）。真实系统由派单接口负责状态回写，不需要实现它。
 */
public interface DispatchStatusWriter {

    /** 仅当记录仍处于待派单状态时更新，返回是否更新成功 */
    boolean markDispatched(String tenantId, String recordId, LocalDateTime dispatchedAt);
}
