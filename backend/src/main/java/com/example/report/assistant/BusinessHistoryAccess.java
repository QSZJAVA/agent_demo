package com.example.report.assistant;

import com.example.report.common.ApiException;
import com.example.report.permission.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** 查询历史的权限撤销边界；会话中曾取得的明细与总数属于同一权限快照，权限改变后整个会话不再展示。 */
@Component
public class BusinessHistoryAccess {
    private final JdbcTemplate jdbc;
    private final com.example.report.catalog.ReportCatalogService catalog;
    public BusinessHistoryAccess(JdbcTemplate jdbc,com.example.report.catalog.ReportCatalogService catalog){this.jdbc=jdbc;this.catalog=catalog;}
    /** 仅读取当前用户所属状态；首次业务查询前无权限快照，不因此阻止普通会话。 */
    public boolean readable(CurrentUser user,String conversationId) {
        var rows=jdbc.queryForList("SELECT user_id,state_json FROM semantic_dialogue WHERE conversation_id=? AND tenant_id=?",
                conversationId,user.tenantId());
        if(rows.isEmpty()) return true;
        var state=com.example.report.common.JsonUtil.toMap(rows.get(0).get("state_json").toString());
        Object version=state.get("businessPermissionVersion");
        if(version==null)return true;
        if(Objects.equals(rows.get(0).get("user_id"),user.userId())) {
            if(!Objects.equals(version,user.permissionVersion()))return false;
        } else {
            // 管理员追溯会携带别人的会话；不能因当前用户名不匹配而当作“没有查询历史”。
            Object scope=state.get("businessCompanyCodes");
            if(!user.admin() || !(scope instanceof java.util.List<?> companies) || !user.companies().containsAll(companies))return false;
        }
        Object ids=state.get("businessReportIds");if(!(ids instanceof java.util.List<?> reports))return false;
        try{catalog.refreshForValidation();for(Object id:reports)catalog.requireVisible(user,id.toString());return true;}catch(ApiException denied){return false;}
    }
    /** 读取历史、继续会话前检查，避免只遮挡卡片却从助手总结文本泄露已撤销事实。 */
    public void require(CurrentUser user,String conversationId){if(!readable(user,conversationId))throw ApiException.notFound("会话权限已变化，请新建会话重新查询");}
}
