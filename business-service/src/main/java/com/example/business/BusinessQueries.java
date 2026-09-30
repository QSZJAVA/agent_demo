package com.example.business;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.query.*;
import com.example.report.common.ApiException;
import com.example.report.entity.ReportDefinition;
import com.example.report.mapper.ReportDefinitionMapper;
import com.example.report.permission.CurrentUser;
import com.example.report.report.ReportService;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class BusinessQueries {
    private final ReportDefinitionMapper definitions;
    private final QueryAdapterFactory adapters;
    private final ReportService reports;
    public BusinessQueries(ReportDefinitionMapper definitions,QueryAdapterFactory adapters,ReportService reports) {
        this.definitions=definitions;this.adapters=adapters;this.reports=reports;
    }
    public CatalogEntry require(CurrentUser user,String reportId,boolean lock) {
        var d=lock?definitions.lockById(reportId):definitions.selectById(reportId);
        if(d==null || !Objects.equals(d.getTenantId(),user.tenantId()) || !user.hasPermission(d.getPermissionCode())) throw ApiException.notFound("报表不存在或无权访问");
        var entry=CatalogEntry.of(d,List.of(),adapters.create(d.getQueryMode(),d.getQueryConfig()),null);
        if(!entry.published() || !entry.effectiveAt(LocalDateTime.now())) throw ApiException.notFound("报表不存在或无权访问");
        return entry;
    }
    public void requireHistoricalAccess(CurrentUser user,String reportId) {
        var d=definitions.selectById(reportId);
        if(d==null || !Objects.equals(d.getTenantId(),user.tenantId()) || !user.hasPermission(d.getPermissionCode()))
            throw ApiException.notFound("报表不存在或无权访问");
    }
    public List<Map<String,Object>> catalog(CurrentUser user) {
        return definitions.selectList(new LambdaQueryWrapper<ReportDefinition>().eq(ReportDefinition::getTenantId,user.tenantId()).orderByAsc(ReportDefinition::getSortOrder))
                .stream().filter(d->user.hasPermission(d.getPermissionCode()) && ReportDefinition.STATUS_PUBLISHED.equals(d.getStatus()))
                .map(d->CatalogEntry.of(d,List.of(),adapters.create(d.getQueryMode(),d.getQueryConfig()),null))
                .filter(e->e.effectiveAt(LocalDateTime.now()))
                .map(e->Map.<String,Object>of("reportId",e.reportId(),"reportCode",e.reportCode(),"reportName",e.reportName(),"fields",e.fields(),"dispatchEnabled",e.dispatchEnabled())).toList();
    }
    public Object page(CurrentUser user,String code,int page,int size) {
        var d=definitions.selectOne(new LambdaQueryWrapper<ReportDefinition>().eq(ReportDefinition::getTenantId,user.tenantId()).eq(ReportDefinition::getReportCode,code));
        if(d==null) throw ApiException.notFound("报表不存在或无权访问");
        require(user,d.getReportId(),false);
        return switch(code) {case "sales"->reports.pageSales(user,page,size);case "receivable"->reports.pageReceivable(user,page,size);case "expense"->reports.pageExpense(user,page,size);default->throw new ApiException("请使用 report_records 查询目录扩展报表");};
    }
    public List<FactRow> records(CurrentUser user,String reportId,String mode,Set<String> companies,String afterId,int offset,int size,List<String> ids) {
        var entry=require(user,reportId,false);
        if(size<1 || size>500 || offset<0 || offset>1000000 || (afterId!=null && afterId.length()>128)) throw new ApiException("分页参数超出范围");
        Set<String> scope=companies==null?user.companies():Set.copyOf(companies);
        if(!user.companies().containsAll(scope)) throw ApiException.forbidden("公司范围超出权限");
        var adapter=entry.adapter();
        List<FactRow> result=switch(mode) {
            case "page" -> adapter.pendingRowsPage(user.tenantId(),scope,offset,size);
            case "cursor" -> adapter.pendingRowsAfterWithRule(user.tenantId(),scope,afterId,size,null);
            case "dryRun" -> {if(!user.admin()) throw ApiException.forbidden("仅管理员可试算");yield adapter.dryRunRowsAfter(user.tenantId(),scope,afterId,size);}
            case "ids","pendingIds" -> {
                if(ids==null || ids.size()>500 || ids.stream().anyMatch(id->id==null || id.length()>128)) throw new ApiException("每次最多查询 500 个 ID");
                yield "ids".equals(mode)?adapter.rowsByIds(user.tenantId(),ids):adapter.pendingRowsByIds(user.tenantId(),ids);
            }
            default -> throw new ApiException("未知查询方式");
        };
        if(result.size()>500) throw new ApiException(502,"报表适配器未遵守分页上限");
        return result.stream().filter(row->scope.contains(row.companyCode())).toList();
    }
    public boolean probe(String mode,String config) {adapters.createAndProbe(mode,config);return true;}
}
