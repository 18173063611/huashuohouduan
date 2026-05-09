package com.huashuo.voice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.voice.dto.VoicePresetCreateRequest;
import com.huashuo.voice.dto.VoicePresetItem;
import com.huashuo.voice.entity.VoiceProfileEntity;
import com.huashuo.voice.mapper.VoiceProfileMapper;
import com.huashuo.voice.service.VoicePresetService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
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
    public VoicePresetItem createPreset(VoicePresetCreateRequest request) {
        String providerVoiceId = normalize(request.providerVoiceId());
        String voiceName = normalize(request.voiceName());
        if (!StringUtils.hasText(providerVoiceId) || !StringUtils.hasText(voiceName)) {
            throw new BusinessException(40000, "Voice type and voice name are required");
        }

        LambdaQueryWrapper<VoiceProfileEntity> w = new LambdaQueryWrapper<>();
        w.eq(VoiceProfileEntity::getProvider, "DOUBAO")
                .eq(VoiceProfileEntity::getProviderVoiceId, providerVoiceId)
                .last("limit 1");
        VoiceProfileEntity entity = voiceProfileMapper.selectOne(w);
        LocalDateTime now = LocalDateTime.now();
        if (entity == null) {
            entity = new VoiceProfileEntity();
            entity.setProvider("DOUBAO");
            entity.setProviderVoiceId(providerVoiceId);
            entity.setCreatedAt(now);
            entity.setDeleted(0);
        }
        entity.setVoiceName(voiceName);
        entity.setGender(defaultIfBlank(request.gender(), "未知"));
        entity.setScene(defaultIfBlank(request.scene(), "通用口播"));
        entity.setSampleUrl(blankToNull(request.sampleUrl()));
        entity.setEnabled(1);
        entity.setUpdatedAt(now);
        if (entity.getVoiceId() == null) {
            voiceProfileMapper.insert(entity);
        } else {
            voiceProfileMapper.updateById(entity);
        }
        return toItem(entity);
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
                e.getProviderVoiceId(),
                e.getVoiceName(),
                e.getGender(),
                e.getScene(),
                e.getSampleUrl()
        );
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private String blankToNull(String value) {
        String normalized = normalize(value);
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private String defaultIfBlank(String value, String fallback) {
        String normalized = normalize(value);
        return StringUtils.hasText(normalized) ? normalized : fallback;
    }
}
