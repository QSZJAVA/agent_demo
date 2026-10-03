package com.example.report.operations;

import com.example.report.common.*;
import com.example.report.entity.*;
import com.example.report.permission.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/**
 * 保存并读取报表定义与别名的不可变目录版本快照。
 * 调用方负责管理员授权与修改事务；回滚会创建新的当前版本，不能覆盖历史快照。
 */
@Service
public class CatalogRevisions {
    private final JdbcTemplate jdbc;
    public CatalogRevisions(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    /**
     * 业务历史或事实的不可变展示快照。
     * @param definition 报表定义的完整历史快照
     * @param aliases 报表别名集合
     */
    public record Snapshot(ReportDefinition definition, List<ReportAlias> aliases) { }
    public void capture(ReportDefinition d,List<ReportAlias> aliases) {
        jdbc.update("INSERT IGNORE INTO catalog_revision(tenant_id,report_id,catalog_version,definition_json,aliases_json,created_by,created_at) VALUES (?,?,?,?,?,?,NOW(3))",
                d.getTenantId(),d.getReportId(),d.getCatalogVersion(),JsonUtil.toJson(d),JsonUtil.toJson(aliases),d.getUpdatedBy());
    }
    public List<Map<String,Object>> list(CurrentUser user,String id) {
        return jdbc.queryForList("SELECT catalog_version,created_by,created_at FROM catalog_revision WHERE tenant_id=? AND report_id=? ORDER BY catalog_version DESC LIMIT 100",user.tenantId(),id);
    }
    public Snapshot load(CurrentUser user,String id,long version) {
        var rows=jdbc.query("SELECT definition_json,aliases_json FROM catalog_revision WHERE tenant_id=? AND report_id=? AND catalog_version=?",
                (rs,n)->new Snapshot(JsonUtil.fromJson(rs.getString(1),ReportDefinition.class),
                        Arrays.asList(JsonUtil.fromJson(rs.getString(2),ReportAlias[].class))),user.tenantId(),id,version);
        if(rows.isEmpty()) throw ApiException.notFound("目录版本不存在");
        return rows.get(0);
    }
}
