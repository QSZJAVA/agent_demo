package com.example.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.report.entity.DispatchPreview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DispatchPreviewMapper extends BaseMapper<DispatchPreview> {
    @Select("SELECT id FROM dispatch_preview WHERE id = #{id} FOR UPDATE")
    String lockById(@Param("id") String id);

    @Select("SELECT * FROM dispatch_preview WHERE id = #{id} FOR UPDATE")
    DispatchPreview lockState(@Param("id") String id);
}
