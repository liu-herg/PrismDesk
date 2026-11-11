package com.echomind.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface EpisodicMemoryMapper extends BaseMapper<EpisodicMemoryEntity> {

    /** 取某用户最近的若干条情景记忆，作为相似度排序的候选集。走 idx_user_created。 */
    @Select("SELECT * FROM episodic_memory WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{limit}")
    List<EpisodicMemoryEntity> selectRecent(@Param("userId") String userId, @Param("limit") int limit);
}
