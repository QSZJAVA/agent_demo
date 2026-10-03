package com.example.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.report.entity.DispatchPlanItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * DispatchPlanItem 的数据库映射接口；基础 CRUD 不自动实施业务授权。
 * 调用方须限定租户并验证数据归属；带 FOR UPDATE 的方法必须在事务内调用，锁顺序由业务服务统一约定。
 */
@Mapper
public interface DispatchPlanItemMapper extends BaseMapper<DispatchPlanItem> {

    /** 多行 INSERT，原因同 {@link DispatchPreviewItemMapper#insertBatch} */
    @Insert("""
            <script>
            INSERT INTO dispatch_plan_item (plan_id, seq, report_id, report_name, catalog_version, record_id, doc_no,
                company_code, label, amount, biz_date, rule_id, rule_name, rule_version, status, attempt_count, updated_at, rule_snapshot)
            VALUES
            <foreach collection="items" item="i" separator=",">
              (#{i.planId}, #{i.seq}, #{i.reportId}, #{i.reportName}, #{i.catalogVersion}, #{i.recordId}, #{i.docNo},
               #{i.companyCode}, #{i.label}, #{i.amount}, #{i.bizDate}, #{i.ruleId}, #{i.ruleName}, #{i.ruleVersion},
               #{i.status}, #{i.attemptCount}, #{i.updatedAt}, #{i.ruleSnapshot})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("items") List<DispatchPlanItem> items);
}
