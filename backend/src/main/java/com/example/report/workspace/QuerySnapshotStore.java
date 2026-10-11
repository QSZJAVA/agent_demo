package com.example.report.workspace;

import com.example.report.assistant.*;
import com.example.report.catalog.CatalogEntry;
import com.example.report.catalog.query.FieldInfo;
import com.example.report.common.*;
import com.example.report.permission.CurrentUser;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * 不可变完整查询事实的持久仓储；读事务取得的事实在独立短写事务内原子保存，失败不发布半份快照。
 * 快照不提供永久业务授权；调用方还须核验当前目录、身份与口径。行按稳定序号分页，过期事实有界清理。
 */
@Repository
public class QuerySnapshotStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate write;
    /**
     * 内部完整运行信息；仅向已重新授权的调用方返回。
     * @param result 初始查询定义及统计，不含未请求页的明细
     * @param definitionHash 来源目录、字段映射和业务口径版本摘要
     * @param companies 创建时实际授权公司集合，包含当时没有记录的公司
     */
    public record Run(BusinessResult result,String definitionHash,Set<String> companies) { }
    public QuerySnapshotStore(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc;write=new TransactionTemplate(manager);write.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);write.setTimeout(30);
    }
    /** 目录发布与词典发布都会改变目录版本，防止快照/常用查询沿用旧口径。 */
    public static String definitionHash(List<CatalogEntry> reports) {
        return Digests.sha256(JsonUtil.toJson(reports.stream().sorted(Comparator.comparing(CatalogEntry::reportId))
                .map(e->List.of(e.reportId(),e.schemaVersion(),e.catalogVersion(),e.queryMode(),Objects.toString(e.queryConfig(),""))).toList()));
    }
    /**
     * 保存经过共同谓词计算的全集。租户互斥行保护保留容量；头与全部明细在一个事务提交。
     * @return 带运行标识、来源边界和过期时间的原结果
     */
    public BusinessResult save(CurrentUser user,BusinessQueryEngine.Materialized materialized,List<CatalogEntry> reports,Set<String> companies,int scanned) {
        var result=materialized.result();var rows=materialized.rows();
        if(rows.size()!=result.total() || rows.size()>100000)throw new ApiException(502,"完整查询快照数量不一致");
        var ids=reports.stream().map(CatalogEntry::reportId).collect(java.util.stream.Collectors.toSet());
        var encoded=new ArrayList<String>();long bytes=0;var digest=sha();
        digest.update(JsonUtil.toJson(List.of(result.query(),result.columns())).getBytes(StandardCharsets.UTF_8));
        for(var row:rows) {
            if(!ids.contains(row.get("reportId")) || !companies.contains(row.get("companyCode")))throw new ApiException(502,"查询快照包含范围外记录");
            String json=JsonUtil.toJson(row);encoded.add(json);byte[] data=json.getBytes(StandardCharsets.UTF_8);bytes+=data.length;digest.update((byte)'\n');digest.update(data);
        }
        if(bytes>16L*1024*1024)throw new ApiException(422,"完整快照超过16MiB预算，请缩小条件");
        long factBytes=bytes;String hash=HexFormat.of().formatHex(digest.digest()),definition=definitionHash(reports);
        String consistency=result.query().domain()==BusinessQuery.Domain.WORK_ORDER?"SOURCE_OBSERVATION":"LOCAL_REPEATABLE_READ";
        return write.execute(tx->{
            lock(user.tenantId());
            var capacity=jdbc.queryForMap("SELECT COUNT(*) n,COALESCE(SUM(fact_bytes),0) bytes FROM business_query_run WHERE tenant_id=? AND user_id=? AND expires_at>UTC_TIMESTAMP(3)",user.tenantId(),user.userId());
            if(((Number)capacity.get("n")).longValue()>=128 || ((Number)capacity.get("bytes")).longValue()+factBytes>64L*1024*1024)
                throw new ApiException(429,"本人查询快照保留容量已满，请删除不再需要的快照后重试");
            long tenantBytes=jdbc.queryForObject("SELECT COALESCE(SUM(fact_bytes),0) FROM business_query_run WHERE tenant_id=? AND expires_at>UTC_TIMESTAMP(3)",Long.class,user.tenantId());
            if(tenantBytes+factBytes>256L*1024*1024)throw new ApiException(429,"租户查询快照容量已满，请稍后重试");
            String id=JsonUtil.newId();Instant expires=Instant.now().plus(Duration.ofHours(24)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            jdbc.update("INSERT INTO business_query_run(id,tenant_id,user_id,permission_version,definition_hash,companies_json,query_json,columns_json,summary_json,row_count,fact_bytes,source,observed_at,expires_at,content_hash,scanned_rows,consistency) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id,user.tenantId(),user.userId(),user.permissionVersion(),definition,JsonUtil.toJson(companies.stream().sorted().toList()),JsonUtil.toJson(result.query()),JsonUtil.toJson(result.columns()),JsonUtil.toJson(result.summary()),
                    rows.size(),factBytes,result.source(),OffsetDateTime.parse(result.observedAt()).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(),LocalDateTime.ofInstant(expires,ZoneOffset.UTC),hash,scanned,consistency);
            jdbc.batchUpdate("INSERT INTO business_query_row(run_id,seq,row_key,data_json) VALUES (?,?,?,?)",java.util.stream.IntStream.range(0,rows.size()).boxed().toList(),200,
                    (statement,index)->{statement.setString(1,id);statement.setInt(2,index+1);statement.setString(3,rows.get(index).get("rowKey").toString());statement.setString(4,encoded.get(index));});
            return new BusinessResult(result.query(),result.observedAt(),result.source(),result.columns(),result.rows(),result.total(),result.summary(),
                    new BusinessResult.Snapshot(id,expires.toString(),"COMPLETE",consistency,hash,scanned));
        });
    }
    /** 锁住租户工作区容量；所有入队与保存路径使用同一锁顺序。 */
    public void lock(String tenant) {
        jdbc.update("INSERT INTO query_workspace_queue(tenant_id) VALUES (?) ON DUPLICATE KEY UPDATE tenant_id=tenant_id",tenant);
        jdbc.queryForObject("SELECT tenant_id FROM query_workspace_queue WHERE tenant_id=? FOR UPDATE",String.class,tenant);
    }
    /** 只读本人同权限快照；过期或撤权均不返回明细，页码不产生新的来源读取。 */
    public Run require(CurrentUser user,String id) {
        var found=jdbc.queryForList("SELECT * FROM business_query_run WHERE id=? AND tenant_id=? AND user_id=?",id,user.tenantId(),user.userId());
        if(found.isEmpty())throw ApiException.notFound("查询快照不存在或无权查看");var row=found.get(0);
        if(!user.permissionVersion().equals(row.get("permission_version")))throw ApiException.notFound("查询权限已变化，请重新查询");
        Instant expires=instant(row.get("expires_at"));if(!expires.isAfter(Instant.now()))throw new ApiException(410,"查询快照已过期，请重新查询");
        var snapshot=new BusinessResult.Snapshot(id,expires.toString(),"COMPLETE",row.get("consistency").toString(),row.get("content_hash").toString(),((Number)row.get("scanned_rows")).intValue());
        return new Run(new BusinessResult(AssistantCodec.query(JsonUtil.toMap(row.get("query_json").toString())),instant(row.get("observed_at")).toString(),row.get("source").toString(),
                decode(row.get("columns_json"),new TypeReference<List<FieldInfo>>(){}),List.of(),((Number)row.get("row_count")).intValue(),decode(row.get("summary_json"),new TypeReference<BusinessResult.Summary>(){}),snapshot),
                row.get("definition_hash").toString(),Set.copyOf(decode(row.get("companies_json"),new TypeReference<List<String>>(){})));
    }
    /** 按不可变序号闭区间读取；明细缺口不能从下一页补齐，调用方据数量拒绝损坏的页或计算批次。 */
    public List<Map<String,Object>> rows(String runId,int after,int size) {
        if(after<0 || size<1 || size>500 || after>Integer.MAX_VALUE-size)throw new ApiException(422,"快照分页参数无效");
        return jdbc.query("SELECT data_json FROM business_query_row WHERE run_id=? AND seq>? AND seq<=? ORDER BY seq",(rs,n)->JsonUtil.toMap(rs.getString(1)),runId,after,after+size);
    }
    /** 完整有界计算读取；若到期清理或其他异常造成明细缺失，拒绝部分统计。 */
    public List<Map<String,Object>> all(Run run) {
        var rows=new ArrayList<Map<String,Object>>();int count=run.result().total();
        for(int offset=0;offset<count;offset+=500)rows.addAll(rows(run.result().snapshot().id(),offset,Math.min(500,count-offset)));
        if(rows.size()!=count)throw new ApiException(410,"快照明细已不可用，请重新查询");return rows;
    }
    /** 主动释放本人快照；不会删除业务记录，关联导出随后失效。 */
    public void expire(CurrentUser user,String id) {
        require(user,id);jdbc.update("UPDATE business_query_run SET expires_at=UTC_TIMESTAMP(3) WHERE id=? AND tenant_id=? AND user_id=?",id,user.tenantId(),user.userId());
    }
    /** 每批仅删除有限过期头与其明细，保留派单及来源业务数据。 */
    public int cleanup(String tenant) {
        return jdbc.update("DELETE FROM business_query_run WHERE tenant_id=? AND expires_at<=UTC_TIMESTAMP(3) LIMIT 10",tenant);
    }
    /** MySQL DATETIME返回LocalDateTime，TIMESTAMP表达式可返回Timestamp；两者均按本表明确的UTC语义转换。 */
    public static LocalDateTime localTime(Object timestamp) {return timestamp instanceof LocalDateTime local?local:((java.sql.Timestamp)timestamp).toLocalDateTime();}
    public static Instant instant(Object timestamp) {return localTime(timestamp).toInstant(ZoneOffset.UTC);}
    private static java.security.MessageDigest sha(){try{return java.security.MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
    private static <T>T decode(Object json,TypeReference<T> type){try{return JsonUtil.MAPPER.readValue(json.toString(),type);}catch(Exception e){throw new ApiException(502,"查询快照结构损坏");}}
}
