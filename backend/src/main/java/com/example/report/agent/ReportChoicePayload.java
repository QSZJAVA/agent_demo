package com.example.report.agent;

import com.example.report.catalog.ReportRef;

import java.util.List;

/**
 * 报表选择卡片载荷：一句话命中了多张报表，由用户勾选后再查询，不允许模型或服务端自行挑选。
 * 选择后前端调用 POST /api/dispatch/previews，带回原请求的公司、排除项和范围方式，服务端重新按权限校验。
 *
 * @param candidates  候选报表（只含当前用户可见的）
 * @param preselected 已唯一确定、默认勾选的报表
 * @param query 用户对报表的说法或本次解析输入
 * @param companyCode 公司代码；查询范围为空时表示当前用户全部可见公司
 * @param excludeDocNos 用户排除的单据号，须绑定对应预览
 * @param scopeMode replace替换、append追加、remove移除报表范围
 */
public record ReportChoicePayload(
        String query,
        List<ReportRef> candidates,
        List<String> preselected,
        String companyCode,
        List<String> excludeDocNos,
        String scopeMode
) {
}
