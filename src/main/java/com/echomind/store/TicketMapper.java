package com.echomind.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface TicketMapper extends BaseMapper<TicketEntity> {

    /**
     * 乐观锁更新：只有数据库里的版本号仍然等于 expectedVersion 时才更新，并把版本号 +1。
     * 返回 0 表示在"读取"和"更新"之间已经有别人改过这张工单，本次修改作废。
     * 可为 NULL 的参数显式写 jdbcType，避免驱动推断类型出错。
     */
    @Update("""
            UPDATE ticket SET
                status      = #{t.status},
                assignee    = #{t.assignee, jdbcType=VARCHAR},
                assigned_at = #{t.assignedAt, jdbcType=TIMESTAMP},
                resolved_at = #{t.resolvedAt, jdbcType=TIMESTAMP},
                closed_at   = #{t.closedAt, jdbcType=TIMESTAMP},
                updated_at  = #{t.updatedAt},
                version     = version + 1
            WHERE id = #{t.id} AND version = #{expectedVersion}
            """)
    int updateWithVersion(@Param("t") TicketEntity ticket, @Param("expectedVersion") int expectedVersion);

    /** 某会话当前的未结工单（最多一张，由唯一索引保证）。 */
    @Select("SELECT * FROM ticket WHERE open_conversation_id = #{conversationId}")
    TicketEntity selectOpenByConversation(@Param("conversationId") String conversationId);

    /** 工单列表：可按状态、坐席筛选，按 id 倒序游标翻页。 */
    @Select("""
            <script>
            SELECT * FROM ticket
            <where>
                <if test="status != null">AND status = #{status}</if>
                <if test="assignee != null">AND assignee = #{assignee}</if>
                <if test="beforeId != null">AND id &lt; #{beforeId}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{size}
            </script>
            """)
    List<TicketEntity> page(@Param("status") String status,
                            @Param("assignee") String assignee,
                            @Param("beforeId") Long beforeId,
                            @Param("size") int size);
}
