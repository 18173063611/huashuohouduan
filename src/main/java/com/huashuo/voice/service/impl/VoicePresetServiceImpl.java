package com.huashuo.voice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.voice.dto.VoicePresetCreateRequest;
import com.huashuo.voice.dto.VoicePresetItem;
import com.huashuo.voice.entity.UserVoiceLibraryEntity;
import com.huashuo.voice.entity.VoiceProfileEntity;
import com.huashuo.voice.mapper.UserVoiceLibraryMapper;
import com.huashuo.voice.mapper.VoiceProfileMapper;
import com.huashuo.voice.service.VoicePresetService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class VoicePresetServiceImpl implements VoicePresetService {

    private static final List<String> DEFAULT_PROVIDER_VOICE_IDS = List.of(
            "zh_female_shuangkuaisisi_moon_bigtts",
            "zh_male_liufei_uranus_bigtts",
            "zh_female_wanwanxiaohe_moon_bigtts"
    );

    private final VoiceProfileMapper voiceProfileMapper;
    private final UserVoiceLibraryMapper userVoiceLibraryMapper;

    public VoicePresetServiceImpl(
            VoiceProfileMapper voiceProfileMapper,
            UserVoiceLibraryMapper userVoiceLibraryMapper
    ) {
        this.voiceProfileMapper = voiceProfileMapper;
        this.userVoiceLibraryMapper = userVoiceLibraryMapper;
    }

    @Override
    public List<VoicePresetItem> listEnabledPresets() {
        LambdaQueryWrapper<VoiceProfileEntity> w = new LambdaQueryWrapper<>();
        w.eq(VoiceProfileEntity::getEnabled, 1)
                .orderByAsc(VoiceProfileEntity::getVoiceId);
        return voiceProfileMapper.selectList(w).stream().map(this::toItem).toList();
    }

    @Override
    public List<VoicePresetItem> listCatalogPresets() {
        return listEnabledPresets();
    }

    @Override
    public void ensureDefaultUserLibraryIfFirstVisit(Long userId) {
        if (userVoiceLibraryMapper.countAnyRowsByUser(userId) > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (String providerVoiceId : DEFAULT_PROVIDER_VOICE_IDS) {
            LambdaQueryWrapper<VoiceProfileEntity> w = new LambdaQueryWrapper<>();
            w.eq(VoiceProfileEntity::getProviderVoiceId, providerVoiceId).last("limit 1");
            VoiceProfileEntity v = voiceProfileMapper.selectOne(w);
            if (v == null || v.getVoiceId() == null) {
                continue;
            }
            UserVoiceLibraryEntity row = new UserVoiceLibraryEntity();
            row.setUserId(userId);
            row.setVoiceId(v.getVoiceId());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            row.setDeleted(0);
            userVoiceLibraryMapper.insert(row);
        }
    }

    @Override
    public List<VoicePresetItem> listUserLibrary(Long userId) {
        LambdaQueryWrapper<UserVoiceLibraryEntity> w = new LambdaQueryWrapper<>();
        w.eq(UserVoiceLibraryEntity::getUserId, userId).orderByAsc(UserVoiceLibraryEntity::getLibraryId);
        return userVoiceLibraryMapper.selectList(w).stream()
                .map(UserVoiceLibraryEntity::getVoiceId)
                .map(voiceProfileMapper::selectById)
                .filter(v -> v != null && v.getEnabled() != null && v.getEnabled() == 1)
                .map(this::toItem)
                .toList();
    }

    @Override
    public void addVoiceToUserLibrary(Long userId, Long voiceId) {
        VoiceProfileEntity voice = voiceProfileMapper.selectById(voiceId);
        if (voice == null || voice.getEnabled() == null || voice.getEnabled() != 1) {
            throw new BusinessException(40400, "音色不存在或未启用");
        }
        LocalDateTime now = LocalDateTime.now();
        int revived = userVoiceLibraryMapper.resurrectByUserAndVoice(userId, voiceId, now);
        if (revived > 0) {
            return;
        }
        UserVoiceLibraryEntity row = new UserVoiceLibraryEntity();
        row.setUserId(userId);
        row.setVoiceId(voiceId);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setDeleted(0);
        userVoiceLibraryMapper.insert(row);
    }

    @Override
    public void removeVoiceFromUserLibrary(Long userId, Long voiceId) {
        LambdaQueryWrapper<UserVoiceLibraryEntity> w = new LambdaQueryWrapper<>();
        w.eq(UserVoiceLibraryEntity::getUserId, userId).eq(UserVoiceLibraryEntity::getVoiceId, voiceId);
        userVoiceLibraryMapper.delete(w);
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

    @Override
    public VoiceProfileEntity requireEnabledForUser(Long voiceId, Long ownerUserId) {
        VoiceProfileEntity voice = requireEnabled(voiceId);
        if (ownerUserId == null) {
            return voice;
        }
        LambdaQueryWrapper<UserVoiceLibraryEntity> w = new LambdaQueryWrapper<>();
        w.eq(UserVoiceLibraryEntity::getUserId, ownerUserId).eq(UserVoiceLibraryEntity::getVoiceId, voiceId);
        Long c = userVoiceLibraryMapper.selectCount(w);
        if (c == null || c == 0L) {
            throw new BusinessException(40300, "该音色不在您的私人音色库中，请先在资产中心加入");
        }
        return voice;
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
