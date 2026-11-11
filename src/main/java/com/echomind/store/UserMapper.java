package com.echomind.store;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    @Select("SELECT * FROM app_user WHERE username = #{username}")
    UserEntity selectByUsername(@Param("username") String username);

    @Update("UPDATE app_user SET last_login_at = #{at} WHERE id = #{id}")
    int updateLastLogin(@Param("id") long id, @Param("at") LocalDateTime at);

    @Update("UPDATE app_user SET enabled = #{enabled} WHERE id = #{id}")
    int updateEnabled(@Param("id") long id, @Param("enabled") boolean enabled);

    @Select("SELECT * FROM app_user ORDER BY role, id")
    List<UserEntity> selectAllOrdered();
}
