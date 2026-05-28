package com.huashuo.task.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huashuo.task.enums.TaskStatusCode;
import com.huashuo.task.enums.TaskTypeCode;
import com.huashuo.task.service.TaskService;
import com.huashuo.task.vo.TaskItem;
import com.huashuo.video.DTO.CarSalesVideoDTO;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "huashuo.ai-task.listener.enabled=false",
        "huashuo.ai-task.publisher.enabled=false",
        "huashuo.ai-task.backlog-dispatch.enabled=false",
        "huashuo.asset.generated-result-backfill.enabled=false",
        "huashuo.seed.initializer.enabled=false",
        "huashuo.bootstrap.database-compatibility.enabled=false",
        "huashuo.datasource.warmup.required=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.direct.auto-startup=false",
        "spring.sql.init.mode=never",
        "volcengine.seedance.poll-timeout-seconds=600"
})
@EnabledIfSystemProperty(named = "huashuo.smoke.car-sales.enabled", matches = "true")
class ControlledCarSalesSmokeTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private AiTaskConsumer aiTaskConsumer;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void carSalesTaskRunsThroughConsumerGuardAndSeedanceOnce() throws Exception {
        String traceId = "car-sales-smoke-" + UUID.randomUUID();
        TaskItem task = taskService.createTask(
                null,
                TaskTypeCode.SEEDANCE_CAR_SALES_VIDEO,
                objectMapper.writeValueAsString(smokeRequest()),
                traceId,
                null,
                null,
                0L,
                "TEST:" + traceId
        );

        Channel channel = mock(Channel.class);
        aiTaskConsumer.consumeMessage(
                new AiTaskMessage(task.taskId(), task.taskType(), task.ownerUserId(), task.traceId()),
                channel,
                1L
        );

        verify(channel).basicAck(1L, false);
        TaskItem completed = taskService.getTask(task.taskId());
        assertThat(completed.status())
                .withFailMessage("taskId=%s status=%s error=%s",
                        completed.taskId(), completed.status(), completed.errorMessage())
                .isEqualTo(TaskStatusCode.SUCCESS);
        assertThat(completed.progress()).isEqualTo(100);
        assertThat(completed.outputJson()).contains("videoUrl");
    }

    private CarSalesVideoDTO smokeRequest() {
        String imageUrl = smokeImageUrl();
        CarSalesVideoDTO request = new CarSalesVideoDTO();
        request.setCarImageUrls(List.of(imageUrl));
        request.setBrandModel("测试车型");
        request.setSellingPoints("外观稳重、空间舒适、适合家庭通勤");
        request.setAudience("城市家庭用户");
        request.setCallToAction("欢迎预约到店试驾");
        request.setPrompt("真实汽车广告质感，镜头简洁，避免文字水印");
        request.setAudioMode("none");
        request.setVoicePolicy("none");
        request.setSegmentCount(1);
        request.setSegmentDuration(4);
        request.setHostAppearanceEnabled(false);

        CarSalesVideoDTO.Scene scene = new CarSalesVideoDTO.Scene();
        scene.setSegmentIndex(1);
        scene.setTitle("外观开场");
        scene.setVisualPrompt("展示车辆正面与车身线条，镜头缓慢推进，突出真实展厅汽车广告质感。");
        scene.setPrompt(scene.getVisualPrompt());
        scene.setImageUrls(List.of(imageUrl));
        scene.setDuration(4);
        request.setScenes(List.of(scene));

        CarSalesVideoDTO.AssetRoleBinding binding = new CarSalesVideoDTO.AssetRoleBinding();
        binding.setUrl(imageUrl);
        binding.setAssetType("IMAGE");
        binding.setAssetRole("car_exterior_front");
        binding.setLabel("正面");
        request.setAssetRoleBindings(List.of(binding));
        return request;
    }

    private String smokeImageUrl() {
        String value = System.getProperty("huashuo.smoke.car-sales.image-url");
        if (value == null || value.isBlank()) {
            value = System.getenv("HUASHUO_SMOKE_CAR_SALES_IMAGE_URL");
        }
        assertThat(value)
                .as("Set -Dhuashuo.smoke.car-sales.image-url or HUASHUO_SMOKE_CAR_SALES_IMAGE_URL to a Seedance-accessible TOS/public image URL")
                .isNotBlank();
        return value.trim();
    }
}
