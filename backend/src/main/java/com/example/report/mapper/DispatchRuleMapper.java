package com.example.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.report.entity.DispatchRule;
import com.example.report.entity.ReportDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * DispatchRule 的数据库映射接口；基础 CRUD 不自动实施业务授权。
 * 调用方须限定租户并验证数据归属；带 FOR UPDATE 的方法必须在事务内调用，锁顺序由业务服务统一约定。
 */
@Mapper
public interface DispatchRuleMapper extends BaseMapper<DispatchRule> {
    @Select("SELECT * FROM report_definition WHERE report_id=#{reportId} AND tenant_id=#{tenantId} FOR UPDATE")
    ReportDefinition lockReport(@Param("tenantId") String tenantId, @Param("reportId") String reportId);

    @Select("SELECT * FROM dispatch_rule WHERE id=#{id} FOR UPDATE")
    DispatchRule lockRule(@Param("id") Long id);

    @Select("SELECT * FROM dispatch_rule WHERE tenant_id=#{tenantId} AND report_id=#{reportId} "
            + "AND company_code=#{companyCode} AND status='published' ORDER BY id FOR UPDATE")
    List<DispatchRule> publishedForUpdate(@Param("tenantId") String tenantId, @Param("reportId") String reportId,
                                         @Param("companyCode") String companyCode);

    @Select("SELECT * FROM dispatch_rule WHERE tenant_id=#{tenantId} AND report_id=#{reportId} "
            + "AND status='published' ORDER BY id FOR UPDATE")
    List<DispatchRule> publishedReportForUpdate(@Param("tenantId") String tenantId, @Param("reportId") String reportId);

    @Select("SELECT version FROM dispatch_rule WHERE tenant_id=#{tenantId} AND report_id=#{reportId} "
            + "AND company_code=#{companyCode} ORDER BY version DESC LIMIT 1 FOR UPDATE")
    Integer latestVersionForUpdate(@Param("tenantId") String tenantId, @Param("reportId") String reportId,
                                  @Param("companyCode") String companyCode);
}
