package com.example.report.dispatch;

import com.example.report.common.JsonUtil;
import com.example.report.entity.DispatchPlan;
import com.example.report.entity.DispatchPreview;
import com.example.report.web.AgentController;
import com.example.report.web.DispatchController;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 当前协议边界：退役字段拒绝读取，损坏快照不能变成全选或空范围。 */
class CurrentProtocolBoundaryTest {
    @Test void retiredRequestFieldsAreRejected() {
        assertThrows(Exception.class, () -> JsonUtil.MAPPER.readValue("{\"message\":\"查询\",\"excludeDocNos\":[\"SO1\"]}", AgentController.ChatRequest.class));
        assertThrows(Exception.class, () -> JsonUtil.MAPPER.readValue("{\"reportType\":\"sales\",\"ids\":[\"1\"]}", DispatchController.DirectRequest.class));
        assertThrows(Exception.class, () -> JsonUtil.MAPPER.readValue("{\"previewId\":\"p1\",\"excludeDocNos\":[\"SO1\"]}", DispatchController.PlanRequest.class));
    }
    @Test void currentRecordKeysSurviveDecoding() throws Exception {
        var request=JsonUtil.MAPPER.readValue("{\"previewId\":\"p1\",\"excludedRecords\":[{\"reportId\":\"rpt-sales-order\",\"recordId\":\"7\"}]}",DispatchController.PlanRequest.class);
        assertEquals(List.of(new RecordKey("rpt-sales-order","7")),request.getExcludedRecords());
    }
    @Test void missingOrDamagedExclusionsNeverBecomeAllSelected() {
        var plan=new DispatchPlan();var snapshot=new PlanSnapshot(plan,List.of());
        for(String json:new String[]{null,"","null","{}","[null]","broken"}) {
            plan.setExcludeJson(json);assertThrows(IllegalStateException.class,snapshot::excluded);
        }
        plan.setExcludeJson("[]");assertEquals(List.of(),snapshot.excluded());
    }
    @Test void missingOrDamagedScopeNeverBecomesAnEmptyScope() {
        var preview=new DispatchPreview();
        for(String json:new String[]{null,"","null","{}","[null]","broken"}) {
            preview.setReportIds(json);assertThrows(IllegalStateException.class,()->DispatchVersionService.reportIds(preview));
        }
    }
    @Test void oldHttpFieldsFailBeforeBusinessExecution() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new AgentController(null,null,null,null))
                .setControllerAdvice(new com.example.report.common.GlobalExceptionHandler())
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(JsonUtil.MAPPER)).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/agent/chat")
                .contentType("application/json").content("{\"message\":\"查询\",\"excludeDocNos\":[\"SO1\"]}")
                .header(com.example.report.permission.PermissionService.USER_HEADER,"test-user"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(400));
    }
}
