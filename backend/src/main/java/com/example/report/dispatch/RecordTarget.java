package com.example.report.dispatch;

import com.example.report.common.ApiException;

/**
 * 已绑定的业务目标，仅证明记录身份，不能代替当前权限、资格或确认。
 * @param key 报表与来源记录的复合稳定标识
 * @param companyCode 引用时的公司代码；准备清单时公司变化须重新核对
 */
public record RecordTarget(RecordKey key,String companyCode) {
    public RecordTarget {
        if(key==null || key.reportId()==null || key.reportId().isBlank() || key.recordId()==null || key.recordId().isBlank()
                || companyCode==null || companyCode.isBlank())throw new ApiException(422,"派单目标缺少稳定记录身份或所属公司");
    }
}
