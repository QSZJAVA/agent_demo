package com.example.report.assistant;

import com.example.report.catalog.*;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.*;
import com.example.report.operations.*;
import com.example.report.permission.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** 按公司、报表和数据域管理版本化业务词典；复用配置与历史表，专用入口逐次验证业务授权。 */
@Service
public class SemanticDictionaryService {
    private static final String PREFIX="semantic-dictionary:";
    private static final ObjectMapper STRICT=JsonUtil.strictMapper();
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final ReportCatalogService catalog;
    private final ReportCatalog registry;
    private final PermissionService permissions;
    private final OperationsAudit audit;
    public SemanticDictionaryService(JdbcTemplate jdbc,ReportCatalogService catalog,ReportCatalog registry,PermissionService permissions,OperationsAudit audit){
        this.jdbc=jdbc;this.named=new NamedParameterJdbcTemplate(jdbc);this.catalog=catalog;this.registry=registry;this.permissions=permissions;this.audit=audit;
    }
    /**
     * 一份已发布定义或未配置的版本零。
     * @param scope 经业务授权限定的词典范围
     * @param version 当前发布版本，零表示尚未配置
     * @param definition 完整定义；不包含其他公司或报表的条目
     */
    public record Entry(SemanticDictionary.Scope scope,long version,SemanticDictionary.Definition definition) { }
    /**
     * 发布请求，完整替换同一范围的定义。
     * @param expectedVersion 读取时版本，冲突时拒绝而非覆盖
     * @param definition 待验证并发布的完整定义
     * @param reason 管理员说明，1至512字，保存审计前脱敏
     */
    public record Change(long expectedVersion,SemanticDictionary.Definition definition,String reason) { }
    /**
     * 本轮采用的不可变词典上下文及版本依据。
     * @param entries 当前授权范围内实际已配置的词典
     * @param referenceTime 各阶段共同使用的参考时刻
     * @param fingerprint 定义与版本的确定性摘要，用于诊断与变更保护
     */
    public record Snapshot(List<Entry> entries,Instant referenceTime,String fingerprint) {
        /** 提供有界业务事实；配置中的说明是数据，不是覆盖系统约束的指令。 */
        public Map<String,Object> context(){return Map.of("fingerprint",fingerprint,"referenceTime",referenceTime.toString(),
                "defaultCalendar",SemanticDictionary.Definition.empty().calendar(referenceTime),"entries",entries.stream().map(e->Map.of("scope",e.scope(),"version",e.version(),
                        "definition",e.definition(),"calendar",e.definition().calendar(referenceTime))).toList());}
    }
    /** 严格解码管理表单，拒绝额外字段、缺少字段和数值隐式转换；此方法不写数据库。 */
    public static Change decodeChange(Object value){
        try {String json=JsonUtil.toJson(value);if(json.length()>96000)throw new IllegalArgumentException();var change=STRICT.readValue(json,Change.class);
            if(change==null || change.definition()==null || change.expectedVersion()<0)throw new IllegalArgumentException();return change;
        }catch(Exception failure){for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof ApiException api)throw api;throw new ApiException(422,"业务词典表单结构、类型或大小无效");}
    }
    /** 当前授权目录用于词典编辑，不返回不可见报表或公司；不会读取业务记录。 */
    public Map<String,Object> catalog(CurrentUser user){catalog.refreshForValidation();return Map.of("companies",user.companies().stream().sorted().toList(),
            "reports",catalog.visibleReports(user).stream().map(r->Map.of("reportId",r.reportId(),"reportName",r.reportName())).toList(),"admin",user.admin());}
    /** 同一范围的定义与可用字段；读取不取得发布权限或修改任何配置。 */
    public Map<String,Object> read(CurrentUser user,SemanticDictionary.Scope scope){var report=requireScope(user,scope);return Map.of("entry",readEntry(user.tenantId(),scope),
            "fields",BusinessFields.forDomain(scope.domain(),report.fields()));}
    /** 已配置词典的有界历史，只返回当前有权查看的单一范围。 */
    public List<Entry> history(CurrentUser user,SemanticDictionary.Scope scope){requireScope(user,scope);
        return jdbc.query("SELECT version,payload FROM operations_policy_revision WHERE tenant_id=? AND policy_key=? ORDER BY version DESC LIMIT 50",
                (rs,n)->entry(scope,rs.getLong(1),rs.getString(2)),user.tenantId(),key(scope));}
    /** 发布前检查字段、单位、冲突和类型；可用于界面试校验，不写数据库。 */
    public Entry validate(CurrentUser user,SemanticDictionary.Scope scope,Change change){OperationsPolicy.requireAdmin(user);var report=requireScope(user,scope);
        OperationsPolicy.requireReason(change.reason());change.definition().validate(BusinessFields.forDomain(scope.domain(),report.fields()));
        if(change.expectedVersion()!=readEntry(user.tenantId(),scope).version())throw new ApiException(409,"词典已变化，请重新读取后编辑");
        return new Entry(scope,change.expectedVersion(),change.definition());}
    /**
     * 短事务内核对版本并保存定义及历史；目录版本同时递增，旧预览与清单不能沿用变更前的业务解释。
     * @return 本次发布的新版本，重复提交旧版本返回409，不执行自动覆盖
     */
    @Transactional
    public Entry publish(CurrentUser user,SemanticDictionary.Scope scope,Change change){
        validate(user,scope,change);var fresh=permissions.resolve(user.userId());
        if(!fresh.permissionVersion().equals(user.permissionVersion()) || !fresh.tenantId().equals(user.tenantId()))throw ApiException.forbidden("发布期间权限已变化");
        OperationsPolicy.requireAdmin(fresh);requireScope(fresh,scope);
        var locked=jdbc.queryForList("SELECT report_id FROM report_definition WHERE tenant_id=? AND report_id=? FOR UPDATE",user.tenantId(),scope.reportId());
        if(locked.isEmpty())throw ApiException.notFound("报表不存在");
        String key=key(scope),initial=JsonUtil.toJson(new Entry(scope,0,SemanticDictionary.Definition.empty()));
        jdbc.update("INSERT IGNORE INTO operations_policy(tenant_id,policy_key,version,payload,updated_by,updated_at) VALUES (?,?,0,?,?,NOW(3))",user.tenantId(),key,initial,user.userId());
        var current=jdbc.queryForMap("SELECT version,payload FROM operations_policy WHERE tenant_id=? AND policy_key=? FOR UPDATE",user.tenantId(),key);
        long version=((Number)current.get("version")).longValue();if(version!=change.expectedVersion())throw new ApiException(409,"词典已被其他管理员修改，请刷新后重试");
        long next=Math.addExact(version,1);var result=new Entry(scope,next,change.definition());String json=JsonUtil.toJson(result);
        jdbc.update("INSERT IGNORE INTO operations_policy_revision(tenant_id,policy_key,version,payload,created_by,created_at) VALUES (?,?,?,?,?,NOW(3))",user.tenantId(),key,version,current.get("payload"),user.userId());
        jdbc.update("UPDATE operations_policy SET version=?,payload=?,updated_by=?,updated_at=NOW(3) WHERE tenant_id=? AND policy_key=?",next,json,user.userId(),user.tenantId(),key);
        jdbc.update("INSERT INTO operations_policy_revision(tenant_id,policy_key,version,payload,created_by,created_at) VALUES (?,?,?,?,?,NOW(3))",user.tenantId(),key,next,json,user.userId());
        jdbc.update("UPDATE report_definition SET catalog_version=catalog_version+1,updated_by=?,updated_at=NOW(3) WHERE tenant_id=? AND report_id=?",user.userId(),user.tenantId(),scope.reportId());
        audit.record(user,"SEMANTIC_DICTIONARY_PUBLISH",scope.reportId(),"SUCCESS",change.reason());registry.broadcastRefresh();return result;
    }
    /** 仅加载授权范围内的配置，未配置范围采用明确默认值；范围过大整体拒绝，不截断成完整上下文。 */
    public Snapshot snapshot(CurrentUser user,List<CatalogEntry> reports){
        var scopes=new LinkedHashMap<String,SemanticDictionary.Scope>();
        for(var report:reports)for(String company:user.companies())for(var domain:BusinessQuery.Domain.values()){
            var scope=new SemanticDictionary.Scope(domain,company,report.reportId());scopes.put(key(scope),scope);
        }
        if(scopes.size()>3000)throw new ApiException(422,"业务词典范围超过预算，请限定助手可见目录");
        var entries=new ArrayList<Entry>();var keys=new ArrayList<>(scopes.keySet());
        for(int offset=0;offset<keys.size();offset+=400){var page=keys.subList(offset,Math.min(keys.size(),offset+400));
            entries.addAll(named.query("SELECT policy_key,version,payload FROM operations_policy WHERE tenant_id=:tenant AND policy_key IN (:keys)",Map.of("tenant",user.tenantId(),"keys",page),
                    (rs,n)->entry(scopes.get(rs.getString(1)),rs.getLong(2),rs.getString(3))));}
        entries.sort(Comparator.comparing(e->key(e.scope())));
        var byId=new HashMap<String,CatalogEntry>();reports.forEach(r->byId.put(r.reportId(),r));
        for(var e:entries)e.definition().validate(BusinessFields.forDomain(e.scope().domain(),byId.get(e.scope().reportId()).fields()));
        String json=JsonUtil.toJson(entries);if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>64000)throw new ApiException(422,"业务词典上下文超过预算，请精简定义或限定授权范围");
        return new Snapshot(List.copyOf(entries),Instant.now(),Digests.sha256(json));
    }
    /** 模型调用后重新核对词典版本；新发布或删除配置也会改变摘要，不允许发布基于旧定义的计划。 */
    public void requireUnchanged(CurrentUser user,Snapshot prior){catalog.refreshForValidation();var fresh=permissions.resolve(user.userId());
        if(!fresh.tenantId().equals(user.tenantId()) || !fresh.permissionVersion().equals(user.permissionVersion()))throw ApiException.forbidden("语义处理期间权限已变化");
        if(!prior.fingerprint().equals(snapshot(fresh,catalog.visibleReports(fresh)).fingerprint()))throw new ApiException(409,"业务词典已更新，请重新发送以使用最新口径");}
    private CatalogEntry requireScope(CurrentUser user,SemanticDictionary.Scope scope){catalog.refreshForValidation();if(!user.companies().contains(scope.companyCode()))throw ApiException.forbidden("公司不存在或无权访问");return catalog.requireVisible(user,scope.reportId());}
    private Entry readEntry(String tenant,SemanticDictionary.Scope scope){var rows=jdbc.query("SELECT version,payload FROM operations_policy WHERE tenant_id=? AND policy_key=?",(rs,n)->entry(scope,rs.getLong(1),rs.getString(2)),tenant,key(scope));return rows.isEmpty()?new Entry(scope,0,SemanticDictionary.Definition.empty()):rows.get(0);}
    private static Entry entry(SemanticDictionary.Scope scope,long version,String json){try{var entry=STRICT.readValue(json,Entry.class);if(!Objects.equals(scope,entry.scope()) || version!=entry.version())throw new IllegalStateException();return entry;}catch(Exception failure){throw new ApiException(503,"业务词典存储不符合当前契约，请核查配置");}}
    /** 稳定摘要键只用于定位同一业务范围，读取仍独立执行权限检查。 */
    static String key(SemanticDictionary.Scope scope){return PREFIX+Digests.sha256(JsonUtil.toJson(scope));}
}
