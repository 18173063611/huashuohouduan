package com.huashuo.voice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("voice_profile")
public class VoiceProfileEntity {

    @TableId(value = "voice_id", type = IdType.AUTO)
    private Long voiceId;

    private String provider;

    private String providerVoiceId;

    private String voiceName;

    private String gender;

    private String scene;

    private String sampleUrl;

    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
