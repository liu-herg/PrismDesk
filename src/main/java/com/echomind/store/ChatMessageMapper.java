package com.echomind.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    /**
     * 游标分页：取 id 小于 beforeId 的最近 size 条（beforeId 为空表示从最新开始）。
     * 和 LIMIT offset, size 相比，翻到很后面也只扫描 size 行，不会越翻越慢。走 idx_conv_id。
     */
    @Select("""
            <script>
            SELECT * FROM chat_message
            WHERE conversation_id = #{conversationId}
            <if test="beforeId != null">AND id &lt; #{beforeId}</if>
            ORDER BY id DESC
            LIMIT #{size}
            </script>
            """)
    List<ChatMessageEntity> pageBefore(@Param("conversationId") String conversationId,
                                       @Param("beforeId") Long beforeId,
                                       @Param("size") int size);
}
