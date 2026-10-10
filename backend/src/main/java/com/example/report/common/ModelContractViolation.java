package com.example.report.common;

/** 可修正的模型契约错误；将用户可读原因与模型修正反馈分离，不把内部协议说明塞进页面提示。 */
public class ModelContractViolation extends ApiException {
    private final String feedback;
    /**
     * 只表示422契约错误，不能包装权限、版本或业务服务故障以诱导更换目标。
     * @param message 可安全展示给用户的原因，不包含凭据或底层实现信息
     * @param feedback 用于本轮有界模型修正的约束说明，不代填用户意图或自动执行修正
     */
    public ModelContractViolation(String message,String feedback) {super(422,message);this.feedback=feedback;}
    /** 返回模型可用的契约反馈；调用方仍须执行原文、权限和完整计划验证。 */
    public String feedback(){return feedback;}
}
