package com.example.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.report.entity.ReportDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * ReportDefinition 的数据库映射接口；基础 CRUD 不自动实施业务授权。
 * 调用方须限定租户并验证数据归属；带 FOR UPDATE 的方法必须在事务内调用，锁顺序由业务服务统一约定。
 */
@Mapper
public interface ReportDefinitionMapper extends BaseMapper<ReportDefinition> {
    @Select("SELECT * FROM report_definition WHERE report_id=#{id} FOR UPDATE")
    ReportDefinition lockById(@Param("id") String id);
}
