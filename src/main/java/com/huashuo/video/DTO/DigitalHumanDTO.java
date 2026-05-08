package com.huashuo.video.DTO;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.util.StringUtils;

@Data
public class DigitalHumanDTO {

    private Long projectId;

    private Long ownerUserId;

    @Size(max = 2048)
    private String imageUrl;

    @Size(max = 2048)
    private String audioUrl;

    @Size(max = 2000)
    private String text;

    @Size(max = 120)
    private String voiceId;

    @Size(max = 2000)
    private String prompt;

    @Pattern(regexp = "^(540p|720p|1080p)$", message = "resolution must be 540p, 720p, or 1080p")
    private String resolution;

    private String model;

    @AssertTrue(message = "imageUrl is required")
    public boolean hasImageUrl() {
        return StringUtils.hasText(imageUrl);
    }

    @AssertTrue(message = "audioUrl or text is required")
    public boolean hasAudioOrText() {
        return StringUtils.hasText(audioUrl) || StringUtils.hasText(text);
    }
}
