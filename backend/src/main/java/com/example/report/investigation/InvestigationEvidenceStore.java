package com.example.report.investigation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/** 保存本轮模型实际获得的有界事实；引用存在只证明来源，报告事实和自然语言解释须另行验证。 */
@Service
@ConditionalOnProperty(name="security.enabled", havingValue="true")
public class InvestigationEvidenceStore {
    /**
     * 本轮引用校验对象。
     * @param type PLAN_SNAPSHOT、ITEM_SNAPSHOT、RULE_SNAPSHOT、EXECUTION_EVENT或MCP_LOOKUP
     * @param refs 适用条目引用数组，整体清单证据为空
     * @param data 模型实际收到的脱敏事实
     * @param truncated 是否为不完整来源，不能据此断言完整历史
     */
    public record Evidence(String type,List<String> refs,Map<String,Object> data,boolean truncated) { }
    private final InvestigationRepository repository;
    public InvestigationEvidenceStore(InvestigationRepository repository) {this.repository=repository;}
    /** 数据落库成功后才进入可引用集合；失租或取消时不允许记录迟到证据。 */
    public String add(InvestigationSession session,String type,List<String> refs,Map<String,Object> content,boolean truncated) {
        session.check(false);
        String ref=repository.evidence(session.id,session.token,type,refs,Map.of("executionVersion",session.snapshot.get("executionVersion")),content,truncated);
        session.evidence.put(ref,new Evidence(type,List.copyOf(refs),content,truncated));return ref;
    }
}
