package com.example.report.investigation;

import com.example.report.common.JsonUtil;
import com.example.report.permission.CurrentUser;
import com.example.report.dispatch.DispatchGateway.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 调查协议和评估的当前格式合成事实；仅真实模型评估调用真实端点，夹具不访问业务库。 */
final class InvestigationTestSupport {
    static final CurrentUser ACTOR=new CurrentUser("T001","reader","读者",Set.of("A"),Set.of("report:sales"),false);
    static Map<String,Object> item(String ref,String status,String error) {
        var item=new LinkedHashMap<String,Object>();item.put("itemRef",ref);item.put("itemId",ref.substring(1));item.put("docNo","DUPLICATE-DOC");
        item.put("label","测试条目");item.put("companyCode","A");item.put("status",status);item.put("errorCode",error);item.put("errorMessage",error==null?"":switch(status) {case "SKIPPED" -> "执行前复核跳过："+error;case "UNKNOWN","PENDING" -> "未获得明确业务结果："+error;default -> "明确业务失败："+error;});
        item.put("attemptCount",1);item.put("requestId","req-"+ref);item.put("rule",Map.of("id",1,"version",1,"expression","amount > 100","name","金额规则"));
        item.put("events",List.of(Map.of("eventId","1","outcome",status,"attemptCount",1,"at","2026-10-05T00:00:00Z")));item.put("eventCount",1L);item.put("eventsTruncated",false);return item;
    }
    static Map<String,Object> snapshot(List<Map<String,Object>> items) {return Map.of("status","REVIEW_REQUIRED","executionVersion",1L,"ownerId",ACTOR.userId(),"items",items,"counts",Map.of("total",items.size(),"success",0,"nonSuccess",items.size()),"snapshotAt","2026-10-05T00:00:00Z","fingerprint","source");}
    static InvestigationRepository repository() {
        var repo=mock(InvestigationRepository.class);var seq=new AtomicInteger();var refs=new AtomicInteger();
        when(repo.startStep(anyString(),anyString(),anyString(),nullable(String.class),nullable(String.class),any())).thenAnswer(i -> seq.incrementAndGet());
        when(repo.evidence(anyString(),anyString(),anyString(),anyList(),any(),any(),anyBoolean())).thenAnswer(i -> "E"+refs.incrementAndGet());return repo;
    }
    static InvestigationTools tools(InvestigationRepository repo,InvestigationProperties props,Map<String,Lookup> results) {
        var access=mock(InvestigationAccessPolicy.class);when(access.current(anyString(),anyString())).thenReturn(ACTOR);
        var mcp=mock(InvestigationMcpReader.class);when(mcp.lookup(any(),anyString(),anyString(),any())).thenAnswer(i -> results.getOrDefault(i.getArgument(2),new Lookup(LookupStatus.UNKNOWN,null,"暂未确定")));
        return new InvestigationTools(new InvestigationEvidenceStore(repo),mcp,repo,access,props);
    }
    static InvestigationSession session(List<Map<String,Object>> items,InvestigationProperties props) {return new InvestigationSession("run","token",ACTOR,snapshot(items),new InvestigationBudget(props),() -> {},() -> {});}
    static String report(String ref,String reason,String certainty,List<String> evidence) {return JsonUtil.toJson(Map.of("findings",List.of(Map.of("itemRef",ref,"reasonCode",reason,"certainty",certainty,"evidenceIds",evidence,"nextStep","MANUAL_REVIEW")),"unresolved",List.of()));}
}
