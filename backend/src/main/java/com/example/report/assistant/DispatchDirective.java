package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.semantic.SemanticIntent;
import java.util.List;

/**
 * 统一任务计划中的派单动作及对象来源；模型只能准备清单，不能确认或执行派单。
 * @param intent 完整受控派单操作，与目标一起解释，不再由第二个解析器重新猜测
 * @param source EXPLICIT_SCOPE本轮完整范围、PREVIEW已有候选、PLAN已有清单、QUERY_ROWS已展示查询对象、QUERY_ALL完整查询结果
 * @param sourceRef 服务端提供的对象集引用；EXPLICIT_SCOPE为空，其余必须与本轮上下文绑定一致
 * @param referenceKeys QUERY_ROWS选择的展示对象键；其他来源为空，不能由模型提供任意数据库记录标识
 * @param evidence 本轮支持动作及目标引用的连续原文；对象身份可以继承，操作要求不能冒用历史消息
 */
public record DispatchDirective(SemanticIntent intent,Source source,String sourceRef,List<String> referenceKeys,String evidence) {
    public enum Source { EXPLICIT_SCOPE, PREVIEW, PLAN, QUERY_ROWS, QUERY_ALL }
    public DispatchDirective {
        if(intent==null || source==null || referenceKeys==null || referenceKeys.size()>50 || evidence==null || evidence.isBlank() || evidence.length()>1000)
            throw new ApiException(422,"派单任务缺少完整动作、目标来源或本轮依据");
        referenceKeys=List.copyOf(referenceKeys);
        if(referenceKeys.stream().anyMatch(k->k==null || !k.matches("row-[1-9][0-9]{0,3}")) || referenceKeys.stream().distinct().count()!=referenceKeys.size())
            throw new ApiException(422,"查询对象引用键无效或重复");
        if((source==Source.EXPLICIT_SCOPE)!=(sourceRef==null) || (sourceRef!=null && sourceRef.length()>128))
            throw new ApiException(422,"目标来源必须携带对应上下文引用，明确新范围不得复用旧引用");
        if((source==Source.QUERY_ROWS)!=!referenceKeys.isEmpty())throw new ApiException(422,"仅QUERY_ROWS填写已展示对象键，且至少指定一个对象");
        boolean planAction=intent.action()==SemanticIntent.Action.CANCEL_PLAN || intent.action()==SemanticIntent.Action.SHOW_RESULT;
        if(planAction!=(source==Source.PLAN) || intent.action()==SemanticIntent.Action.HELP || intent.action()==SemanticIntent.Action.CLARIFY)
            throw new ApiException(422,"派单动作与对象来源不一致；帮助或澄清使用独立路由");
        if((source==Source.QUERY_ROWS || source==Source.QUERY_ALL)
                && (intent.action()!=SemanticIntent.Action.PREVIEW && intent.action()!=SemanticIntent.Action.PREPARE_DISPATCH))
            throw new ApiException(422,"查询对象可用于核验后预览或准备清单；资格询问使用只读查询");
        if((source==Source.QUERY_ROWS || source==Source.QUERY_ALL) && (!intent.scopeChanges().isEmpty() || !intent.reportConstraints().isEmpty()))
            throw new ApiException(422,"查询对象来源已明确限定目标，不得同时修改派单范围或旧候选选择；先通过查询确定完整目标");
        if(source==Source.PLAN && (!intent.scopeChanges().isEmpty() || !intent.reportConstraints().isEmpty()))
            throw new ApiException(422,"清单操作只作用于绑定清单，不同时改变查询或记录范围");
    }
}
