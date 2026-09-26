package com.example.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.report.entity.DispatchPreviewItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DispatchPreviewItemMapper extends BaseMapper<DispatchPreviewItem> {

    /**
     * 多行 INSERT：一条语句写入一批快照记录。不用 MyBatis-Plus 的批量执行器，
     * 它需要切换到 BATCH 执行器，在已开启的事务里（同事务已用过普通执行器）会直接报错。
     */
    @Insert("""
            <script>
            INSERT INTO dispatch_preview_item (preview_id, seq, report_id, report_name, catalog_version, record_id, doc_no,
                company_code, label, amount, biz_date, rule_id, rule_name, rule_version, rule_description)
            VALUES
            <foreach collection="items" item="i" separator=",">
              (#{i.previewId}, #{i.seq}, #{i.reportId}, #{i.reportName}, #{i.catalogVersion}, #{i.recordId}, #{i.docNo},
               #{i.companyCode}, #{i.label}, #{i.amount}, #{i.bizDate}, #{i.ruleId}, #{i.ruleName}, #{i.ruleVersion},
               #{i.ruleDescription})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("items") List<DispatchPreviewItem> items);
}
