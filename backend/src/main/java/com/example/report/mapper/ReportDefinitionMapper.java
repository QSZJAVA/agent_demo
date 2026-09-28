package com.example.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.report.entity.ReportDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ReportDefinitionMapper extends BaseMapper<ReportDefinition> {
    @Select("SELECT * FROM report_definition WHERE report_id=#{id} FOR UPDATE")
    ReportDefinition lockById(@Param("id") String id);
}
