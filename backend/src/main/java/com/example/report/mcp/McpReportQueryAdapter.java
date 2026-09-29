package com.example.report.mcp;

import com.example.report.catalog.query.*;
import com.example.report.common.ApiException;
import com.example.report.permission.CurrentUser;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.*;

/** Immutable per-user adapter; safe across asynchronous previews, without thread-local identity. */
public class McpReportQueryAdapter implements ReportQueryAdapter {
    private final BusinessMcpClient client;
    private final String reportId;
    private final List<FieldInfo> fields;
    private final String label;
    private final CurrentUser user;
    public McpReportQueryAdapter(BusinessMcpClient client, String reportId, ReportQueryAdapter metadata) {
        this(client, reportId, metadata.fields(), metadata.docNoLabel(), null);
    }
    private McpReportQueryAdapter(BusinessMcpClient client, String reportId, List<FieldInfo> fields, String label, CurrentUser user) {
        this.client=client; this.reportId=reportId; this.fields=fields; this.label=label; this.user=user;
    }
    @Override public ReportQueryAdapter forUser(CurrentUser user) { return new McpReportQueryAdapter(client,reportId,fields,label,user); }
    @Override public List<FieldInfo> fields() { return fields; }
    @Override public String docNoLabel() { return label; }
    private List<FactRow> query(String tenant, String mode, Set<String> companies, String after, int offset, int size,
                                Collection<String> ids) {
        if (user==null || !user.tenantId().equals(tenant)) throw ApiException.forbidden("MCP 查询缺少可信用户上下文");
        Map<String,Object> args=new LinkedHashMap<>();
        args.put("reportId",reportId); args.put("mode",mode); args.put("size",size); args.put("offset",offset);
        if (companies!=null) args.put("companies",companies);
        if (after!=null) args.put("afterId",after);
        if (ids!=null) args.put("recordIds",ids);
        return client.call("report_records",user,args,new TypeReference<>() {});
    }
    @Override public List<FactRow> pendingRows(String tenant, Set<String> companies) {
        throw new ApiException("MCP 仅支持有界查询，请使用分页或游标");
    }
    @Override public List<FactRow> pendingRowsPage(String t, Set<String> c,int offset,int size) {
        return query(t,"page",c,null,offset,size,null);
    }
    @Override public List<FactRow> pendingRowsPageWithRule(String t,Set<String> c,int offset,int size,String expression) {
        return pendingRowsPage(t,c,offset,size);
    }
    @Override public List<FactRow> pendingRowsAfterWithRule(String t,Set<String> c,String after,int size,String expression) {
        // Business service chooses its own current rules; arbitrary model expressions are not executed remotely.
        return query(t,"cursor",c,after,0,size,null);
    }
    @Override public List<FactRow> dryRunRowsAfter(String t,Set<String> c,String after,int size) {
        return query(t,"dryRun",c,after,0,size,null);
    }
    @Override public List<FactRow> rowsByIds(String t,Collection<String> ids) { return query(t,"ids",null,null,0,500,ids); }
    @Override public List<FactRow> pendingRowsByIds(String t,Collection<String> ids) { return query(t,"pendingIds",null,null,0,500,ids); }
}
