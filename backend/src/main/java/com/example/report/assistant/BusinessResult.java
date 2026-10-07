package com.example.report.assistant;

import com.example.report.catalog.query.FieldInfo;
import java.util.List;
import java.util.Map;

/**
 * 查询成功后的完整性凭据及分页事实；金额、长标识均以规范字符串展示，避免浏览器数值精度损失。
 * @param query 本次实际生效的受控查询
 * @param observedAt 业务服务查询完成时间，含时区偏移，不代表未来实时状态
 * @param source 业务事实来源说明；演示流程必须明确标注
 * @param columns 当前数据域可展示的字段名称、类型和说明
 * @param rows 当前页事实；工单详情可包含 steps 数组，非当前页不冒充全部数据
 * @param total 完整筛选范围记录总数，超过扫描预算时不返回部分总数
 * @param summary 完整筛选范围的确定性统计
 */
public record BusinessResult(BusinessQuery query,String observedAt,String source,List<FieldInfo> columns,
        List<Map<String,Object>> rows,int total,Summary summary) {
    /**
     * 同一读取批次的统计；金额按来源币种分组，禁止将不同币种相加。
     * @param count 完整匹配数
     * @param amountsByCurrency 已声明币种到精确十进制合计；来源未声明币种的金额不参与合计
     * @param statusCounts 状态分布，空状态记作未提供
     * @param groups 可选字段分组计数，空值记作未提供
     * @param pendingApprovers 工单当前待审批人及其待办数量；未来环节不算当前待办
     * @param unclassifiedAmountCount 有金额但未声明币种的记录数，不参与金额合计
     */
    public record Summary(int count,Map<String,String> amountsByCurrency,Map<String,Integer> statusCounts,
                          Map<String,Integer> groups,Map<String,Integer> pendingApprovers,int unclassifiedAmountCount) { }
}
