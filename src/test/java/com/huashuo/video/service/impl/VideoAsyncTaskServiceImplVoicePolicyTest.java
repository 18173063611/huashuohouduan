package com.huashuo.video.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.video.DTO.CarSalesVideoDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VideoAsyncTaskServiceImplVoicePolicyTest {

    private final VideoAsyncTaskServiceImpl service = new VideoAsyncTaskServiceImpl(
            null,
            new ObjectMapper(),
            null
    );

    @Test
    void missingAudioModeDefaultsToSilentModeBeforeTaskIsStored() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setBrandModel("Jetour G700");
        request.setSellingPoints("front lighting and cabin details");

        invokePrepare(request);

        assertThat(request.getAudioMode()).isEqualTo("none");
        assertThat(request.getVoicePolicy()).isEqualTo("none");
        assertThat(request.getAudioUrl()).isNull();
    }

    @Test
    void explicitAutoTtsVoicePolicyStillEnablesAutoTtsCompatibility() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setVoicePolicy("auto_tts");

        invokePrepare(request);

        assertThat(request.getAudioMode()).isEqualTo("auto_tts");
        assertThat(request.getVoicePolicy()).isEqualTo("auto_tts");
    }

    @Test
    void implicitAutoVoiceoverFromAutoTextSourceIsSilencedBeforeTaskIsStored() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("auto_tts");
        request.setVoicePolicy("auto_tts");
        request.setVoiceTextSource("auto");
        request.setFinalVoiceText("Auto-filled default narration.");
        CarSalesVideoDTO.Scene scene = new CarSalesVideoDTO.Scene();
        scene.setVoiceText("Auto-filled segment line.");
        request.setScenes(List.of(scene));

        invokePrepare(request);

        assertThat(request.getAudioMode()).isEqualTo("none");
        assertThat(request.getVoicePolicy()).isEqualTo("none");
        assertThat(request.getFinalVoiceText()).isNull();
        assertThat(scene.getVoiceText()).isNull();
    }

    @Test
    void manualAutoTtsRequestStillEnablesGeneratedVoiceoverBeforeTaskIsStored() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setAudioMode("auto_tts");
        request.setVoicePolicy("auto_tts");
        request.setVoiceTextSource("manual");
        request.setStrictVoiceText(true);
        request.setFinalVoiceText("A short user-provided walkaround narration.");

        invokePrepare(request);

        assertThat(request.getAudioMode()).isEqualTo("auto_tts");
        assertThat(request.getVoicePolicy()).isEqualTo("auto_tts");
        assertThat(request.getFinalVoiceText()).isEqualTo("A short user-provided walkaround narration.");
    }

    @Test
    void explicitModelNativeVoicePolicyStillEnablesNativeAudioCompatibility() {
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setVoicePolicy("model_native");
        request.setVoiceTextSource("manual");
        request.setStrictVoiceText(true);

        invokePrepare(request);

        assertThat(request.getAudioMode()).isEqualTo("model_native");
        assertThat(request.getVoicePolicy()).isEqualTo("model_native");
    }

    private void invokePrepare(CarSalesVideoDTO request) {
        try {
            Method method = VideoAsyncTaskServiceImpl.class.getDeclaredMethod(
                    "prepareCarSalesVoicePolicy", CarSalesVideoDTO.class);
            method.setAccessible(true);
            method.invoke(service, request);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
