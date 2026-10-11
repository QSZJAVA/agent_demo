package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.LongPredicate;
import java.util.regex.Pattern;

/**
 * 核对独立条件证据之间明确的 AND/OR 及括号关系；字段、比较符和值仍由模型理解并由既有契约校验。
 * 只拒绝可证明的组合冲突，不生成或修改计划；重叠引用、混合优先级、整体否定及超预算表达仍交独立模型复核。
 */
final class LogicalConditionEvidence {
    private LogicalConditionEvidence() { }
    private static final int MAX_ATOMS=12;
    private static final Pattern CONNECTOR=Pattern.compile("^([\\s,，、)]*)(或者|或是|或|or|\\|\\||∨|并且|而且|以及|同时|且|和|并|and|&&|∧)([\\s,，、(]*)$",Pattern.CASE_INSENSITIVE);
    private static final Pattern PREFIX_BRACKETS=Pattern.compile("[\\s,，、(]*$");
    private static final Pattern SUFFIX_BRACKETS=Pattern.compile("^[\\s,，、)]*");
    // 这些词可能对整组取反、限定异或或形成条件句；原子间连接不足以确定整体语义时不擅自推断。
    private static final Pattern AMBIGUOUS_ENVELOPE=Pattern.compile("不|没|无|未|非|排除|剔除|除外|以外|禁止|要么|二选一|恰好|只有|如果|除非|否则|\\b(?:not|neither|nor|except|exclude|without|either|if|unless)\\b",Pattern.CASE_INSENSITIVE);

    /**
     * 在冻结独立期望前逐个条件作用域只读检查，不跨查询与不同候选操作拼接证据。
     * @param message 当前用户完整原文；历史条件和不可唯一定位的引用不用于确定性逻辑判断
     * @param review 已完成条件身份及原文来源校验的独立复核
     * @throws ApiException 明确逻辑与期望分组冲突，返回422让复核在原预算内自行修正
     */
    static void validate(String message,SemanticReview review) {
        if(message==null)return;
        var checks=new HashMap<BusinessQuery.Filter,SemanticReview.ConditionCheck>();
        review.conditionChecks().forEach(check->checks.put(TaskConditionEvidence.filter(JsonUtil.MAPPER.valueToTree(check.condition())),check));
        var root=JsonUtil.MAPPER.valueToTree(review.expectedPlan());
        validateScope(message,root.path("query").path("conditions"),checks,"/query/conditions");
        var changes=root.path("dispatch").path("intent").path("scopeChanges");
        for(int index=0;index<changes.size();index++)
            validateScope(message,changes.get(index).path("conditions"),checks,"/dispatch/intent/scopeChanges/"+index+"/conditions");
    }

    /**
     * 仅当每个不同条件都有唯一、不重叠的本轮正向证据且中间只含明确连接时核对。
     * 每个原子视为独立真假值，穷举至多4096种赋值；不依赖当前数据恰好相同，也不靠字段取值推导蕴含关系。
     */
    private static void validateScope(String message,JsonNode groups,Map<BusinessQuery.Filter,SemanticReview.ConditionCheck> checks,String path) {
        var conditions=new LinkedHashSet<BusinessQuery.Filter>();
        for(var group:groups)for(var condition:group.path("allOf"))conditions.add(TaskConditionEvidence.filter(condition));
        if(conditions.size()<2 || conditions.size()>MAX_ATOMS)return;
        var spans=new ArrayList<Span>();
        for(var condition:conditions) {
            var check=checks.get(condition);
            if(check==null || check.origin()!=SemanticReview.ConditionOrigin.CURRENT_REQUEST || !check.negationEvidence().isEmpty())return;
            String evidence=check.evidence();int start=message.indexOf(evidence);
            if(start<0 || message.indexOf(evidence,start+1)>=0)return;
            spans.add(new Span(condition,start,start+evidence.length()));
        }
        spans.sort(Comparator.comparingInt(Span::start));
        for(int index=1;index<spans.size();index++)if(spans.get(index-1).end()>spans.get(index).start())return;
        String prefix=message.substring(0,spans.get(0).start()),suffix=message.substring(spans.get(spans.size()-1).end());
        if(AMBIGUOUS_ENVELOPE.matcher(prefix+" "+suffix).find())return;
        String expression=expression(message,spans,prefix,suffix);
        if(expression==null)return;
        var expected=new ExpressionReader(expression,spans.size()).read();if(expected==null)return;
        var bits=new HashMap<BusinessQuery.Filter,Long>();
        for(int index=0;index<spans.size();index++)bits.put(spans.get(index).condition(),1L<<index);
        var terms=new ArrayList<Long>();
        for(var group:groups) {
            long term=0;for(var condition:group.path("allOf"))term|=bits.get(TaskConditionEvidence.filter(condition));
            terms.add(term);
        }
        for(long assignment=0;assignment<(1L<<spans.size());assignment++) {
            boolean actual=false;for(long term:terms)if((assignment&term)==term){actual=true;break;}
            if(actual!=expected.test(assignment))throw new ApiException(422,"明确逻辑连接与 "+path+" 的AND/OR分组不一致；本轮条件原文="
                    +JsonUtil.toJson(message.substring(spans.get(0).start(),spans.get(spans.size()-1).end()))
                    +"。逐项条件正确不能替代组合正确：或者为组间OR，并且为组内AND，括号限定作用域。只修正独立期望的组合并同步核对完整依据，不删除条件、不改变动作，也不能要求规划器复制错误复核");
        }
    }

    /** 原子内部作为不透明证据，不扫描其中的“或”、括号或业务值；只读取相邻证据之间的连接符。 */
    private static String expression(String message,List<Span> spans,String prefix,String suffix) {
        var opening=PREFIX_BRACKETS.matcher(brackets(prefix));opening.find();
        var closing=SUFFIX_BRACKETS.matcher(brackets(suffix));closing.find();
        var result=new StringBuilder(onlyBrackets(opening.group())).append('A');
        for(int index=1;index<spans.size();index++) {
            var gap=CONNECTOR.matcher(brackets(message.substring(spans.get(index-1).end(),spans.get(index).start())));
            if(!gap.matches())return null;
            String word=gap.group(2).toLowerCase(Locale.ROOT);
            char operator=Set.of("或者","或是","或","or","||","∨").contains(word)?'|':'&';
            result.append(onlyBrackets(gap.group(1))).append(operator).append(onlyBrackets(gap.group(3))).append((char)('A'+index));
        }
        result.append(onlyBrackets(closing.group()));
        return result.length()>128?null:result.toString();
    }
    private static String brackets(String value){return value.replace('（','(').replace('）',')');}
    private static String onlyBrackets(String value){return value.replaceAll("[^()]","");}

    /**
     * 本轮某一条件的准确连续位置，不对字符做归一化后再推测位置。
     * @param condition 已归一且由来源校验确认的完整条件身份
     * @param start 原文中包含的UTF-16起始位置
     * @param end 原文中不包含的UTF-16结束位置
     */
    private record Span(BusinessQuery.Filter condition,int start,int end) { }

    /** 有界正向布尔表达式读取器；同一括号层只接受同类连接，不自行决定自然语言混合优先级。 */
    private static final class ExpressionReader {
        private final String text;
        private final int atoms;
        private int position;
        private ExpressionReader(String text,int atoms){this.text=text;this.atoms=atoms;}
        /** 仅完整匹配才给出只读真假判定；无法确定或括号不配对时返回null，让模型保留语义判断职责。 */
        private LongPredicate read(){var value=level(0);return position==text.length()?value:null;}
        private LongPredicate level(int depth) {
            if(depth>16)return null;
            var value=term(depth);char seen=0;
            if(value==null)return null;
            while(position<text.length() && text.charAt(position)!=')') {
                char operator=text.charAt(position++);
                if((operator!='&' && operator!='|') || (seen!=0 && seen!=operator))return null;
                seen=operator;var next=term(depth);if(next==null)return null;
                var left=value;value=operator=='&'?mask->left.test(mask)&&next.test(mask):mask->left.test(mask)||next.test(mask);
            }
            return value;
        }
        private LongPredicate term(int depth) {
            if(position>=text.length())return null;
            char token=text.charAt(position++);
            if(token=='(') {
                var value=level(depth+1);
                if(value==null || position>=text.length() || text.charAt(position++)!=')')return null;
                return value;
            }
            int atom=token-'A';if(atom<0 || atom>=atoms)return null;
            long bit=1L<<atom;return mask->(mask&bit)!=0;
        }
    }
}
