package com.huashuo.voice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.voice.dto.VoicePresetItem;
import com.huashuo.voice.entity.VoiceProfileEntity;
import com.huashuo.voice.mapper.VoiceProfileMapper;
import com.huashuo.voice.service.VoicePresetService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VoicePresetServiceImpl implements VoicePresetService {

    private final VoiceProfileMapper voiceProfileMapper;

    public VoicePresetServiceImpl(VoiceProfileMapper voiceProfileMapper) {
        this.voiceProfileMapper = voiceProfileMapper;
    }

    @Override
    public List<VoicePresetItem> listEnabledPresets() {
        LambdaQueryWrapper<VoiceProfileEntity> w = new LambdaQueryWrapper<>();
        w.eq(VoiceProfileEntity::getEnabled, 1)
                .orderByAsc(VoiceProfileEntity::getVoiceId);
        return voiceProfileMapper.selectList(w).stream().map(this::toItem).toList();
    }

    @Override
    public VoiceProfileEntity requireEnabled(Long voiceId) {
        VoiceProfileEntity entity = voiceProfileMapper.selectById(voiceId);
        if (entity == null || entity.getEnabled() == null || entity.getEnabled() != 1) {
            throw new BusinessException(40400, "Voice preset does not exist or is disabled");
        }
        return entity;
    }

    private VoicePresetItem toItem(VoiceProfileEntity e) {
        return new VoicePresetItem(
                e.getVoiceId(),
                e.getProvider(),
                e.getVoiceName(),
                e.getGender(),
                e.getScene(),
                e.getSampleUrl()
        );
    }
}
