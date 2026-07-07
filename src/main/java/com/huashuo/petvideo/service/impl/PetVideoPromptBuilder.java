package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
public class PetVideoPromptBuilder {

    static final String PROMPT_VERSION = "pet-video-prompt-v2";
    static final String STYLE_VERSION = "pet-style-v1";

    private final ObjectMapper objectMapper;

    public PetVideoPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String build(JsonNode draft) {
        StringBuilder prompt = new StringBuilder(2200);
        appendSection(prompt, "System", "Create a polished short pet video for social media. Use the user's pet draft as the only business context.");
        appendSection(prompt, "Core idea", text(draft, "prompt"));
        appendSection(prompt, "Generation mode", text(draft, "generationMode", "reference_video"));
        appendSection(prompt, "Pet identity", petIdentity(draft));
        appendSection(prompt, "Reference material rules", materialRules(draft));
        appendSection(prompt, "Role consistency", roleConsistency(draft));
        appendSection(prompt, "Background and scene edit", backgroundScene(draft));
        appendSection(prompt, "Product and prop direction", productAndProp(draft));
        appendSection(prompt, "Story and dialogue", storyAndDialogue(draft));
        appendSection(prompt, "Storyboard shots", storyboard(draft));
        appendSection(prompt, "Style and camera", styleAndCamera(draft));
        appendSection(prompt, "Subtitle and text policy", subtitlePolicy(draft));
        appendSection(prompt, "Negative prompt", negativePrompt());
        appendSection(prompt, "Quality constraints", "Keep the main pet's appearance, fur color, fur pattern, face, body shape, and personality stable across all shots. Scene references are background only and must not override pet identity.");
        return limit(prompt.toString(), 2600);
    }

    public String promptVersion() {
        return PROMPT_VERSION;
    }

    public String styleVersion() {
        return STYLE_VERSION;
    }

    private String petIdentity(JsonNode draft) {
        ArrayNode roles = array(draft, "roles");
        if (roles.isEmpty()) {
            return "Primary pet identity is defined by the main pet reference image and user prompt.";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode role : roles) {
            String name = text(role, "name", "pet");
            builder.append(name)
                    .append(" is a ")
                    .append(text(role, "type", "pet"));
            appendIfText(builder, ", breed ", text(role, "breed"));
            appendIfText(builder, ", age feel ", text(role, "ageFeel"));
            appendIfText(builder, ", speaking tone ", text(role, "speakingTone"));
            if (!array(role, "personalityTags").isEmpty()) {
                builder.append(", personality ").append(array(role, "personalityTags").toString());
            }
            builder.append(". ");
        }
        return builder.toString();
    }

    private String materialRules(JsonNode draft) {
        int mainPet = 0;
        int secondPet = 0;
        int scene = 0;
        int prop = 0;
        for (JsonNode material : array(draft, "materials")) {
            String role = text(material, "role");
            if ("main_pet".equals(role)) mainPet++;
            if ("second_pet".equals(role)) secondPet++;
            if ("scene".equals(role)) scene++;
            if ("prop".equals(role)) prop++;
        }
        return "Use main_pet references as the primary identity anchor (" + mainPet + "). "
                + "Use second_pet references only for the second role (" + secondPet + "). "
                + "Use scene references as background context (" + scene + ") and prop references only as pet products or props (" + prop + "). "
                + "Do not merge two pets into one. Do not change breed, fur color, fur pattern, face shape, or body proportions.";
    }

    private String roleConsistency(JsonNode draft) {
        JsonNode consistency = draft == null ? null : draft.get("consistency");
        boolean keepAppearance = booleanValue(consistency, "keepAppearance", true);
        boolean keepFurPattern = booleanValue(consistency, "keepFurPattern", true);
        boolean keepScene = booleanValue(consistency, "keepScene", false);
        boolean allowAnthropomorphic = booleanValue(consistency, "allowAnthropomorphic", true);
        boolean multiShotPriority = booleanValue(consistency, "multiShotPriority", true);
        return "keepAppearance=" + keepAppearance
                + ", keepFurPattern=" + keepFurPattern
                + ", keepScene=" + keepScene
                + ", allowAnthropomorphic=" + allowAnthropomorphic
                + ", multiShotPriority=" + multiShotPriority
                + ". If anthropomorphic acting is used, preserve natural pet features and avoid human-like body replacement.";
    }

    private String backgroundScene(JsonNode draft) {
        JsonNode visual = draft == null ? null : draft.get("visualSettings");
        String backgroundPrompt = text(visual, "backgroundPrompt");
        if (!StringUtils.hasText(backgroundPrompt)) {
            return "Use scene references only as background context. Keep the pet as the primary subject.";
        }
        return backgroundPrompt + ". Apply this as background or scene direction only; do not replace, merge, recolor, or reshape the pet identity.";
    }

    private String productAndProp(JsonNode draft) {
        JsonNode visual = draft == null ? null : draft.get("visualSettings");
        String productPrompt = text(visual, "productPrompt");
        if (!StringUtils.hasText(productPrompt)) {
            return "If prop references are provided, show them as pet supplies, toys, snacks, grooming tools, or scene props only. Do not let props cover the pet's face or alter pet identity.";
        }
        return productPrompt + ". Apply this only to product or prop presentation; keep the pet as the primary subject and do not cover the face, fur pattern, or body shape.";
    }

    private String storyAndDialogue(JsonNode draft) {
        StringBuilder builder = new StringBuilder();
        appendIfText(builder, "Script: ", text(draft, "scriptText"));
        ArrayNode lines = array(draft, "dialogueLines");
        if (!lines.isEmpty()) {
            builder.append(" Dialogue beats: ");
            for (JsonNode line : lines) {
                String lineText = text(line, "text");
                if (StringUtils.hasText(lineText)) {
                    builder.append("[")
                            .append(text(line, "speakerRoleId", "pet"))
                            .append(" ")
                            .append(text(line, "emotion", "neutral"))
                            .append("] ")
                            .append(lineText)
                            .append(" ");
                }
            }
        }
        return builder.length() == 0 ? "No dialogue. Tell the story through pet actions and expressions." : builder.toString();
    }

    private String storyboard(JsonNode draft) {
        ArrayNode shots = array(draft, "shots");
        if (shots.isEmpty()) {
            return "Single cohesive short video based on the script and prompt.";
        }
        StringBuilder builder = new StringBuilder();
        for (JsonNode shot : shots) {
            builder.append("Shot ")
                    .append(shot.has("index") ? shot.get("index").asText() : "?")
                    .append(" (")
                    .append(shot.has("durationSeconds") ? shot.get("durationSeconds").asText() : "?")
                    .append("s): frame=")
                    .append(text(shot, "frameDescription"))
                    .append("; action=")
                    .append(text(shot, "characterAction"))
                    .append("; camera=")
                    .append(text(shot, "cameraMove"))
                    .append("; narration/subtitle intent=")
                    .append(text(shot, "subtitle"))
                    .append(". ");
        }
        return builder.toString();
    }

    private String styleAndCamera(JsonNode draft) {
        String style = text(draft, "style", "cute").toLowerCase(Locale.ROOT);
        String stylePrompt = switch (style) {
            case "realistic" -> "realistic pet photography, natural movement, soft home lighting";
            case "anime" -> "anime-inspired cute pet style, clean composition, expressive but stable characters";
            case "anthropomorphic" -> "light anthropomorphic acting, still visibly a real pet with original fur and face";
            case "funny" -> "funny short-video rhythm, expressive reactions, clear comedic timing";
            case "healing" -> "warm healing tone, soft light, gentle pacing";
            default -> "cute pet style, expressive face, bright clean composition";
        };
        JsonNode visual = draft == null ? null : draft.get("visualSettings");
        return stylePrompt + ". Aspect ratio " + text(draft, "aspectRatio", "9:16")
                + ", duration " + text(draft, "durationSeconds", "15")
                + " seconds, camera rhythm " + text(visual, "cameraRhythm", "balanced")
                + ", expression intensity " + text(visual, "expressionIntensity", "70") + ".";
    }

    private String subtitlePolicy(JsonNode draft) {
        if (booleanValue(draft, "subtitleEnabled", false)) {
            return "Reserve safe space for external Chinese subtitles, but do not render readable text, captions, watermarks, UI labels, or gibberish inside the video frames.";
        }
        return "Do not render any readable text, captions, watermarks, UI labels, or gibberish inside the video frames.";
    }

    public String negativePrompt() {
        return "No car sales script, no vehicle model, no dealership promotion, no test-drive wording, no range, price, discount, or promotion information. No text watermark, no gibberish subtitles, no random text. No fused pets, no breed drift, no fur color change, no face shape change, no extra animals suddenly appearing. No deformed limbs, no extra limbs, no twisted body, no scary expression, no flickering, no excessive motion blur, no low clarity.";
    }

    private void appendSection(StringBuilder builder, String label, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        builder.append(label).append(": ").append(value.trim()).append("\n");
    }

    private void appendIfText(StringBuilder builder, String prefix, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(prefix).append(value.trim());
        }
    }

    private ArrayNode array(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        return objectMapper.createArrayNode();
    }

    private String text(JsonNode node, String field) {
        return text(node, field, "");
    }

    private String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        String value = node.get(field).asText("");
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private boolean booleanValue(JsonNode node, String field, boolean fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        return node.get(field).asBoolean(fallback);
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }
}
