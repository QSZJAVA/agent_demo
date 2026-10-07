package com.example.business;

import com.example.report.assistant.*;
import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.ApiException;
import com.example.report.permission.CurrentUser;
import com.example.report.rule.FieldFact;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.util.*;

/**
 * 通用查询的权威业务边界；租户、公司及报表在读取前限定，派单普通用户仅看本人，管理员仍受业务范围限制。
 * 完整扫描受预算限制，结果与汇总共享同一数据库快照；任何来源失败都不返回部分成功。
 */
@Service
public class BusinessReadService {
    private final BusinessQueries queries;
    private final NamedParameterJdbcTemplate jdbc;
    private final WorkOrderProvider orders;
    private final int maxRows;
    private final int maxSeconds;
    private final BusinessQueryProperties units;
    private static final long MAX_FACT_BYTES=16L*1024*1024;
    public BusinessReadService(BusinessQueries queries,NamedParameterJdbcTemplate jdbc,WorkOrderProvider orders,
            @Value("${business.query.max-scan-rows:100000}") int maxRows,@Value("${business.query.max-scan-seconds:120}") int maxSeconds,BusinessQueryProperties units) {
        if(maxRows<1 || maxRows>100000 || maxSeconds<1 || maxSeconds>120) throw new IllegalArgumentException("业务查询预算无效");
        this.queries=queries;this.jdbc=jdbc;this.orders=orders;this.maxRows=maxRows;this.maxSeconds=maxSeconds;this.units=units;
    }
    /**
     * 只读事务内执行完整授权范围查询；租户身份不来自请求，分页只截取最终结果，预算超限不会泄露部分统计。
     * @param user 当前服务认证解析出的业务身份
     * @param query 严格受控的查询条件
     * @return 已授权且完整的统计及当前页
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=125)
    public BusinessResult query(CurrentUser user,BusinessQuery query) {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(maxSeconds).toNanos();
        Set<String> companies=query.companyCode()==null?user.companies():Set.of(query.companyCode());
        if(!user.companies().containsAll(companies)) throw ApiException.forbidden("公司不存在或无权访问");
        Set<String> allowed=queries.catalog(user).stream().map(row->row.get("reportId").toString()).collect(java.util.stream.Collectors.toSet());
        List<String> ids=query.reportIds().isEmpty()?allowed.stream().sorted().toList():query.reportIds().stream().distinct().sorted().toList();
        if(!allowed.containsAll(ids)) throw ApiException.notFound("报表不存在或无权访问");
        List<CatalogEntry> entries=ids.stream().map(id->queries.require(user,id,false)).toList();
        List<Map<String,Object>> rows=new ArrayList<>();
        long factBytes=0;
        List<FieldInfo> sourceFields=intersection(entries);
        if(query.domain()==BusinessQuery.Domain.REPORT) {
            for(var entry:entries) {
                String after=null;Set<String> seen=new HashSet<>();
                while(true) {
                    budget(rows.size(),deadline);
                    var batch=entry.adapter().dataRowsAfter(user.tenantId(),companies,after,Math.min(100,maxRows-rows.size()+1));
                    if(batch.size()>500) throw new ApiException(502,"报表接口超过分页上限");
                    if(batch.isEmpty()) break;
                    for(var data:batch) {
                        var fact=data.fact();
                        if(fact==null || fact.recordId()==null || fact.recordId().isBlank() || !seen.add(fact.recordId()) || !companies.contains(fact.companyCode()))
                            throw new ApiException(502,"报表来源返回重复、缺失标识或越权数据");
                        var row=new LinkedHashMap<String,Object>();
                        for(var field:FieldFact.capture(entry.fields(),fact.facts())) row.put(field.name(),field.value());
                        row.put("rowKey",entry.reportId()+":"+fact.recordId());row.put("recordId",fact.recordId());
                        row.put("reportId",entry.reportId());row.put("reportName",entry.reportName());row.put("companyCode",fact.companyCode());
                        row.put("docNo",fact.docNo());row.put("amount",fact.amount()==null?null:fact.amount().toPlainString());row.put("date",fact.date()==null?null:fact.date().toString());
                        if(!row.containsKey("currency"))row.put("currency",units.getReportCurrencies().get(entry.reportId()));
                        row.put("status",data.status());factBytes=checkBytes(factBytes,row);rows.add(row);budget(rows.size(),deadline);
                    }
                    after=batch.get(batch.size()-1).fact().recordId();
                }
            }
        } else {
            rows.addAll(dispatchRows(user,ids,companies));budget(rows.size(),deadline);
            for(var row:rows)factBytes=checkBytes(factBytes,row);
            if(query.domain()==BusinessQuery.Domain.WORK_ORDER) {
                rows=new ArrayList<>(orders.read(user,Set.copyOf(ids),companies,rows));budget(rows.size(),deadline);
                factBytes=0;for(var row:rows)factBytes=checkBytes(factBytes,row);
                var names=new HashMap<String,String>();entries.forEach(entry->names.put(entry.reportId(),entry.reportName()));
                for(var row:rows) {
                    if(!ids.contains(row.get("reportId")) || !companies.contains(row.get("companyCode"))) throw new ApiException(502,"工单接口返回越权数据");
                    row.put("reportName",names.get(row.get("reportId")));
                }
            }
        }
        var result=BusinessQueryEngine.execute(query,BusinessFields.forDomain(query.domain(),sourceFields),rows,
                query.domain()==BusinessQuery.Domain.WORK_ORDER?"演示工单：固定环节和审批人；不代表真实外部审批进度":"业务系统当前记录");
        budget(rows.size(),deadline);return result;
    }
    /** 派单来源是持久清单条目，既含待执行也含已执行；参数绑定且在 SQL 中落实租户、公司和操作者范围。 */
    private List<Map<String,Object>> dispatchRows(CurrentUser user,List<String> ids,Set<String> companies) {
        if(ids.isEmpty() || companies.isEmpty()) return List.of();
        var params=new MapSqlParameterSource().addValue("tenant",user.tenantId()).addValue("reports",ids).addValue("companies",companies)
                .addValue("operator",user.userId()).addValue("limit",maxRows+1);
        String sql="SELECT i.id,i.plan_id,i.report_id,i.record_id,i.doc_no,i.company_code,i.amount,i.biz_date,i.status,i.external_request_id,i.error_message,"+
                "CASE WHEN p.status='PENDING' AND p.expires_at<=NOW(3) THEN 'EXPIRED' ELSE p.status END AS plan_status,p.created_at,p.user_id,u.display_name,d.report_name FROM dispatch_plan_item i JOIN dispatch_plan p ON p.id=i.plan_id " +
                "JOIN report_definition d ON d.report_id=i.report_id AND d.tenant_id=p.tenant_id LEFT JOIN app_user u ON u.user_id=p.user_id AND u.tenant_id=p.tenant_id " +
                "WHERE p.tenant_id=:tenant AND i.report_id IN (:reports) AND i.company_code IN (:companies)"+
                (user.admin()?"":" AND p.user_id=:operator")+" ORDER BY p.created_at,i.id LIMIT :limit";
        return jdbc.query(sql,params,(rs,index)->{
            var row=new LinkedHashMap<String,Object>();
            row.put("rowKey",rs.getString("plan_id")+":"+rs.getString("id"));
            for(String key:List.of("report_id","record_id","company_code","doc_no","plan_id")) row.put(switch(key){case "report_id"->"reportId";case "record_id"->"recordId";case "company_code"->"companyCode";case "doc_no"->"docNo";default->"planId";},rs.getString(key));
            row.put("reportName",rs.getString("report_name"));row.put("amount",rs.getBigDecimal("amount")==null?null:rs.getBigDecimal("amount").toPlainString());
            row.put("currency",units.getReportCurrencies().get(rs.getString("report_id")));
            row.put("date",rs.getDate("biz_date")==null?null:rs.getDate("biz_date").toLocalDate().toString());
            row.put("status",status(rs.getString("status")));row.put("planStatus",planStatus(rs.getString("plan_status")));
            row.put("requestId",rs.getString("external_request_id"));row.put("message",rs.getString("error_message"));row.put("operatorName",rs.getString("display_name"));
            // 本人归属用认证标识比较，不能依赖可能重复或变更的展示名，也不向模型暴露身份标识。
            row.put("createdByMe",user.userId().equals(rs.getString("user_id")));
            row.put("createdDate",rs.getTimestamp("created_at").toLocalDateTime().toLocalDate().toString());return row;
        });
    }
    /** 多报表筛选仅提供名称和类型共同存在的来源字段，防止拿缺失字段当 NULL 匹配。 */
    private static List<FieldInfo> intersection(List<CatalogEntry> entries) {
        if(entries.isEmpty()) return List.of();
        return entries.get(0).fields().stream().filter(f->entries.stream().allMatch(e->e.fields().stream().anyMatch(other->other.name().equals(f.name()) && other.type().equals(f.type())))).toList();
    }
    private void budget(int count,long deadline){if(count>maxRows || System.nanoTime()>deadline || Thread.currentThread().isInterrupted()) throw new ApiException(422,"查询范围超过预算，请缩小公司、报表或使用数据源专用查询接口；未返回部分结果");}
    /** 行数之外另限制保留事实的字节体积，防止大量长字段在完整统计前耗尽堆内存。 */
    private static long checkBytes(long bytes,Map<String,Object> row) {
        long next=bytes+com.example.report.common.JsonUtil.toJson(row).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(next>MAX_FACT_BYTES)throw new ApiException(422,"查询事实超过16MiB预算，请缩小公司或报表范围；未返回部分统计");return next;
    }
    private static String status(String value){return switch(value){case "PENDING"->"待执行";case "SUCCESS"->"成功";case "FAILED"->"失败";case "UNKNOWN"->"结果未知";case "SKIPPED"->"已跳过";default->value;};}
    private static String planStatus(String value){return switch(value){case "PENDING"->"待确认";case "EXECUTING"->"执行中";case "EXECUTED"->"已执行";case "REVIEW_REQUIRED"->"待核对";case "CANCELLED"->"已取消";case "EXPIRED"->"已过期";default->value;};}
}
