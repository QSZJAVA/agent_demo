package com.example.report.assistant;

import com.example.report.common.ApiException;
import java.util.List;

/**
 * 通用只读业务查询协议；身份由服务器注入，字段来自目录，不能携带 SQL 或执行授权。
 * @param domain 数据域：报表、派单条目或工单
 * @param view 展示方式：列表、单项详情或完整查询范围总结
 * @param reportIds 稳定报表标识；空集合表示当前全部授权报表，服务端必须重新解析权限
 * @param companyCode 公司代码；null 表示当前全部授权公司
 * @param conditions 有界析取范式：组间 OR、组内 AND；空集合表示没有字段条件
 * @param sortField 排序字段；null 使用稳定业务标识排序
 * @param descending 是否按指定字段倒序；同值始终以稳定标识消除歧义
 * @param page 一起始页码，列表翻页不改变筛选及统计范围
 * @param size 每页记录数，范围 1–50
 * @param groupBy 总结分组字段；null 表示不额外分组
 */
public record BusinessQuery(Domain domain,View view,List<String> reportIds,String companyCode,
        List<Group> conditions,String sortField,boolean descending,int page,int size,String groupBy) {
    public enum Domain { REPORT, DISPATCH, WORK_ORDER }
    public enum View { LIST, DETAIL, SUMMARY }
    /**
     * 一组同时成立的条件。
     * @param allOf AND 条件集合，至少一项、最多八项
     */
    public record Group(List<Filter> allOf) { }
    /**
     * 字段比较；值以字符串携带，依据目录类型严格解析，不经浮点转换。
     * @param field 白名单字段名称
     * @param operator EQ、NE、IN、NOT_IN、IS_NULL、NOT_NULL、CONTAINS、STARTS_WITH、GT、GTE、LT、LTE
     * @param values 比较值；空值判断无参数，集合比较 1–30 项，其他比较恰好一项
     */
    public record Filter(String field,String operator,List<String> values) { }
    /** 校验协议大小及集合边界；字段类型、权限和排序能力由业务域进一步检查。 */
    public BusinessQuery {
        if(domain==null || view==null || reportIds==null || conditions==null || page<1 || page>2000 || size<1 || size>50
                || reportIds.size()>100 || conditions.size()>8) throw new ApiException(422,"查询参数超出支持范围");
        reportIds=List.copyOf(reportIds); conditions=List.copyOf(conditions);
        if(reportIds.stream().anyMatch(id -> id==null || id.isBlank() || id.length()>128)
                || (companyCode!=null && (companyCode.isBlank() || companyCode.length()>32))) throw new ApiException(422,"查询范围无效");
        for(var group:conditions) {
            if(group==null || group.allOf()==null || group.allOf().isEmpty() || group.allOf().size()>8) throw new ApiException(422,"查询条件组无效");
            for(var filter:group.allOf()) {
                if(filter==null || filter.field()==null || !filter.field().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
                        || filter.operator()==null || filter.values()==null || filter.values().size()>30
                        || filter.values().stream().anyMatch(v->v==null || v.length()>512)) throw new ApiException(422,"查询条件格式无效");
                int sizeOfValues=filter.values().size();
                if(switch(filter.operator()) {case "IS_NULL","NOT_NULL"->sizeOfValues!=0;case "IN","NOT_IN"->sizeOfValues<1;default->sizeOfValues!=1;})
                    throw new ApiException(422,"查询条件的参数数量不正确");
            }
        }
    }
    /** 复制当前条件到指定页码，不修改原查询或授予新增范围。 */
    public BusinessQuery atPage(int number) {return new BusinessQuery(domain,view,reportIds,companyCode,conditions,sortField,descending,number,size,groupBy);}
}
