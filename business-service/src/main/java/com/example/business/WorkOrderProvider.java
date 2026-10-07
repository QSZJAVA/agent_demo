package com.example.business;

import com.example.report.permission.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 工单系统只读边界；本地提供固定演示流程，外部接入实现须遵守同样的权限、完整性和查询预算约束。 */
public interface WorkOrderProvider {
    /**
     * 读取已限定租户、公司和报表的工单事实；不得自动审批或修改来源派单状态。
     * @param user 服务器解析的当前身份
     * @param reportIds 当前可见报表标识
     * @param companies 当前授权公司范围
     * @param dispatchRows 当前有权查看的派单条目，只有明确成功且具有请求号的条目可生成关联演示工单
     * @return 完整且有界的流程事实，包含明确演示来源
     */
    List<Map<String,Object>> read(CurrentUser user,Set<String> reportIds,Set<String> companies,List<Map<String,Object>> dispatchRows);
}
