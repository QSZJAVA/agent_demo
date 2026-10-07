package com.example.business;

import com.example.report.common.JsonUtil;
import com.example.report.permission.CurrentUser;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.util.*;

/** 固定流程与固定审批人的演示工单提供者；没有真实外部审批调用，读取不推进流程，不伪造实际派单结果。 */
@Service
public class DemoWorkOrderProvider implements WorkOrderProvider {
    private final List<Map<String,Object>> fixtures;
    public DemoWorkOrderProvider() {
        try(var in=new ClassPathResource("business/demo-work-orders.json").getInputStream()) {
            fixtures=JsonUtil.MAPPER.readValue(in,new com.fasterxml.jackson.core.type.TypeReference<>(){});
        } catch(Exception e){throw new IllegalStateException("工单演示资源无法读取",e);}
    }
    @Override public List<Map<String,Object>> read(CurrentUser user,Set<String> reportIds,Set<String> companies,List<Map<String,Object>> dispatchRows) {
        List<Map<String,Object>> result=new ArrayList<>();
        for(var fixture:fixtures) {
            if(!user.tenantId().equals(fixture.get("tenantId")) || !reportIds.contains(fixture.get("reportId")) || !companies.contains(fixture.get("companyCode"))) continue;
            var row=new LinkedHashMap<>(fixture);row.remove("tenantId");
            int stage=((Number)row.remove("stageIndex")).intValue();
            row.put("rowKey","fixture:"+row.get("orderId"));row.put("source","固定演示示例");row.put("requestId",null);
            decorate(row,stage);result.add(row);
        }
        for(var dispatch:dispatchRows) {
            if(!"成功".equals(dispatch.get("status")) || dispatch.get("requestId")==null) continue;
            var row=new LinkedHashMap<String,Object>();
            for(String key:List.of("reportId","reportName","companyCode","docNo","amount","currency","date","createdDate")) row.put(key,dispatch.get(key));
            String request=dispatch.get("requestId").toString();
            row.put("rowKey","dispatch:"+request);row.put("orderId","WO-"+request);row.put("requestId",request);
            row.put("title",Objects.toString(dispatch.get("docNo"),"单据")+"派单后处理");row.put("status","待审批");
            row.put("updatedDate",dispatch.get("createdDate"));row.put("source","实际演示派单关联");
            // 成功派单只证明提交完成，不能推断审批已经通过；关联流程固定停在首个审批环节。
            decorate(row,1);result.add(row);
        }
        return result;
    }
    /** 环节与处理人由提供者产生；终态无当前处理人，未来环节不得被统计为当前待办。 */
    private static void decorate(Map<String,Object> row,int active) {
        String status=row.get("status").toString();
        List<String> names=List.of("提交登记","部门审批","财务复核","业务处理","归档");
        List<String> people=List.of("演示经办人","林主管（演示）","陈会计（演示）","赵专员（演示）","系统归档（演示）");
        List<Map<String,Object>> steps=new ArrayList<>();
        boolean completed="已完成".equals(status),rejected="已驳回".equals(status);
        for(int i=0;i<names.size();i++) {
            var step=new LinkedHashMap<String,Object>();step.put("name",names.get(i));step.put("approver",people.get(i));
            String state=completed || i<active?"已完成":i>active?"未到达":rejected?"已驳回":"待审批".equals(status)?"待审批":"处理中";
            step.put("status",state);step.put("date","已完成".equals(state)||"已驳回".equals(state)?row.get("updatedDate"):null);
            steps.add(step);
        }
        row.put("stage",completed?"归档":rejected?"退回":names.get(active));
        row.put("assignee",completed||rejected?null:people.get(active));row.put("steps",steps);row.put("simulated",true);
        row.put("workflowSummary",completed?"固定演示流程全部完成并归档。":rejected?"固定演示流程在"+names.get(active)+"被驳回，需经办人核对后重新发起。"
                :"固定演示流程当前停在"+names.get(active)+"，处理人为"+people.get(active)+"；后续环节尚未完成。");
    }
}
