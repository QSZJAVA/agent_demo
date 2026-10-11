package com.example.report.operations;

import com.example.report.permission.CurrentUser;
import com.example.report.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import lombok.extern.slf4j.Slf4j;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;

/**
 * 持久化操作耗时、版本及结果，查询派单统计时落实管理员当前租户、公司及报表授权。
 * 现有采样缺少公司归属，不能证明其符合数据范围，必须明确不可用；不能因管理员身份返回租户全量采样。
 */
@Slf4j
@Service
public class BusinessMetrics {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private static final int DETAIL_LIMIT=1000;
    private static final String CURRENT_SCOPE="CURRENT_AUTHORIZATION";
    private static final String SAMPLING_REASON="当前采样未记录公司归属，无法核实其是否属于当前授权范围；解析率与耗时指标暂不可用。";
    private static final String DISPATCH_FROM="""
            FROM dispatch_plan p JOIN dispatch_plan_item i ON i.plan_id=p.id
            WHERE p.tenant_id=:tenant AND i.company_code IN (:companies) AND i.report_id IN (:reports)
              AND p.created_at>=:since AND p.created_at<=:until
            """;
    public BusinessMetrics(JdbcTemplate jdbc) { this.jdbc = jdbc;this.named=new NamedParameterJdbcTemplate(jdbc); }

    /**
     * 指标数据与其可用范围，缺失权限或无法证明范围时不返回数据行。
     * @param available 是否能够按当前授权证明统计范围
     * @param scope 统计范围编码，CURRENT_AUTHORIZATION为当前授权，UNVERIFIABLE为采样归属不可核实
     * @param unavailableReason 不可用原因；可用时为空
     * @param complete 返回的数据是否覆盖所声明的授权范围；明细截断或不可用时为false
     * @param truncated 是否仅截取了前1000个明细分组；概览始终独立汇总
     * @param rows 统计数据行；不可用时为null，不用空数组表示权限不足
     */
    public record Series(boolean available,String scope,String unavailableReason,boolean complete,boolean truncated,List<Map<String,Object>> rows) {
        public Series {
            if(available && (rows==null || unavailableReason!=null || complete==truncated)
                    || !available && (rows!=null || complete || truncated || unavailableReason==null))throw new IllegalArgumentException("指标可用性与数据范围不一致");
            rows=rows==null?null:List.copyOf(rows);
        }
    }

    /**
     * 当前认证身份有权统计的公司与历史报表范围，不能推断为租户全量。
     * @param companyCodes 当前授权公司代码；为空表示没有公司数据权限
     * @param reportIds 当前租户中满足报表权限码的稳定标识；包含仍有历史条目的停用报表
     */
    public record Scope(List<String> companyCodes,List<String> reportIds) { }

    /**
     * 独立于明细展示上限的总体统计。
     * @param requests 解析请求采样；目前缺少公司归属，明确不可用
     * @param dispatch 当前授权范围内的全部派单条目状态，未按分组上限截断
     */
    public record Overview(Series requests,Series dispatch) { }

    /**
     * 一次数据库读取快照内的运营结果。
     * @param samples 运行明细采样及可用性
     * @param dispatch 按报表、真实条目版本和状态分组的派单明细
     * @param overview 不受明细1000组上限影响的汇总
     * @param scope 当前可证明的授权范围，不包含范围外标识
     */
    public record Snapshot(Series samples,Series dispatch,Overview overview,Scope scope) { }

    /**
     * 一次统计请求固定的业务数据库时间窗，不随明细、概览SQL执行时间移动。
     * @param since 包含的窗口起点，按数据库本地业务时间计算
     * @param until 包含的观察时刻，排除未来时间的记录
     */
    private record Window(LocalDateTime since,LocalDateTime until) { }

    /** 仅记录不含原始事实的遥测；写入失败不能把已经提交的业务操作改判为失败。 */
    public void record(CurrentUser user, String operation, String report, String version, String outcome, long started) {
        // Telemetry failure cannot turn a committed dispatch/preview into a business failure.
        try {
            jdbc.update("INSERT INTO business_metric(tenant_id,operation,report_id,version,outcome,duration_ms,created_at) VALUES (?,?,?,?,?,?,NOW(3))",
                    user.tenantId(), operation, report, version, outcome, Math.max(0,(System.nanoTime()-started)/1_000_000));
        } catch (RuntimeException e) { log.warn("业务指标写入失败 operation={}", operation); }
    }
    /** 在同一只读事务快照中计算明细与比例分母，避免并发派单让两个统计口径来自不同时间。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Snapshot read(CurrentUser user,int days) {
        Scope scope=authorizedScope(user,days);
        Window window=window(days);
        return new Snapshot(samplingUnavailable(),dispatch(user,scope,window),new Overview(samplingUnavailable(),dispatchOverview(user,scope,window)),scope);
    }

    /** 当前采样无法关联公司，拒绝返回租户全量明细；不可用与授权范围内没有样本是不同状态。 */
    public Series summary(CurrentUser user,int days) {
        requireRequest(user,days);
        return samplingUnavailable();
    }

    /** 按当前授权汇总持久条目，重试不会增加条目数；超过1000组时显式声明明细截断。 */
    public Series dispatch(CurrentUser user,int days) {
        Scope scope=authorizedScope(user,days);
        return dispatch(user,scope,window(days));
    }

    /** 顶部概览按同一授权范围独立汇总，不能从已截断的明细计算成功率。 */
    public Overview overview(CurrentUser user,int days) {
        Scope scope=authorizedScope(user,days);
        return new Overview(samplingUnavailable(),dispatchOverview(user,scope,window(days)));
    }

    private Series dispatch(CurrentUser user,Scope scope,Window window) {
        if(scope.companyCodes().isEmpty() || scope.reportIds().isEmpty())return dispatchUnavailable();
        var rows=named.queryForList("SELECT i.report_id,i.catalog_version,i.rule_version,i.status,COUNT(*) items "+DISPATCH_FROM+
                " GROUP BY i.report_id,i.catalog_version,i.rule_version,i.status ORDER BY i.report_id,i.catalog_version,i.rule_version,i.status LIMIT "+(DETAIL_LIMIT+1),parameters(user,scope,window));
        boolean truncated=rows.size()>DETAIL_LIMIT;
        return new Series(true,CURRENT_SCOPE,null,!truncated,truncated,rows.subList(0,Math.min(DETAIL_LIMIT,rows.size())));
    }

    private Series dispatchOverview(CurrentUser user,Scope scope,Window window) {
        if(scope.companyCodes().isEmpty() || scope.reportIds().isEmpty())return dispatchUnavailable();
        var rows=named.queryForList("SELECT i.status,COUNT(*) items "+DISPATCH_FROM+" GROUP BY i.status ORDER BY i.status",parameters(user,scope,window));
        return new Series(true,CURRENT_SCOPE,null,true,false,rows);
    }

    /** 报表历史数据继续受当前权限码控制；管理员角色不会扩大公司或报表集合。 */
    private Scope authorizedScope(CurrentUser user,int days) {
        requireRequest(user,days);
        var reports=jdbc.queryForList("SELECT report_id,permission_code FROM report_definition WHERE tenant_id=? ORDER BY report_id",user.tenantId()).stream()
                .filter(row->user.hasPermission((String)row.get("permission_code"))).map(row->(String)row.get("report_id")).toList();
        return new Scope(user.companies().stream().sorted().toList(),reports);
    }

    /** REPEATABLE_READ只固定行版本，不固定NOW()；读取一次数据库时钟并给全部查询绑定相同起止时间。 */
    private Window window(int days) {
        LocalDateTime until=jdbc.queryForObject("SELECT NOW(3)",LocalDateTime.class);
        return new Window(until.minusDays(days),until);
    }

    private static MapSqlParameterSource parameters(CurrentUser user,Scope scope,Window window) {
        return new MapSqlParameterSource().addValue("tenant",user.tenantId()).addValue("since",window.since()).addValue("until",window.until())
                .addValue("companies",scope.companyCodes()).addValue("reports",scope.reportIds());
    }

    private static Series samplingUnavailable() {
        return new Series(false,"UNVERIFIABLE",SAMPLING_REASON,false,false,null);
    }

    private static Series dispatchUnavailable() {
        return new Series(false,CURRENT_SCOPE,"当前账号未获公司或报表数据权限，无法统计派单；未将权限不足计为零条记录。",false,false,null);
    }

    private static void requireRequest(CurrentUser user,int days) {
        OperationsPolicy.requireAdmin(user);
        if(days<1 || days>90)throw new ApiException("统计窗口为1～90天");
    }
}
