package com.example.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.report.entity.DispatchPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DispatchPlanMapper extends BaseMapper<DispatchPlan> {
    @Select("SELECT execution_version FROM dispatch_plan WHERE id=#{id} AND status='EXECUTING' FOR UPDATE")
    Long lockExecution(@Param("id") String id);
}
