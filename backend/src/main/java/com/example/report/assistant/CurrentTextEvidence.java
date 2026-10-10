package com.example.report.assistant;

import com.example.report.common.JsonUtil;
import com.example.report.common.ModelContractViolation;

/** 本轮连续原文的共同校验边界；只指出错误引用位置和值，不补齐省略文本、不修改业务计划。 */
final class CurrentTextEvidence {
    private CurrentTextEvidence() { }

    /**
     * 校验模型声明的证据片段，供目标、详细要求和条件来源共用。
     * @param source 本轮完整原文或已验证的上级证据，不允许用历史替换
     * @param evidence 模型引用的非空连续片段，必须原样存在
     * @param path 服务端定义的协议字段路径，帮助模型在原预算内定位错误
     * @throws ModelContractViolation 引用不存在；反馈只能修正引用，不能据此改变动作、字段或比较关系
     */
    static void require(String source,String evidence,String path) {
        if(evidence!=null && !evidence.isBlank() && source!=null && source.contains(evidence))return;
        throw failure(path+"="+JsonUtil.toJson(evidence)+"不是本轮连续原文");
    }
    /** 同一来源的多个证据一次定位，避免修好第一处后才发现其他引用错误而耗尽有界复核次数。 */
    static void requireAll(String source,java.util.Map<String,String> evidenceByPath) {
        var failures=new java.util.ArrayList<String>();
        evidenceByPath.forEach((path,evidence)->{
            if(evidence==null || evidence.isBlank() || source==null || !source.contains(evidence))failures.add(path+"="+JsonUtil.toJson(evidence)+"不是本轮连续原文");
        });
        if(!failures.isEmpty())throw failure(String.join("；",failures));
    }
    private static ModelContractViolation failure(String fields) {
        return new ModelContractViolation("本轮原文引用尚未一致",fields
                +"。并列句省略的主语、字段名或单位不能补入evidence；引用实际存在的连续片段，必要时引用完整原句，业务解释写在meaning。只修正引用，不改动已正确的动作、对象、条件或比较关系；历史只能解释含义，不能替代本轮依据");
    }
}
