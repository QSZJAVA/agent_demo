package com.example.report.assistant;

import com.example.report.common.ApiException;
import java.util.List;

/**
 * 独立复核的语义期望；先列原文要求，再表达对应的完整计划，由程序计算差异，模型不自报批准或执行修正。
 * @param requirements 本轮原文支持的动作、范围、条件等要求；历史仅在meaning中解释省略，不能冒充本轮授权证据
 * @param conditionChecks 每个实际字段条件的来源及连续原文；继承条件必须仍存在于当前有效查询，不能从已撤销的历史恢复
 * @param priorConditionChanges 同范围追问中不再原样保留的旧条件及本轮依据；端点方向从旧比较符确定，不要求模型重复分类
 * @param expectedPlan 不读取草稿而独立形成的完整期望；仅作比较，必须由规划器产生并通过相同预检才可执行
 * @param targetCount 本轮明确要求的最终派单目标总数；null表示未限定总数，不把金额、序号或局部操作数量误作总数
 */
public record SemanticReview(List<Requirement> requirements,List<ConditionCheck> conditionChecks,List<PriorConditionChange> priorConditionChanges,AssistantPlan expectedPlan,TargetCount targetCount) {
    public SemanticReview {
        if(requirements==null || requirements.isEmpty() || requirements.size()>16 || conditionChecks==null || conditionChecks.size()>128
                || priorConditionChanges==null || priorConditionChanges.size()>128 || expectedPlan==null)
            throw new ApiException(422,"语义复核必须列出原文要求及完整任务期望");
        requirements=List.copyOf(requirements);conditionChecks=List.copyOf(conditionChecks);priorConditionChanges=List.copyOf(priorConditionChanges);
    }
    /** 内部构造未改变旧条件的期望；模型JSON仍须显式声明priorConditionChanges，不接受旧结构缺字段。 */
    public SemanticReview(List<Requirement> requirements,List<ConditionCheck> checks,AssistantPlan expectedPlan,TargetCount targetCount){this(requirements,checks,List.of(),expectedPlan,targetCount);}
    /** 内部构造无字段条件的期望；模型协议仍必须显式提供conditionChecks和targetCount。 */
    public SemanticReview(List<Requirement> requirements,AssistantPlan expectedPlan){this(requirements,List.of(),expectedPlan,null);}
    /** 内部构造无字段条件但限定数量的期望；不为模型缺失字段提供协议兜底。 */
    public SemanticReview(List<Requirement> requirements,AssistantPlan expectedPlan,TargetCount targetCount){this(requirements,List.of(),expectedPlan,targetCount);}
    /** 条件来源只能是本轮明确要求、当前有效查询或已展示对象；原始历史不是可继承条件集合。 */
    public enum ConditionOrigin { CURRENT_REQUEST, ACTIVE_QUERY, VISIBLE_OBJECT }
    /**
     * 当前成功查询中被替换或删除的一个旧条件；新条件仍由expectedPlan和conditionChecks完整声明，本对象不执行修改。
     * @param condition previousQuery中真实存在且未原样保留的完整旧条件；不是替换后的新条件
     * @param evidence 本轮授权改变该旧条件的连续原文；只修改另一端点、金额或其他字段的原话不能作为依据
     */
    public record PriorConditionChange(BusinessQuery.Filter condition,String evidence) {
        public PriorConditionChange {
            if(condition==null || condition.field()==null || condition.operator()==null || condition.values()==null
                    || evidence==null || evidence.isBlank() || evidence.length()>1000)
                throw new ApiException(422,"旧条件变化须提供完整旧条件和本轮连续原文");
        }
    }
    /**
     * 单个查询或候选字段条件的可核对依据，不携带可执行补丁。
     * @param condition 对应字段、比较符和值；按条件内容绑定，不依赖模型排列数组的顺序，同一条件重复出现时只说明一次
     * @param origin 当前要求、仍有效查询或已展示对象；历史只能解释本轮指代，不能作为第四种条件来源
     * @param evidence 本轮连续原文；当前条件应包含相应比较和值，继承/对象引用应引用本轮承接或指代语句
     * @param negationEvidence 仅普通查询对整个比较取反时引用本轮否定原文；无取反或候选选择时必须为空串
     */
    public record ConditionCheck(BusinessQuery.Filter condition,ConditionOrigin origin,String evidence,String negationEvidence) {
        public ConditionCheck {
            if(condition==null || condition.field()==null || condition.operator()==null || condition.values()==null
                    || origin==null || evidence==null || evidence.isBlank() || evidence.length()>1000
                    || negationEvidence==null || negationEvidence.length()>1000)
                throw new ApiException(422,"字段条件复核须提供完整条件、来源和本轮连续原文");
        }
    }
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
     * @param messageIndex 当前要求的证据位置，固定为-1表示本轮message；不能将历史指令作为本轮操作依据
     * @param evidence 本轮连续原文；代词或承接短语也来自本轮，不用被引用的历史原句替换
     * @param meaning 结合目录及真实上下文解释省略、指代和业务含义，不是向规划器下达的修改命令
     */
    public record Requirement(Aspect aspect,int messageIndex,String evidence,String meaning) {
        public Requirement {
            if(messageIndex!=-1)throw new ApiException(422,"requirements描述本轮要求，messageIndex必须为-1，evidence引用本轮触发修改或承接的原话；历史只在meaning中解释指代，不能用历史证据代替本轮恢复、排除或查询授权");
            if(aspect==null || evidence==null || evidence.isBlank() || evidence.length()>1000
                    || meaning==null || meaning.isBlank() || meaning.length()>1000)
                throw new ApiException(422,"语义要求缺少维度、原文依据或业务含义");
        }
        /** 程序测试或内部构造本轮依据；模型JSON仍须显式声明消息位置。 */
        public Requirement(Aspect aspect,String evidence,String meaning){this(aspect,-1,evidence,meaning);}
    }
}
