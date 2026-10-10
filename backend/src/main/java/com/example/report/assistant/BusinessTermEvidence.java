package com.example.report.assistant;

import com.example.report.catalog.CatalogEntry;
import com.example.report.common.JsonUtil;
import java.util.*;

/**
 * 从授权目录及只读预检提取本轮原文实际命中的报表名和字段值，补充可核对的业务词义依据。
 * 不暴露草稿动作、查询条件、行身份、匹配数量或结果全集，不据此选择路由、生成条件或授权派单。
 */
public final class BusinessTermEvidence {
    private static final Set<String> RECORD_IDENTITIES=Set.of("rowKey","recordId","docNo","reportId");
    private BusinessTermEvidence() { }

    /**
     * 提供本轮逐字出现的授权报表名及别名；不根据记录类型或历史报表补造当前原词。
     * @param message 本轮原文；命中结果保持原文大小写，不扩写为目录标准名称
     * @param reports 已经过权限过滤的当前目录，不读取其他租户或报表
     * @return 按报表分组的字面命中；空集合只说明没有目录字面命中，不自行推断用户动作或删除未知限定
     */
    public static List<ReportMention> reportMentions(String message,List<CatalogEntry> reports) {
        var matches=new ArrayList<ReportMention>();
        for(var report:reports) {
            var names=new LinkedHashSet<String>();names.add(report.reportName());names.add(report.reportId());names.addAll(report.ref().aliases());
            var literals=new LinkedHashSet<String>();
            for(String name:names) {
                if(name==null || name.isBlank())continue;
                var matcher=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(name),java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.UNICODE_CASE).matcher(message);
                boolean ascii=name.chars().allMatch(c->c<128);
                while(matcher.find()) {
                    // 英文名和代码必须是完整词；中文命中只提供字面事实，语义角色仍由独立复核判断。
                    if(ascii && ((matcher.start()>0 && asciiWord(message.charAt(matcher.start()-1)))
                            || (matcher.end()<message.length() && asciiWord(message.charAt(matcher.end())))))continue;
                    literals.add(matcher.group());
                }
            }
            if(!literals.isEmpty())matches.add(new ReportMention(report.reportId(),List.copyOf(literals)));
        }
        return List.copyOf(matches);
    }

    /**
     * 授权报表与本轮原词的对应事实，不表示该报表应纳入、排除或承接。
     * @param reportId 当前用户可见的报表标识
     * @param mentions 本轮实际出现的名称、代码或已启用别名，不能用历史或业务类型补写
     */
    public record ReportMention(String reportId,List<String> mentions) { }

    /**
     * 只提取当前可见目录声明的字符串字段和原文连续命中；未命中或被截断不证明该业务值不存在。
     * @param message 已恢复脱敏占位的本轮原文，不从历史补充操作要求
     * @param facts 本轮通过权限及完整性检查的只读预检事实，不触发额外数据库读取
     * @param reports 本轮授权目录，用于验证字段归属，禁止把响应的任意属性当作业务字段
     * @return 有界词义事实，不能作为目标引用或结果完整性证明
     */
    public static Evidence extract(String message,Map<String,Object> facts,List<CatalogEntry> reports) {
        var fields=new HashMap<String,Set<String>>();
        for(var report:reports)fields.put(report.reportId(),report.fields().stream().filter(f->"string".equals(f.type()) && !RECORD_IDENTITIES.contains(f.name()))
                .map(com.example.report.catalog.query.FieldInfo::name).collect(java.util.stream.Collectors.toSet()));
        var matches=new LinkedHashSet<Term>();boolean truncated=false;
        for(var row:JsonUtil.MAPPER.valueToTree(facts).path("rows")) {
            String reportId=row.path("reportId").asText();
            for(String field:new TreeSet<>(fields.getOrDefault(reportId,Set.of()))) {
                var node=row.path(field);if(!node.isTextual())continue;
                String value=node.asText();
                if(value.length()<2 || value.length()>512 || !mentioned(message,value))continue;
                var term=new Term(reportId,field,value);
                if(matches.contains(term))continue;
                if(matches.size()==64){truncated=true;continue;}
                matches.add(term);
            }
        }
        return new Evidence(List.copyOf(matches),truncated);
    }

    /** 英文或数字值须保留词边界，不能把较长编号内部的一段误作独立命中；中文原词由语义复核解释角色。 */
    private static boolean mentioned(String message,String value) {
        boolean ascii=value.chars().allMatch(c->c<128);
        for(int index=message.indexOf(value);index>=0;index=message.indexOf(value,index+1)) {
            int end=index+value.length();
            if(!ascii || ((index==0 || !asciiWord(message.charAt(index-1))) && (end==message.length() || !asciiWord(message.charAt(end)))))return true;
        }
        return false;
    }
    private static boolean asciiWord(char c){return c<128 && (Character.isLetterOrDigit(c) || c=='_');}

    /**
     * 只说明已观察到的词义线索；不表示枚举了来源数据或本轮所有语义。
     * @param matches 去重后的报表、字段和原文值，最多64项
     * @param truncated true表示线索超过上限，不能按现有线索排除其他解释
     */
    public record Evidence(List<Term> matches,boolean truncated) { }
    /**
     * 一项原文与授权字段值的连续命中，不携带可执行记录身份。
     * @param reportId 该字段所属的授权报表稳定标识
     * @param field 当前目录声明的字符串字段名
     * @param value 来源实际值且也是本轮连续原文；角色、否定及是否用于筛选仍由模型复核
     */
    public record Term(String reportId,String field,String value) { }
}
