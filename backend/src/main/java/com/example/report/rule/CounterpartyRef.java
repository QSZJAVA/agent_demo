package com.example.report.rule;

import com.example.report.common.ApiException;
import com.example.report.common.JsonUtil;
import java.util.*;

/**
 * 来源系统声明的交易对方实体快照；标识在所属租户和公司内稳定，名称与别名不参与生成标识。
 * @param id 来源客户标识，长度不超过128字符；不由模型生成
 * @param name 来源客户完整名称，长度不超过160字符
 * @param aliases 来源维护的别名数组，最多32项；空集合表示没有别名，禁止从相似名称自动学习
 */
public record CounterpartyRef(String id,String name,List<String> aliases) {
    public CounterpartyRef {
        if(id==null || id.isBlank() || id.length()>128 || name==null || name.isBlank() || name.length()>160
                || aliases==null || aliases.size()>32 || aliases.stream().anyMatch(a -> a==null || a.isBlank() || a.length()>160))
            throw new ApiException(422,"客户实体数据不完整，请维护来源客户标识、名称和别名后重新预览");
        aliases=List.copyOf(aliases);
    }
    /** 读取当前事实契约，未声明客户字段的报表返回null；声明不完整或别名JSON损坏必须拒绝，不能降级为摘要匹配。 */
    public static CounterpartyRef fromFacts(Map<String,Object> facts) {
        if(!facts.containsKey("counterpartyId") && !facts.containsKey("counterpartyName")) return null;
        Object id=facts.get("counterpartyId"),name=facts.get("counterpartyName"),aliases=facts.get("counterpartyAliases");
        if(id==null && name==null && aliases==null) return null;
        try {
            List<String> values=aliases==null?List.of():JsonUtil.MAPPER.readValue(aliases.toString(),
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});
            return new CounterpartyRef(Objects.toString(id,null),Objects.toString(name,null),values);
        } catch(Exception error) { throw new ApiException(422,"客户实体配置或别名数据无效，请修正来源数据后重新预览"); }
    }
    /** 当前快照反序列化；null表示本记录未提供客户实体，损坏证据不能恢复为另一种选择语义。 */
    public static CounterpartyRef fromSnapshot(String json) {
        if(json==null) return null;
        try { return JsonUtil.MAPPER.readValue(json,CounterpartyRef.class); }
        catch(Exception error) { throw new ApiException(422,"预览客户快照无法读取，请重新查询"); }
    }
}
