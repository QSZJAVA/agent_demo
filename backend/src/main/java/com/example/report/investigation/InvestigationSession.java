package com.example.report.investigation;

import com.example.report.permission.CurrentUser;
import java.util.*;

/** 当前认领轮次的可信运行上下文；身份、范围和令牌由程序持有，模型只见不透明条目引用。 */
public class InvestigationSession {
    public final String id, token;
    public final CurrentUser actor;
    public final Map<String,Object> snapshot;
    public final InvestigationBudget budget;
    public final Map<String,InvestigationEvidenceStore.Evidence> evidence=new LinkedHashMap<>();
    public final Map<String,Integer> lookupAttempts=new HashMap<>();
    public final Runnable accessGuard, sourceGuard;
    public final List<Map<String,Object>> items;
    public String partialReason;
    @SuppressWarnings("unchecked")
    public InvestigationSession(String id,String token,CurrentUser actor,Map<String,Object> snapshot,InvestigationBudget budget,Runnable accessGuard,Runnable sourceGuard) {
        this.id=id;this.token=token;this.actor=actor;this.snapshot=snapshot;this.budget=budget;this.accessGuard=accessGuard;this.sourceGuard=sourceGuard;
        items=(List<Map<String,Object>>)snapshot.get("items");
    }
    /** 每次业务查询都验证当前授权和来源；报告阶段可描述已变化来源，但不能跳过授权。 */
    public void check(boolean source) {accessGuard.run();if(source) sourceGuard.run();}
    public Map<String,Object> item(String ref) {
        return items.stream().filter(i -> ref.equals(i.get("itemRef"))).findFirst().orElseThrow(() -> new com.example.report.common.ApiException("条目引用超出本次调查范围"));
    }
}
