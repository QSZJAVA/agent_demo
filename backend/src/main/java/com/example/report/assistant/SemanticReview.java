package com.example.report.assistant;

import com.example.report.common.ApiException;
import java.util.List;

/**
 * 独立语义复核的结构化结果；只评价计划是否对应本轮要求，不执行操作或改写业务事实。
 * @param approved 是否通过；通过时issues必须为空，未通过必须列出具体偏差
 * @param issues 本轮原文依据及偏差说明，最多八项
 */
public record SemanticReview(Boolean approved,List<Issue> issues) {
    public SemanticReview {
        if(approved==null || issues==null || issues.size()>8 || approved!=issues.isEmpty())throw new ApiException(422,"语义复核结果不完整");
        issues=List.copyOf(issues);
    }
    /**
     * 一项需要重新规划的问题。
     * @param evidence 对应本轮连续原文，不使用历史或来源文本冒充用户依据
     * @param reason 动作、对象、条件、数量、继承或能力上的具体偏差
     */
    public record Issue(String evidence,String reason) {
        public Issue {
            if(evidence==null || evidence.isBlank() || evidence.length()>1000 || reason==null || reason.isBlank() || reason.length()>1000)
                throw new ApiException(422,"语义复核缺少本轮依据或具体问题");
        }
    }
}
