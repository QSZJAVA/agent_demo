package com.example.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.report.entity.DispatchPreview;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * DispatchPreview 的数据库映射接口；基础 CRUD 不自动实施业务授权。
 * 调用方须限定租户并验证数据归属；带 FOR UPDATE 的方法必须在事务内调用，锁顺序由业务服务统一约定。
 */
@Mapper
public interface DispatchPreviewMapper extends BaseMapper<DispatchPreview> {
    @Select("SELECT id FROM dispatch_preview WHERE id = #{id} FOR UPDATE")
    String lockById(@Param("id") String id);

    @Select("SELECT * FROM dispatch_preview WHERE id = #{id} FOR UPDATE")
    DispatchPreview lockState(@Param("id") String id);
}
