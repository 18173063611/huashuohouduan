package com.huashuo.voice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.huashuo.voice.entity.UserVoiceLibraryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface UserVoiceLibraryMapper extends BaseMapper<UserVoiceLibraryEntity> {

    /**
     * 含已逻辑删除行，用于判断是否曾为该用户初始化过私人音色库（避免删空后再次自动灌入默认三条）。
     */
    @Select("SELECT COUNT(1) FROM user_voice_library WHERE user_id = #{userId}")
    long countAnyRowsByUser(@Param("userId") Long userId);

    @Update(
            "UPDATE user_voice_library SET deleted = 0, updated_at = #{updatedAt} "
                    + "WHERE user_id = #{userId} AND voice_id = #{voiceId}"
    )
    int resurrectByUserAndVoice(
            @Param("userId") Long userId,
            @Param("voiceId") Long voiceId,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
