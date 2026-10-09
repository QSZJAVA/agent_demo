package com.example.report.assistant;

import com.example.report.catalog.CatalogEntry;
import com.example.report.common.JsonUtil;
import java.util.*;

/**
 * 从已授权的只读预检中提取本轮原文实际命中的字段值，补充业务词义依据。
 * 不暴露草稿动作、查询条件、行身份、匹配数量或结果全集，不据此选择路由、生成条件或授权派单。
 */
public final class BusinessTermEvidence {
    private static final Set<String> RECORD_IDENTITIES=Set.of("rowKey","recordId","docNo","reportId");
    private BusinessTermEvidence() { }

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
