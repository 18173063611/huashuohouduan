package com.huashuo.video.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.video.DTO.CarSalesVideoDTO;
import com.huashuo.voice.dto.VoicePresetItem;
import com.huashuo.voice.entity.VoiceProfileEntity;
import com.huashuo.voice.service.VoicePresetService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CarSalesAutoTtsServiceTest {

    @Test
    void nativeMaleStyleSelectsMaleVoiceInsteadOfDefaultFirstVoice() throws Exception {
        VoicePresetService voicePresetService = mock(VoicePresetService.class);
        when(voicePresetService.listUserLibrary(7L)).thenReturn(List.of(
                new VoicePresetItem(1L, "DOUBAO", "zh_female_shuangkuaisisi_moon_bigtts", "清爽女声", "女", "通用", null),
                new VoicePresetItem(2L, "DOUBAO", "zh_male_liufei_uranus_bigtts", "稳健男声", "男", "销售", null)
        ));
        VoiceProfileEntity male = voice(2L, "zh_male_liufei_uranus_bigtts", "稳健男声", "男");
        when(voicePresetService.requireEnabledForUser(2L, 7L)).thenReturn(male);

        CarSalesAutoTtsService service = service(voicePresetService);
        VoiceProfileEntity selected = (VoiceProfileEntity) invoke(service, "resolveVoice",
                new Class<?>[]{Long.class, Long.class, String.class},
                7L, null, "male_energetic_promo");

        assertThat(selected.getVoiceId()).isEqualTo(2L);
        assertThat(selected.getProviderVoiceId()).contains("male");
    }

    @Test
    void energeticPromoStyleProvidesFastRhythmDefaults() throws Exception {
        CarSalesAutoTtsService service = service(mock(VoicePresetService.class));
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setNativeVoiceStyle("male_energetic_promo");
        request.setNativeSpeechStyle("fast_hook");

        Double speed = (Double) invoke(service, "resolveRequestedSpeed",
                new Class<?>[]{CarSalesVideoDTO.class}, request);
        Integer pitch = (Integer) invoke(service, "defaultPitchForStyle",
                new Class<?>[]{CarSalesVideoDTO.class}, request);

        assertThat(speed).isEqualTo(1.12);
        assertThat(pitch).isEqualTo(-1);
    }

    private CarSalesAutoTtsService service(VoicePresetService voicePresetService) {
        return new CarSalesAutoTtsService(
                null,
                null,
                null,
                null,
                voicePresetService,
                new ObjectMapper()
        );
    }

    private VoiceProfileEntity voice(Long id, String providerVoiceId, String name, String gender) {
        VoiceProfileEntity voice = new VoiceProfileEntity();
        voice.setVoiceId(id);
        voice.setProviderVoiceId(providerVoiceId);
        voice.setVoiceName(name);
        voice.setGender(gender);
        voice.setEnabled(1);
        return voice;
    }

    private Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
