package com.example.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.report.entity.AgentConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentConversationMapper extends BaseMapper<AgentConversation> {

    /**
     * 在当前事务里锁住会话行：同一会话生成预览、生成清单互斥，
     * 保证“同一会话同一时刻只有一份有效预览、一份待确认清单”不会被并发请求打破。
     */
    @Select("SELECT id FROM agent_conversation WHERE id = #{id} FOR UPDATE")
    String lockById(@Param("id") String id);

    @Update("UPDATE agent_conversation SET preview_request_version = preview_request_version + 1 WHERE id = #{id}")
    int advancePreviewRequest(@Param("id") String id);

    @Select("SELECT preview_request_version FROM agent_conversation WHERE id = #{id}")
    Long previewRequestVersion(@Param("id") String id);
}
