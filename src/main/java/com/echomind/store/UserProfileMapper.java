package com.echomind.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;

public interface UserProfileMapper extends BaseMapper<UserProfileEntity> {

    @Insert("""
            INSERT INTO user_profile (user_id, profile, updated_at)
            VALUES (#{userId}, #{profile}, #{updatedAt}) AS new
            ON DUPLICATE KEY UPDATE
                profile    = new.profile,
                updated_at = new.updated_at
            """)
    int upsert(UserProfileEntity profile);
}
