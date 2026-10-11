package com.example.report.assistant;

import com.example.report.common.*;
import java.util.*;

/** 模型提出的澄清候选只引用可验证的业务标识；展示名称由服务端目录生成，不接受模型伪造标签。
 * @param kind COMPANY、REPORT、FIELD或VALUE，决定候选的身份类型
 * @param domain 字段/枚举候选所属数据域，其他类型为空
 * @param companyCode 字段/枚举候选所属授权公司，其他类型为空
 * @param reportId 字段/枚举候选所属报表，其他类型为空
 * @param field 枚举候选所属字段，其他类型为空
 * @param values 两至八个不同的候选稳定值，不能为空或用相似名称合并身份
 */
public record ClarificationOptions(Kind kind,BusinessQuery.Domain domain,String companyCode,String reportId,String field,List<String> values) {
    public enum Kind { COMPANY, REPORT, FIELD, VALUE }
    public ClarificationOptions {
        if(kind==null || values==null || values.size()<2 || values.size()>8 || values.stream().anyMatch(v->v==null || v.isBlank() || v.length()>128)
                || values.stream().distinct().count()!=values.size())throw new ApiException(422,"澄清候选须为2至8个不同的已知业务值");
        boolean scoped=kind==Kind.FIELD || kind==Kind.VALUE;
        if(scoped!=(reportId!=null) || scoped!=(domain!=null) || scoped!=(companyCode!=null) || (kind==Kind.VALUE)!=(field!=null))throw new ApiException(422,"澄清候选的公司、数据域、报表或字段范围不完整");
        if(scoped)new SemanticDictionary.Scope(domain,companyCode,reportId);
        values=List.copyOf(values);
    }
    /** 在草稿和独立复核中执行相同校验；只有当前目录、公司授权或词典真实枚举值可成为按钮。 */
    public void validate(Set<String> reports,Set<String> companies,AssistantPlanningContext context){
        Set<String> known;
        if(kind==Kind.COMPANY)known=companies;
        else if(kind==Kind.REPORT)known=reports;
        else {
            if(!reports.contains(reportId) || !companies.contains(companyCode))throw ApiException.forbidden("澄清公司或报表不存在或无权访问");
            var entry=context.catalog().stream().filter(r->r.reportId().equals(reportId)).findFirst().orElseThrow(()->new ApiException(422,"澄清字段缺少授权目录依据"));
            var fields=BusinessFields.forDomain(domain,entry.fields());
            if(kind==Kind.FIELD)known=fields.stream().map(f->f.name()).collect(java.util.stream.Collectors.toSet());
            else {
                if(fields.stream().noneMatch(f->f.name().equals(field)))throw new ApiException(422,"澄清枚举字段未配置");
                known=new HashSet<>();var definitions=JsonUtil.MAPPER.valueToTree(context.dictionary()).path("entries");
                // 同名字段在不同公司或数据域可有不同枚举，不能合并授权范围内其他词典来凑齐候选。
                for(var definition:definitions)if(reportId.equals(definition.path("scope").path("reportId").asText()) && domain.name().equals(definition.path("scope").path("domain").asText()) && companyCode.equals(definition.path("scope").path("companyCode").asText()))
                    for(var f:definition.path("definition").path("fields"))if(field.equals(f.path("field").asText()))for(var value:f.path("values"))known.add(value.path("value").asText());
            }
        }
        if(!known.containsAll(values))throw new ApiException(422,"澄清候选包含当前授权目录或业务词典中不存在的值");
    }
    /** 生成明确的选择补充语句；它只表达用户选中的值，实际动作仍由完整规划与复核确定。 */
    public String answer(String value){if(!values.contains(value))throw new ApiException(422,"所选值不属于本次澄清候选");return switch(kind){
        case COMPANY->"本轮明确公司代码为 "+value+"。";case REPORT->"本轮明确报表标识为 "+value+"。";
        case FIELD->"本轮明确数据域 "+domain+"、公司 "+companyCode+"、报表 "+reportId+" 的字段为 "+value+"。";case VALUE->"本轮明确数据域 "+domain+"、公司 "+companyCode+"、报表 "+reportId+" 的字段 "+field+" 的准确值为 "+JsonUtil.toJson(value)+"。";};}
}
