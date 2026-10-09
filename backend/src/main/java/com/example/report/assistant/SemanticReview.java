package com.example.report.assistant;

import com.example.report.common.ApiException;
import java.util.List;

/**
 * 独立复核的语义期望；先列原文要求，再表达对应的完整计划，由程序计算差异，模型不自报批准或执行修正。
 * @param requirements 带来源位置的动作、范围、条件等要求；历史仅用于解释省略，不能冒充本轮授权
 * @param expectedPlan 不读取草稿而独立形成的完整期望；仅作比较，必须由规划器产生并通过相同预检才可执行
 * @param targetCount 本轮明确要求的最终派单目标总数；null表示未限定总数，不把金额、序号或局部操作数量误作总数
 */
public record SemanticReview(List<Requirement> requirements,AssistantPlan expectedPlan,TargetCount targetCount) {
    public SemanticReview {
        if(requirements==null || requirements.isEmpty() || requirements.size()>16 || expectedPlan==null)
            throw new ApiException(422,"语义复核必须列出原文要求及完整任务期望");
        requirements=List.copyOf(requirements);
    }
    /** 程序构造未限定数量的期望；模型协议仍须显式提供targetCount。 */
    public SemanticReview(List<Requirement> requirements,AssistantPlan expectedPlan){this(requirements,expectedPlan,null);}
    /**
     * 用户明确指定的最终候选选择或待确认条目数量，由只读预检事实校验，不能通过静默减少条目满足。
     * @param count 最终目标条数，非负整数；不是条目序号或某次排除/恢复的匹配数量
     * @param evidence 本轮连续原文，必须包含总数要求；历史计数和查询结果不能充当用户授权
     */
    public record TargetCount(int count,String evidence) {
        public TargetCount {
            if(count<0 || count>100000 || evidence==null || evidence.isBlank() || evidence.length()>1000)
                throw new ApiException(422,"目标条数须为有本轮依据的非负整数");
        }
    }
    /** 可计算差异所属的语义维度；不通过自由文本指令隐式变更其他维度。 */
    public enum Aspect { ACTION, SCOPE, CONDITIONS, REFERENCE, PRESENTATION, CONTINUITY, CAPABILITY }
    /**
     * 一项有原文依据的语义要求；描述用于解释，真正的期望以expectedPlan的结构为准。
     * @param aspect 要求所属维度，同一维度可有多项正向或否定要求
     * @param messageIndex 证据所在消息：-1为本轮message，0起为recentConversation下标；历史只能解释未改变的语义
     * @param evidence 对应消息中的连续原文；不得引用查询结果或模型自造句子冒充对话
     * @param meaning 结合目录及真实上下文解释的业务含义，不是向规划器下达的修改命令
     */
    public record Requirement(Aspect aspect,int messageIndex,String evidence,String meaning) {
        public Requirement {
            if(aspect==null || messageIndex< -1 || messageIndex>15 || evidence==null || evidence.isBlank() || evidence.length()>1000
                    || meaning==null || meaning.isBlank() || meaning.length()>1000)
                throw new ApiException(422,"语义要求缺少维度、原文依据或业务含义");
        }
        /** 程序测试或内部构造本轮依据；模型JSON仍须显式声明消息位置。 */
        public Requirement(Aspect aspect,String evidence,String meaning){this(aspect,-1,evidence,meaning);}
    }
}
