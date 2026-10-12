package com.example.report.analysis;

import com.example.report.assistant.*;
import com.example.report.catalog.*;
import com.example.report.common.*;
import com.example.report.operations.*;
import com.example.report.permission.*;
import com.example.report.rule.RuleCache;
import com.example.report.workspace.QuerySnapshotStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URI;
import java.time.*;
import java.util.*;

/** 有范围、版本和来源的业务知识库；草稿不影响已发布依据，正文只是数据，发布与每次读取均重新校验业务权限。 */
@Service
public class KnowledgeService {
    private final JdbcTemplate jdbc;
    private final PermissionService permissions;
    private final ReportCatalogService catalog;
    private final SemanticDictionaryService dictionaries;
    private final RuleCache rules;
    private final OperationsAudit audit;
    public KnowledgeService(JdbcTemplate jdbc,PermissionService permissions,ReportCatalogService catalog,SemanticDictionaryService dictionaries,RuleCache rules,OperationsAudit audit) {
        this.jdbc=jdbc;this.permissions=permissions;this.catalog=catalog;this.dictionaries=dictionaries;this.rules=rules;this.audit=audit;
    }
    /**
     * 制度正文与来源；生效日期按上海时区计算，不抓取来源链接或解释为操作指令。
     * @param companyCode 资料所属的唯一公司
     * @param reportId 资料适用的唯一报表
     * @param title 资料标题，1至200字符
     * @param body 管理员维护的正文，1至12000字符
     * @param sourceName 实际制度/手册/文件来源名称，不能为空
     * @param sourceUri 可选HTTP(S)来源链接，不允许凭据、查询串或片段，不由服务端抓取
     * @param effectiveFrom 生效日期，包含当日
     * @param effectiveTo 可选失效日期，不包含当日
     */
    public record Content(String companyCode,String reportId,String title,String body,String sourceName,String sourceUri,String effectiveFrom,String effectiveTo) {
        public Content {
            new AnalysisContract.Request(companyCode,reportId,"知识资料校验",null);
            AnalysisContract.text(title,200,"标题");AnalysisContract.text(body,12000,"正文");AnalysisContract.text(sourceName,200,"来源名称");
            for(String value:List.of(title,body,sourceName))ModelEgressPolicy.requireSafeText(value);
            try {var from=LocalDate.parse(effectiveFrom);if(effectiveTo!=null&&!LocalDate.parse(effectiveTo).isAfter(from))AnalysisContract.fail("失效日期须晚于生效日期");}
            catch(RuntimeException invalid){throw new ApiException(422,"知识生效日期无效");}
            if(sourceUri!=null)try {var uri=URI.create(sourceUri);if(sourceUri.length()>512||!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException();}
            catch(RuntimeException invalid){throw new ApiException(422,"来源链接须为不含凭据、查询串或片段的HTTP(S)地址");}
        }
        boolean effective(LocalDate day){return !day.isBefore(LocalDate.parse(effectiveFrom))&&(effectiveTo==null||day.isBefore(LocalDate.parse(effectiveTo)));}
    }
    /**
     * 资料编辑须明确版本与原因，创建时版本为0。
     * @param content 最新目标正文与范围
     * @param expectedVersion 页面读取的文档版本，防止并发覆盖
     * @param reason 管理员说明的变更原因
     */
    public record Change(Content content,long expectedVersion,String reason) {
        public Change {if(content==null||expectedVersion<0)AnalysisContract.fail("知识编辑参数无效");AnalysisContract.text(reason,500,"修改原因");ModelEgressPolicy.requireSafeText(reason);}
    }
    /**
     * 发布或停用的显式操作表单，不复用过期页面的授权。
     * @param expectedVersion 当前编辑版本
     * @param reason 本次状态变更原因
     */
    public record Transition(long expectedVersion,String reason) {
        public Transition {if(expectedVersion<1)AnalysisContract.fail("知识版本无效");AnalysisContract.text(reason,500,"操作原因");ModelEgressPolicy.requireSafeText(reason);}
    }
    /**
     * 同一业务范围内的当前依据集合。
     * @param report 当前授权报表定义，仅内部使用
     * @param dictionary 同范围当前业务词典定义，仅含合法字段
     * @param sources 已授权、已生效、经过脱敏的全部有界片段
     * @param fingerprint 定义、实际有效规则和已发布资料的稳定摘要，草稿修改不改变它
     * @param referenceTime 此次依据的UTC观测时间
     */
    public record Snapshot(CatalogEntry report,SemanticDictionaryService.Entry dictionary,List<AnalysisEvidence.Source> sources,String fingerprint,Instant referenceTime) { }

    /**
     * 管理员只读检索表单，不接收客户端来源、词典、时间或召回预算。
     * @param companyCode 当前有维护权限的单一公司
     * @param reportId 当前有维护权限的单一报表
     * @param question 需要试查的完整问题，1至2000字符，不写入任务或审计正文
     */
    public record SearchRequest(String companyCode,String reportId,String question) {
        public SearchRequest {new AnalysisContract.Request(companyCode,reportId,question,null);}
    }
    /**
     * 管理员试查的范围和版本依据；仅说明本次读取时的结果，不授予后续访问权限。
     * @param companyCode 本次实际检索的公司
     * @param reportId 本次实际检索的报表
     * @param question 实际使用的问题原文，未持久化
     * @param referenceTime 本次读取时的UTC观测时间
     * @param fingerprint 词典、规则、目录和有效发布资料的当前摘要
     * @param result 与分析执行共用的实际检索结果
     */
    public record SearchPreview(String companyCode,String reportId,String question,Instant referenceTime,String fingerprint,KnowledgeSearch.Result result) { }

    /** 试查不调用模型、不读取业务行；返回前再次检查身份和完整依据版本，变化时要求显式重新检索。 */
    public SearchPreview preview(CurrentUser user,SearchRequest request) {
        permissions.requireAdmin(current(user));var before=snapshot(user,request.companyCode(),request.reportId());
        var result=KnowledgeSearch.search(request.question(),before.sources(),before.dictionary().definition(),12);
        var after=snapshot(user,request.companyCode(),request.reportId());
        if(!before.fingerprint().equals(after.fingerprint()))throw new ApiException(409,"检索期间知识依据已变化，请重新试查");
        return new SearchPreview(request.companyCode(),request.reportId(),request.question(),before.referenceTime(),before.fingerprint(),result);
    }

    /** 当前用户导航选项只来自授权目录，不向模型开放其他公司或报表。 */
    public Map<String,Object> catalog(CurrentUser user){var fresh=current(user);catalog.refreshForValidation();return Map.of("companies",fresh.companies().stream().sorted().toList(),
            "reports",catalog.visibleReports(fresh).stream().map(r->Map.of("reportId",r.reportId(),"reportName",r.reportName(),"fields",BusinessFields.forDomain(BusinessQuery.Domain.REPORT,r.fields()))).toList(),"admin",fresh.admin());}
    /** 操作期间身份变化必须显式失败，不能使用旧权限完成一次新的发布或读取。 */
    public CurrentUser current(CurrentUser original){var fresh=permissions.resolve(original.userId());if(!fresh.tenantId().equals(original.tenantId())||!fresh.permissionVersion().equals(original.permissionVersion()))throw ApiException.forbidden("分析或知识操作期间权限已变化");return fresh;}
    public CatalogEntry scope(CurrentUser user,String company,String report){var fresh=current(user);if(!fresh.companies().contains(company))throw ApiException.notFound("公司不存在或无权访问");catalog.refreshForValidation();return catalog.requireVisible(fresh,report);}

    /** 只返回当前管理员有权维护的单一范围，最多100份资料；普通用户只能通过发布依据读取。 */
    public List<Map<String,Object>> list(CurrentUser user,String company,String report){permissions.requireAdmin(current(user));scope(user,company,report);
        return jdbc.queryForList("SELECT id,row_version,published_version,status,content_json,created_at,updated_at FROM business_knowledge_document WHERE tenant_id=? AND company_code=? AND report_id=? ORDER BY updated_at DESC,id DESC LIMIT 100",user.tenantId(),company,report).stream().map(KnowledgeService::view).toList();}
    /** 读取文档当前草稿和发布版本；只供同范围管理员使用。 */
    public Map<String,Object> get(CurrentUser user,String id){permissions.requireAdmin(current(user));return view(owned(user,id,false));}
    /** 版本历史不可变，最多返回最近50项，旧资料不会自动迁入当前基线。 */
    public List<Map<String,Object>> history(CurrentUser user,String id){permissions.requireAdmin(current(user));owned(user,id,false);
        return jdbc.queryForList("SELECT version,status,content_json,changed_by,reason,created_at FROM business_knowledge_revision WHERE document_id=? AND tenant_id=? ORDER BY version DESC LIMIT 50",id,user.tenantId()).stream().map(row->{var result=new LinkedHashMap<>(row);result.put("content",AnalysisContract.decode(row.get("content_json").toString(),Content.class));result.remove("content_json");result.put("created_at",QuerySnapshotStore.instant(row.get("created_at")).toString());return (Map<String,Object>)result;}).toList();}

    /** 保存草稿采用报表→资料的固定锁顺序；已有发布版本继续可读，只有显式发布才更新对外依据。 */
    @Transactional
    public Map<String,Object> save(CurrentUser user,String id,Change change) {
        permissions.requireAdmin(current(user));var content=change.content();scope(user,content.companyCode(),content.reportId());lockReport(user,content.reportId());
        if(id==null) {
            if(change.expectedVersion()!=0)throw new ApiException(409,"新资料版本必须为0");
            int count=jdbc.queryForObject("SELECT COUNT(*) FROM business_knowledge_document WHERE tenant_id=? AND company_code=? AND report_id=?",Integer.class,user.tenantId(),content.companyCode(),content.reportId());
            if(count>=100)throw new ApiException(429,"该范围已达到100份知识资料上限");id=JsonUtil.newId();
            jdbc.update("INSERT INTO business_knowledge_document(id,tenant_id,company_code,report_id,row_version,status,content_json,updated_by) VALUES (?,?,?,?,1,'DRAFT',?,?)",id,user.tenantId(),content.companyCode(),content.reportId(),JsonUtil.toJson(content),user.userId());
            revision(user,id,1,"DRAFT",content,change.reason());
        } else {
            var row=owned(user,id,true);if(!row.get("company_code").equals(content.companyCode())||!row.get("report_id").equals(content.reportId()))throw new ApiException(422,"不能移动现有资料的业务范围");
            long next=nextVersion(row,change.expectedVersion());
            jdbc.update("UPDATE business_knowledge_document SET row_version=?,content_json=?,updated_by=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND tenant_id=?",next,JsonUtil.toJson(content),user.userId(),id,user.tenantId());
            revision(user,id,next,"DRAFT",content,change.reason());
        }
        current(user);audit.record(user,"KNOWLEDGE_DRAFT",content.reportId(),"SUCCESS",change.reason());return get(user,id);
    }
    /** 发布绑定完整正文和版本；停用撤销发布指针，历史回答随依据摘要变化失效，不把旧制度当作现行制度。 */
    @Transactional
    public Map<String,Object> transition(CurrentUser user,String id,Transition change,boolean publish) {
        permissions.requireAdmin(current(user));var initial=owned(user,id,false);lockReport(user,initial.get("report_id").toString());var row=owned(user,id,true);
        long next=nextVersion(row,change.expectedVersion());var content=AnalysisContract.decode(row.get("content_json").toString(),Content.class);
        String state=publish?"PUBLISHED":"DISABLED";
        jdbc.update("UPDATE business_knowledge_document SET row_version=?,published_version=?,status=?,updated_by=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND tenant_id=?",next,publish?next:null,state,user.userId(),id,user.tenantId());
        revision(user,id,next,state,content,change.reason());current(user);audit.record(user,publish?"KNOWLEDGE_PUBLISH":"KNOWLEDGE_DISABLE",content.reportId(),"SUCCESS",change.reason());return get(user,id);
    }
    private void lockReport(CurrentUser user,String report){if(jdbc.queryForList("SELECT report_id FROM report_definition WHERE tenant_id=? AND report_id=? FOR UPDATE",user.tenantId(),report).isEmpty())throw ApiException.notFound("报表不存在");}
    private Map<String,Object> owned(CurrentUser user,String id,boolean lock){var rows=jdbc.queryForList("SELECT * FROM business_knowledge_document WHERE id=? AND tenant_id=?"+(lock?" FOR UPDATE":""),id,user.tenantId());if(rows.isEmpty())throw ApiException.notFound("知识资料不存在或无权访问");var row=rows.get(0);scope(user,row.get("company_code").toString(),row.get("report_id").toString());return row;}
    private static long nextVersion(Map<String,Object> row,long expected){long version=((Number)row.get("row_version")).longValue();if(version!=expected)throw new ApiException(409,"知识资料已变化，请刷新后重新编辑");return Math.addExact(version,1);}
    private void revision(CurrentUser user,String id,long version,String state,Content content,String reason){jdbc.update("INSERT INTO business_knowledge_revision(document_id,tenant_id,version,status,content_json,changed_by,reason) VALUES (?,?,?,?,?,?,?)",id,user.tenantId(),version,state,JsonUtil.toJson(content),user.userId(),reason);}
    private static Map<String,Object> view(Map<String,Object> row){var result=new LinkedHashMap<String,Object>();for(String key:List.of("id","row_version","published_version","status"))result.put(key,row.get(key));result.put("content",AnalysisContract.decode(row.get("content_json").toString(),Content.class));
        for(String key:List.of("created_at","updated_at"))result.put(key,QuerySnapshotStore.instant(row.get(key)).toString());return result;}

    /** 获取授权范围内的当前依据；发布版本而非草稿进入检索，未生效/过期资料不进入模型上下文。 */
    public Snapshot snapshot(CurrentUser user,String company,String reportId) {
        var report=scope(user,company,reportId);Instant at=Instant.now();LocalDate day=at.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();String range=company+" / "+reportId;
        var sources=new ArrayList<AnalysisEvidence.Source>();var fingerprint=new ArrayList<Object>();fingerprint.add(QuerySnapshotStore.definitionHash(List.of(report)));fingerprint.add(day.toString());fingerprint.add(KnowledgeChunks.VERSION);fingerprint.add(AnalysisEvidence.SOURCE_VERSION);
        var dictionary=(SemanticDictionaryService.Entry)dictionaries.read(user,new SemanticDictionary.Scope(BusinessQuery.Domain.REPORT,company,reportId)).get("entry");
        dictionary.definition().validate(BusinessFields.forDomain(BusinessQuery.Domain.REPORT,report.fields()));fingerprint.add(dictionary);
        add(sources,"catalog-"+report.catalogVersion(),report.reportName()+"：目录与字段",Long.toString(report.catalogVersion()),range,"当前目录；观测日期 "+day,
                "报表名称："+report.reportName()+"\n用途："+Objects.toString(report.description(),"未声明")+"\n字段定义："+JsonUtil.toJson(BusinessFields.forDomain(BusinessQuery.Domain.REPORT,report.fields())));
        add(sources,"dictionary-"+Digests.sha256(JsonUtil.toJson(dictionary)).substring(0,16),report.reportName()+"：业务词典",Digests.sha256(JsonUtil.toJson(dictionary)),range,"当前词典；观测日期 "+day,JsonUtil.toJson(dictionary));
        rules.reload();var rule=rules.find(user.tenantId(),reportId,company);
        if(rule.isPresent()) {
            var item=rule.get();var facts=new LinkedHashMap<String,Object>();facts.put("id",item.getId());facts.put("version",item.getVersion());facts.put("companyCode",item.getCompanyCode());facts.put("expression",item.getExpression());facts.put("description",item.getDescription());facts.put("effectiveFrom",item.getEffectiveFrom());facts.put("effectiveTo",item.getEffectiveTo());fingerprint.add(facts);
            add(sources,"rule-"+item.getId()+"-"+item.getVersion(),report.reportName()+"：当前派单规则",Long.toString(item.getVersion()),range,"当前生效规则；观测日期 "+day,
                    "仅解释已配置的规则，不证明任意单据符合规则或已被派单。\n"+JsonUtil.toJson(facts));
        } else fingerprint.add("NO_EFFECTIVE_RULE");
        var published=jdbc.queryForList("SELECT d.id,d.published_version,r.content_json FROM business_knowledge_document d JOIN business_knowledge_revision r ON r.document_id=d.id AND r.tenant_id=d.tenant_id AND r.version=d.published_version WHERE d.tenant_id=? AND d.company_code=? AND d.report_id=? AND d.status='PUBLISHED' ORDER BY d.id LIMIT 101",user.tenantId(),company,reportId);
        if(published.size()>100)throw new ApiException(422,"知识资料超过范围预算");
        for(var row:published) {
            var content=AnalysisContract.decode(row.get("content_json").toString(),Content.class);if(!content.companyCode().equals(company)||!content.reportId().equals(reportId))throw new ApiException(503,"知识发布版本范围损坏");
            if(!content.effective(day))continue;String version=row.get("published_version").toString();fingerprint.add(List.of(row.get("id"),version,content));
            String source="来源："+content.sourceName()+(content.sourceUri()==null?"":"\n来源链接："+content.sourceUri());
            add(sources,"document-"+row.get("id")+"-v"+version,content.title(),version,range,"生效 "+content.effectiveFrom()+"；失效 "+Objects.toString(content.effectiveTo(),"未指定"),source+"\n"+content.body());
        }
        if(sources.size()>1600)throw new ApiException(422,"知识片段超过范围预算");current(user);
        return new Snapshot(report,dictionary,List.copyOf(sources),com.example.report.investigation.InvestigationJson.hash(fingerprint),at);
    }
    /** 分块优先保持段落和句子，以原文位置说明有限上下文；头部也受引用上限约束，不再次拆开引用元数据与正文。 */
    private static void add(List<AnalysisEvidence.Source> out,String id,String title,String version,String scope,String at,String content) {
        String safe=AnalysisEvidence.safeText(content);title=AnalysisEvidence.safeText(title);
        String header=AnalysisEvidence.safeText("来源标题："+title+"\n来源版本："+version+"\n适用范围："+scope+"\n时间说明："+at+"\n");
        int budget=Math.min(1400,2000-header.length()-160);
        for(var chunk:KnowledgeChunks.split(safe,budget)) {
            String location="原文位置：脱敏后来源全文第"+chunk.startLine()+"至"+chunk.endLine()+"行；UTF-16字符["+chunk.start()+","+chunk.end()+")。\n片段编号："+chunk.index()+"\n正文片段：\n";
            String text=header+location+chunk.text();if(text.length()>2000)throw new ApiException(422,"知识元数据与正文超过单片引用预算");
            // 标题参与词项匹配，只保留实际资料标题；生成的片段编号放在引用元数据中，不能制造新的检索主题。
            out.add(new AnalysisEvidence.Source(id+"-p"+chunk.index(),id,"KNOWLEDGE",title,version,scope,at,text,header.length()+location.length()));
        }
    }
}
