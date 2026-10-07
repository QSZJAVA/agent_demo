package com.example.report.assistant;

import com.example.report.catalog.query.FieldInfo;
import com.example.report.rule.FieldFact;
import java.util.*;

/** 模型和业务服务共用的查询字段契约；业务域只暴露标量能力，工单环节数组供详情展示而非任意表达式执行。 */
public final class BusinessFields {
    private BusinessFields() { }
    /** 公共展示及筛选字段；名称不会映射为模型提供的 SQL 列。 */
    public static List<FieldInfo> common() {
        return List.of(field("reportId","string","报表稳定标识"),field("reportName","string","报表名称"),field("companyCode","string","所属公司代码"),
                field("docNo","string","来源单据号"),field("amount","decimal","来源金额，币种见 currency；空值表示未提供"),
                field("currency","string","来源声明币种；空值表示未声明，不能跨币种合计"),field("date","date","业务日期；空值表示未配置"),
                field("status","string","报表：未派单/已派单；派单：待执行/处理中/成功/失败/结果未知/已跳过；工单：待审批/处理中/已完成/已驳回"));
    }
    /** 派单及工单字段是服务器接口事实，和可见目录字段同样受白名单校验。 */
    public static List<FieldInfo> forDomain(BusinessQuery.Domain domain,List<FieldInfo> reportFields) {
        var fields=new LinkedHashMap<String,FieldInfo>();common().forEach(f->fields.put(f.name(),f));
        // 状态属于各自业务域，模型和展示只接收该域含义，避免把派单失败误当成报表已派单。
        fields.put("status",field("status","string",switch(domain){case REPORT->"报表派单状态：未派单/已派单";case DISPATCH->"派单条目执行状态：待执行/成功/失败/结果未知/已跳过";case WORK_ORDER->"工单办理状态：待审批/处理中/已完成/已驳回";}));
        if(domain==BusinessQuery.Domain.REPORT) {
            for(var f:reportFields) if(FieldFact.supported(f.type()) && !f.name().equals("counterpartyAliases")) fields.putIfAbsent(f.name(),f);
            if(fields.containsKey("dispatched"))fields.put("dispatched",field("dispatched","boolean","来源记录是否已派单；true为已派单，false为未派单，不代表满足派单规则"));
            fields.put("recordId",field("recordId","string","报表内稳定记录标识"));
        } else if(domain==BusinessQuery.Domain.DISPATCH) {
            add(fields,"planId","清单标识");add(fields,"requestId","稳定派单请求号，未发送时为空");add(fields,"operatorName","清单创建人展示名");
            fields.put("createdByMe",field("createdByMe","boolean","是否当前登录人创建，由服务端身份计算；我的/本人发起使用true，不能把人称代词当创建人姓名"));
            add(fields,"planStatus","清单当前状态");add(fields,"message","最近派单结果说明，空值表示未提供");
            fields.put("createdDate",field("createdDate","date","清单创建日期，Asia/Shanghai"));
        } else {
            add(fields,"orderId","工单编号，须准确匹配");add(fields,"title","工单主题");add(fields,"stage","当前环节，已结束时为归档或退回");
            add(fields,"assignee","当前处理人，已结束时为空");add(fields,"requestId","关联派单请求号，固定演示示例为空");add(fields,"source","固定演示示例或实际演示派单关联");
            fields.put("createdDate",field("createdDate","date","工单创建日期，Asia/Shanghai"));
            fields.put("updatedDate",field("updatedDate","date","最近流程变化日期；固定演示不会随查询推进"));
        }
        return List.copyOf(fields.values());
    }
    private static void add(Map<String,FieldInfo> fields,String name,String description){fields.put(name,field(name,"string",description));}
    private static FieldInfo field(String name,String type,String description){return new FieldInfo(name,type,description);}
}
