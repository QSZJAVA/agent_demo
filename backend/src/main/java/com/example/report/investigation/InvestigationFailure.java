package com.example.report.investigation;

/** 调查的受控终止原因；只保存固定原因和安全摘要，不输出供应商原始异常或凭据。 */
public class InvestigationFailure extends RuntimeException {
    private final String reason;
    public InvestigationFailure(String reason, String message) { super(message); this.reason = reason; }
    public String reason() { return reason; }
}
