package com.example.report.workspace;

import com.example.report.assistant.*;
import com.example.report.agent.PlanPayload;
import com.example.report.catalog.ReportCatalogService;
import com.example.report.common.*;
import com.example.report.config.ResourceQuotaService;
import com.example.report.dispatch.*;
import com.example.report.permission.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/**
 * 本人查询工作区：统一快照、常用定义、分析、对比与精确候选的授权边界。
 * 结构化操作不改变会话语义焦点；新自然语言条件仍由助手完整复核，快照不会授予执行权。
 */
@Service
public class QueryWorkspaceService {
    private final QuerySnapshotStore store;
    private final PermissionService permissions;
    private final ReportCatalogService catalog;
    private final BusinessAssistantService assistant;
    private final JdbcTemplate jdbc;
    private final ResourceQuotaService quotas;
    private final PreviewService previews;
    private final PlanService plans;
    private final TransactionTemplate tx;
    public QueryWorkspaceService(QuerySnapshotStore store,PermissionService permissions,ReportCatalogService catalog,BusinessAssistantService assistant,
            JdbcTemplate jdbc,ResourceQuotaService quotas,PreviewService previews,PlanService plans,PlatformTransactionManager manager) {
        this.store=store;this.permissions=permissions;this.catalog=catalog;this.assistant=assistant;this.jdbc=jdbc;this.quotas=quotas;this.previews=previews;this.plans=plans;tx=new TransactionTemplate(manager);tx.setTimeout(30);
    }
    /**
     * 工作区稳定游标分页；游标只影响位置，不替代业务范围复核。
     * @param rows 当前页可见对象，权限过滤后可能为空
     * @param nextCursor 下一扫描位置；没有后续时为空
     */
    public record Page<T>(List<T> rows,String nextCursor) { }
    /**
     * 常用查询保存参数。
     * @param name 本人命名，1至100字符
     * @param clockMode FIXED固定日期或LAST_MONTH重算上月
     * @param dateField 相对期间使用的日期字段，固定日期时必须为空
     */
    public record Save(String name,String clockMode,String dateField) {
        public Save {if(name==null || name.isBlank() || name.length()>100 || !Set.of("FIXED","LAST_MONTH").contains(Objects.toString(clockMode,""))
                || ("FIXED".equals(clockMode)!=(dateField==null)))throw new ApiException(422,"常用查询名称或日期模式无效");name=name.trim();}
    }
    private record Cursor(LocalDateTime created,String id) { }
    /** 每次读取重取权限与目录；权限变动、口径版本变化均不能只靠旧缓存允许读取。 */
    public QuerySnapshotStore.Run require(CurrentUser user,String id) {
        var current=current(user);var run=store.require(current,id);requireDefinition(current,run.result().query(),run.companies(),run.definitionHash());return run;
    }
    /** 重新解析当前身份；处理期间的撤权或租户变化使整次操作失败，不能继续使用入口处的旧授权。 */
    private CurrentUser current(CurrentUser original){var current=permissions.resolve(original.userId());if(!current.tenantId().equals(original.tenantId()) || !current.permissionVersion().equals(original.permissionVersion()))throw ApiException.forbidden("工作区读取期间权限已变化");return current;}
    /** 比对创建时公司集合和每份报表的当前口径；全公司查询不能因权限扩大而静默扩大保存的范围。 */
    private void requireDefinition(CurrentUser user,BusinessQuery query,Set<String> companies,String definition) {
        if(!user.companies().containsAll(companies) || (query.companyCode()==null && !user.companies().equals(companies)))throw ApiException.notFound("常用查询或快照的公司范围已变化，请重新保存查询");
        catalog.refreshForValidation();var reports=query.reportIds().stream().map(id->catalog.requireVisible(user,id)).toList();
        if(!definition.equals(QuerySnapshotStore.definitionHash(reports)))throw new ApiException(409,"报表或业务口径已变化，请重新查询并保存");
    }
    /** 翻页读取原快照，不调用模型或业务来源；缺行时拒绝部分结果。 */
    public BusinessResult page(CurrentUser user,String id,int page) {
        var run=require(user,id);var original=run.result();int size=original.query().size();
        if(page<1 || page>100000)throw new ApiException(422,"快照页码超出范围");
        int offset=Math.multiplyExact(page-1,size);var rows=store.rows(id,offset,size);
        if(rows.size()!=Math.min(size,Math.max(0,original.total()-offset)))throw new ApiException(410,"快照明细已不可用，请重新查询");
        require(user,id);return new BusinessResult(original.query().atPage(page),original.observedAt(),original.source(),original.columns(),rows,original.total(),original.summary(),original.snapshot());
    }
    /** 最近本人运行使用创建时刻和ID稳定分页，当前权限之外的记录不返回名称、计数或摘要。 */
    public Page<BusinessResult> list(CurrentUser user,String before) {
        current(user);var cursor=cursor(user,"runs",before);var batch=jdbc.queryForList("SELECT id,created_at FROM business_query_run WHERE tenant_id=? AND user_id=? AND expires_at>UTC_TIMESTAMP(3) AND (created_at<? OR (created_at=? AND id<?)) ORDER BY created_at DESC,id DESC LIMIT 21",
                user.tenantId(),user.userId(),cursor.created(),cursor.created(),cursor.id());
        var visible=new ArrayList<BusinessResult>();for(var row:batch.stream().limit(20).toList())try{visible.add(require(user,row.get("id").toString()).result());}catch(ApiException denied){if(!Set.of(403,404,409,410).contains(denied.getCode()))throw denied;}
        return new Page<>(visible,batch.size()>20?cursor(user,"runs",batch.get(19)):null);
    }
    /** 主动删除本人结果快照使导出同步不可用；数据文件由后台到期清理。 */
    public void expire(CurrentUser user,String id){require(user,id);store.expire(user,id);}
    /** 保存查询定义及口径，实际日期模式由用户明确选择；不保存永久权限或历史数据结果。 */
    public Map<String,Object> save(CurrentUser user,String id,Save input) {
        var run=require(user,id);validateSave(run,input);
        return tx.execute(status->{store.lock(user.tenantId());
            if(jdbc.queryForObject("SELECT COUNT(*) FROM saved_business_query WHERE tenant_id=? AND user_id=?",Integer.class,user.tenantId(),user.userId())>=100)throw new ApiException(429,"常用查询已达100项，请先删除不再使用的定义");
            require(user,id);String saved=JsonUtil.newId();jdbc.update("INSERT INTO saved_business_query(id,tenant_id,user_id,name,query_json,companies_json,definition_hash,clock_mode,date_field) VALUES (?,?,?,?,?,?,?,?,?)",
                    saved,user.tenantId(),user.userId(),input.name(),JsonUtil.toJson(run.result().query().atPage(1)),JsonUtil.toJson(run.companies()),run.definitionHash(),input.clockMode(),input.dateField());return saved(user,saved);
        });
    }
    private void validateSave(QuerySnapshotStore.Run run,Save input){if(run.result().query().view()==BusinessQuery.View.ELIGIBILITY)throw new ApiException(422,"资格核验不可保存为常用集合查询");if(input.dateField()!=null && run.result().columns().stream().noneMatch(f->f.name().equals(input.dateField()) && f.type().equals("date")))throw new ApiException(422,"相对月份字段必须是当前日期字段");}
    /** 定义列表按本人隔离；定义不含结果，失效口径在运行时明确拒绝。 */
    public List<Map<String,Object>> savedList(CurrentUser user){current(user);return jdbc.queryForList("SELECT id,name,clock_mode,date_field,version,created_at,updated_at FROM saved_business_query WHERE tenant_id=? AND user_id=? ORDER BY created_at DESC,id DESC LIMIT 100",user.tenantId(),user.userId());}
    /** 只读取本人定义，不包含来源事实；定义本身不授予执行权，重新运行另行校验当前业务范围。 */
    public Map<String,Object> saved(CurrentUser user,String id){current(user);var rows=jdbc.queryForList("SELECT * FROM saved_business_query WHERE id=? AND tenant_id=? AND user_id=?",id,user.tenantId(),user.userId());if(rows.isEmpty())throw ApiException.notFound("常用查询不存在");var row=new LinkedHashMap<>(rows.get(0));row.remove("tenant_id");row.remove("user_id");return row;}
    /** 修改名称及日期模式使用版本条件更新；不覆盖已有查询条件或口径。 */
    public Map<String,Object> updateSaved(CurrentUser user,String id,long expected,Save input) {
        var old=saved(user,id);var query=AssistantCodec.query(JsonUtil.toMap(old.get("query_json").toString()));catalog.refreshForValidation();
        if(input.dateField()!=null)for(String report:query.reportIds())if(BusinessFields.forDomain(query.domain(),catalog.requireVisible(user,report).fields()).stream().noneMatch(f->f.name().equals(input.dateField()) && f.type().equals("date")))throw new ApiException(422,"相对月份字段必须是日期字段");
        if(jdbc.update("UPDATE saved_business_query SET name=?,clock_mode=?,date_field=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND tenant_id=? AND user_id=? AND version=?",input.name(),input.clockMode(),input.dateField(),id,user.tenantId(),user.userId(),expected)!=1)throw new ApiException(409,"常用查询版本已变化，请刷新");return saved(user,id);
    }
    /** 删除本人指定版本的定义，版本冲突要求刷新；不删除既有结果快照或来源记录。 */
    public void deleteSaved(CurrentUser user,String id,long expected){saved(user,id);if(jdbc.update("DELETE FROM saved_business_query WHERE id=? AND tenant_id=? AND user_id=? AND version=?",id,user.tenantId(),user.userId(),expected)!=1)throw new ApiException(409,"常用查询版本已变化，请刷新");}
    /** 重新运行始终访问真实认证HTTP MCP；相对日期按本次运行时间重算，当前权限与口径再次核对。 */
    public BusinessResult runSaved(CurrentUser user,String id) {
        var saved=saved(user,id);var query=AssistantCodec.query(JsonUtil.toMap(saved.get("query_json").toString()));
        Set<String> companies=Set.copyOf(JsonUtil.MAPPER.convertValue(parse(saved.get("companies_json")),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){}));
        requireDefinition(user,query,companies,saved.get("definition_hash").toString());
        if("LAST_MONTH".equals(saved.get("clock_mode")))query=lastMonth(query,saved.get("date_field").toString(),LocalDate.now(ZoneId.of("Asia/Shanghai")));
        try(var permit=quotas.acquire(user,"saved-query",query.reportIds())) {ResourceQuotaService.check(permit);var result=assistant.read(user,query.atPage(1));ResourceQuotaService.check(permit);require(user,result.snapshot().id());return result;}
    }
    /** 保留其他全部字段限制；用户明确选择的日期字段替换为上个自然月闭区间。 */
    public static BusinessQuery lastMonth(BusinessQuery query,String field,LocalDate today) {
        var month=YearMonth.from(today).minusMonths(1);var groups=new ArrayList<BusinessQuery.Group>();
        var original=query.conditions().isEmpty()?List.of(new BusinessQuery.Group(List.of(new BusinessQuery.Filter(field,"NOT_NULL",List.of())))):query.conditions();
        for(var group:original){var conditions=new ArrayList<>(group.allOf().stream().filter(f->!f.field().equals(field)).toList());
            conditions.add(new BusinessQuery.Filter(field,"GTE",List.of(month.atDay(1).toString())));conditions.add(new BusinessQuery.Filter(field,"LTE",List.of(month.atEndOfMonth().toString())));groups.add(new BusinessQuery.Group(conditions));}
        return new BusinessQuery(query.domain(),query.view(),query.reportIds(),query.companyCode(),groups,query.sortField(),query.descending(),1,query.size(),query.groupBy());
    }
    /** 指标计算受工作区配额约束，完成后重查权限与快照有效期。 */
    public Map<String,Object> analyze(CurrentUser user,String id,QueryAnalysis.Request request){var run=require(user,id);try(var permit=quotas.acquire(user,"query-analysis",run.result().query().reportIds())){var result=QueryAnalysis.analyze(run,store.all(run),request);ResourceQuotaService.check(permit);require(user,id);return result;}}
    /** 完整读取两个本人快照后计算集合差异；计算前后均复核两端权限和有效期，不混淆不同查询口径。 */
    public Map<String,Object> compare(CurrentUser user,String before,String after){var left=require(user,before);var right=require(user,after);try(var permit=quotas.acquire(user,"query-compare",left.result().query().reportIds())){var result=QueryComparison.compare(left,store.all(left),right,store.all(right));ResourceQuotaService.check(permit);require(user,before);require(user,after);return result;}}
    /** 对完整授权事实做只读排名；数量、空值、币种和稳定并列规则由共同分析契约约束，不准备或执行派单。 */
    public List<Map<String,Object>> rank(CurrentUser user,String id,QueryAnalysis.Rank rank){var run=require(user,id);try(var permit=quotas.acquire(user,"query-rank",run.result().query().reportIds())){var result=QueryAnalysis.rank(run,store.all(run),rank);ResourceQuotaService.check(permit);require(user,id);return result;}}
    /**
     * 对完整授权快照排名后，仅准备同一组当前仍有效的待确认记录。
     * 幂等键由本人、快照和完整排名参数确定；重放不重建清单，来源变化时要求用户刷新核对，不能自动补选。
     */
    public PlanPayload prepareRank(CurrentUser user,String id,QueryAnalysis.Rank ranking) {
        var original=require(user,id);if(original.result().query().domain()!=BusinessQuery.Domain.REPORT)throw new ApiException(422,"只有报表数据可以准备派单候选");
        String key="query-rank:"+Digests.sha256(JsonUtil.toJson(List.of(user.userId(),id,ranking)));
        var prior=jdbc.queryForList("SELECT id FROM dispatch_plan WHERE tenant_id=? AND user_id=? AND idempotency_key=?",String.class,user.tenantId(),user.userId(),key);
        if(!prior.isEmpty())return PlanPayload.of(plans.getOwned(user,prior.get(0)));
        try(var permit=quotas.acquire(user,"query-rank-plan",original.result().query().reportIds())) {
            var selected=QueryAnalysis.rank(original,store.all(original),ranking);var refreshed=assistant.read(user,original.result().query().atPage(1));var current=require(user,refreshed.snapshot().id());
            var latest=QueryAnalysis.rank(current,store.all(current),ranking);
            if(!selected.equals(latest))throw new ApiException(409,"排名或候选事实已变化，请刷新查询并重新核对排名");
            var targets=selected.stream().map(row->new RecordTarget(new RecordKey(row.get("reportId").toString(),row.get("recordId").toString()),row.get("companyCode").toString())).toList();
            var preview=previews.previewRecords(user,null,targets,"query:"+id,n->ResourceQuotaService.check(permit),p->ResourceQuotaService.check(permit),0).snapshot();
            // 精确预览再次读取事实；对齐所有已配置字段，阻止读取与预览之间的业务变化被当作原排名确认。
            for(var candidate:preview.candidates()) {
                var row=selected.stream().filter(r->r.get("rowKey").equals(candidate.key())).findFirst().orElseThrow();
                if(!Objects.equals(row.get("amount"),candidate.amount()==null?null:candidate.amount().toPlainString()) || !Objects.equals(row.get("date"),candidate.date()==null?null:candidate.date().toString()))throw new ApiException(409,"候选金额或日期已变化，请重新查询");
                for(var field:candidate.fields())if(!Objects.equals(row.get(field.name()),field.value()))throw new ApiException(409,"候选字段已变化，请重新查询");
            }
            ResourceQuotaService.check(permit);require(user,id);return PlanPayload.of(plans.create(user,null,preview.preview().getId(),List.of(),key));
        }
    }
    private static Object parse(Object value){try{return JsonUtil.MAPPER.readTree(value.toString());}catch(Exception e){throw new ApiException(502,"常用查询定义损坏");}}
    private static Cursor cursor(CurrentUser user,String type,String encoded){if(encoded==null || encoded.isBlank())return new Cursor(LocalDateTime.of(9999,1,1,0,0),"~");
        try{if(encoded.length()>512)throw new IllegalArgumentException();String[] parts=new String(Base64.getUrlDecoder().decode(encoded),java.nio.charset.StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=4 || !parts[0].equals(Digests.sha256(user.tenantId()+":"+user.userId()+":"+type)) || !parts[2].matches("[a-zA-Z0-9_-]{1,64}") || !parts[3].equals("v1"))throw new IllegalArgumentException();return new Cursor(LocalDateTime.parse(parts[1]),parts[2]);
        }catch(Exception e){throw new ApiException(422,"分页游标无效，请从首页重试");}}
    private static String cursor(CurrentUser user,String type,Map<String,Object> row){return Base64.getUrlEncoder().withoutPadding().encodeToString((Digests.sha256(user.tenantId()+":"+user.userId()+":"+type)+"|"+QuerySnapshotStore.localTime(row.get("created_at"))+"|"+row.get("id")+"|v1").getBytes(java.nio.charset.StandardCharsets.UTF_8));}
}
