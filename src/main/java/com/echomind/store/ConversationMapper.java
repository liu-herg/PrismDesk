package com.echomind.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    /**
     * 新会话插入一行；已有会话只累加消息数、刷新最后活跃时间（标题保留首条消息）。
     * 一条 SQL 完成"不存在就插入、存在就更新"，避免先查后写的竞态。
     * 使用 MySQL 8.0.19+ 的行别名语法（VALUES() 写法在 8.0.20 起已废弃）。
     */
    @Insert("""
            INSERT INTO conversation (id, user_id, title, message_count, last_message_at, created_at)
            VALUES (#{id}, #{userId}, #{title}, #{delta}, #{now}, #{now}) AS new
            ON DUPLICATE KEY UPDATE
                message_count   = conversation.message_count + new.message_count,
                last_message_at = new.last_message_at
            """)
    int upsertTurn(@Param("id") String id,
                   @Param("userId") String userId,
                   @Param("title") String title,
                   @Param("delta") int delta,
                   @Param("now") LocalDateTime now);

    /** 某用户的会话列表，按最后活跃时间倒序，用 before 做游标翻页。走 idx_user_last。 */
    @Select("""
            <script>
            SELECT * FROM conversation
            WHERE user_id = #{userId}
            <if test="before != null">AND last_message_at &lt; #{before}</if>
            ORDER BY last_message_at DESC
            LIMIT #{size}
            </script>
            """)
    List<ConversationEntity> pageByUser(@Param("userId") String userId,
                                        @Param("before") LocalDateTime before,
                                        @Param("size") int size);
}
