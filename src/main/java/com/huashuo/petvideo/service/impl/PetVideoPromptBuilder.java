package com.huashuo.petvideo.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class PetVideoPromptBuilder {

    static final String PROMPT_VERSION = "pet-video-prompt-v4";
    static final String STYLE_VERSION = "pet-style-v1";

    private final ObjectMapper objectMapper;

    public PetVideoPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String build(JsonNode draft) {
        StringBuilder prompt = new StringBuilder(3600);
        appendSection(prompt, "System", "Create a polished short pet video for social media. Use the user's pet draft as the only business context.");
        appendSection(prompt, "Core idea", text(draft, "prompt"));
        appendSection(prompt, "Finished story requirement", finishedStoryRequirement(draft));
        appendSection(prompt, "Generation mode", text(draft, "generationMode", "reference_video"));
        appendSection(prompt, "Pet identity", petIdentity(draft));
        appendSection(prompt, "Human cast", humanCast(draft));
        appendSection(prompt, "Structured story", structuredStory(draft));
        appendSection(prompt, "Reference image manifest", referenceImageManifest(draft));
        appendSection(prompt, "Reference material rules", materialRules(draft));
        appendSection(prompt, "Role consistency", roleConsistency(draft));
        appendSection(prompt, "Background and scene edit", backgroundScene(draft));
        appendSection(prompt, "Product and prop direction", productAndProp(draft));
        appendSection(prompt, "Sticker overlay and output", stickerOverlayPolicy(draft));
        appendSection(prompt, "Negative prompt", negativePrompt(draft));
        appendSection(prompt, "Story and dialogue", storyAndDialogue(draft));
        appendSection(prompt, "Storyboard shots", storyboard(draft));
        appendSection(prompt, "Style and camera", styleAndCamera(draft));
        appendSection(prompt, "Subtitle and text policy", subtitlePolicy(draft));
        appendSection(prompt, "Quality constraints", "Keep the main pet's appearance, fur color, fur pattern, face, body shape, and personality stable across all shots. Scene references, props, and the second pet must never override the main pet identity. In multi-pet scenes, keep pets visually separate and never fuse their faces, coats, or bodies.");
        return limit(prompt.toString(), 5000);
    }

    public String promptVersion() {
        return PROMPT_VERSION;
    }

    public String styleVersion() {
        return STYLE_VERSION;
    }

    private String finishedStoryRequirement(JsonNode draft) {
        boolean hasHuman = !array(draft, "humanAssets").isEmpty() || hasMaterialRole(draft, "human_avatar");
        boolean hasDialogue = !array(draft, "dialogueLines").isEmpty();
        boolean hasMultiplePets = hasMaterialRole(draft, "second_pet") || array(draft, "roles").size() >= 2;
        StringBuilder builder = new StringBuilder();
        builder.append("Make the result feel like a finished story video, not a random pet motion test. ");
        builder.append("不要生成无剧情视频，不要生成无对白视频，不要生成随机宠物片段；必须有开端、轻冲突、可爱转折和温暖收尾。 ");
        builder.append("不要复用汽车创作中心模板内容，不要出现汽车展厅、车辆、销售顾问、试驾、价格促销或车型广告。 ");
        builder.append("Use a clear beginning, small conflict, cute turn, and warm ending. ");
        if (hasHuman) {
            builder.append("Show the human owner on screen as a natural supporting character, using the human avatar only for that person. ");
        }
        if (hasMultiplePets) {
            builder.append("Keep at least two pets visually separate and preserve each pet's identity. ");
        }
        if (hasDialogue) {
            builder.append("Treat dialogue lines as the story beats and leave safe space for external Chinese subtitles. ");
        }
        builder.append("Do not drift into sticker-only, car, product ad, or generic template content.");
        return builder.toString();
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

    private String humanCast(JsonNode draft) {
        ArrayNode humans = array(draft, "humanAssets");
        List<String> entries = new ArrayList<>();
        for (JsonNode human : humans) {
            String name = firstNonBlank(text(human, "roleName"), text(human, "displayName"), text(human, "name"));
            if (!StringUtils.hasText(name)) {
                name = "human owner";
            }
            StringBuilder item = new StringBuilder(name);
            appendIfText(item, " visual=", text(human, "visualDescription"));
            appendIfText(item, " voiceProfileId=", text(human, "voiceProfileId"));
            appendIfText(item, " subtitleStyleId=", text(human, "subtitleStyleId"));
            entries.add(item.toString());
        }
        if (entries.isEmpty() && hasMaterialRole(draft, "human_avatar")) {
            entries.add("Use the human_avatar reference as the pet owner only; keep them natural, warm, and secondary to the pet story.");
        }
        return entries.isEmpty() ? "" : String.join(" | ", entries);
    }

    private String structuredStory(JsonNode draft) {
        JsonNode story = draft == null ? null : draft.get("story");
        StringBuilder builder = new StringBuilder();
        appendIfText(builder, "Title: ", firstNonBlank(text(story, "title"), text(draft, "title"), text(draft, "templateName")));
        appendIfText(builder, " Summary: ", text(story, "summary"));
        appendIfText(builder, " Conflict: ", text(story, "conflict"));
        appendIfText(builder, " Twist: ", text(story, "twist"));
        appendIfText(builder, " Ending: ", text(story, "ending"));
        appendIfText(builder, " Scene: ", text(story, "scene"));
        appendIfText(builder, " Style: ", text(story, "style"));
        appendCompactJson(builder, "Characters", draft == null ? null : draft.get("characters"), 700);
        appendCompactJson(builder, "Subtitle config", draft == null ? null : draft.get("subtitleConfig"), 360);
        appendCompactJson(builder, "Audio config", draft == null ? null : draft.get("audioConfig"), 360);
        appendCompactJson(builder, "Asset sections", draft == null ? null : draft.get("assetSections"), 420);
        return builder.length() == 0 ? "" : builder.toString();
    }

    private boolean hasMaterialRole(JsonNode draft, String role) {
        for (JsonNode material : array(draft, "materials")) {
            if (role.equals(text(material, "role"))) {
                return true;
            }
        }
        return false;
    }

    private String materialRules(JsonNode draft) {
        int mainPet = 0;
        int secondPet = 0;
        int humanAvatar = 0;
        int scene = 0;
        int prop = 0;
        for (JsonNode material : array(draft, "materials")) {
            String role = text(material, "role");
            if ("main_pet".equals(role)) mainPet++;
            if ("second_pet".equals(role)) secondPet++;
            if ("human_avatar".equals(role)) humanAvatar++;
            if ("scene".equals(role)) scene++;
            if ("prop".equals(role)) prop++;
        }
        return "Reference image order matters and follows the manifest above. "
                + "Use main_pet references as the highest-priority identity anchor (" + mainPet + "); if any text conflicts with the main_pet image, the image wins. "
                + "Preserve the exact individual pet: face markings, eye color, nose bridge, muzzle, ear shape, fur edge, coat pattern, body proportions, and visible age feel. "
                + "Use second_pet references only for the second role (" + secondPet + "). "
                + "Use human_avatar references only for the owner/human character (" + humanAvatar + "), never as pet identity. "
                + "Use scene references as background context (" + scene + ") and prop references only as pet products or props (" + prop + "). "
                + "Do not merge two pets into one, replace the main pet with the scene/prop image, or change breed, fur color, fur pattern, face shape, or body proportions.";
    }

    private String referenceImageManifest(JsonNode draft) {
        List<String> entries = new ArrayList<>();
        addReferenceEntries(draft, entries, "main_pet", 3,
                "highest-priority main pet identity anchor; copy exact face markings, eye color, nose/muzzle, ears, coat pattern, and body shape");
        if ("dialogue".equals(text(draft, "videoType")) || PetCreationDraftValidator.MODE_DIALOGUE_VIDEO.equals(text(draft, "generationMode"))) {
            addReferenceEntries(draft, entries, "second_pet", 3,
                    "second pet identity anchor only; keep it separate from the main pet");
        }
        addReferenceEntries(draft, entries, "human_avatar", 2,
                "human owner reference only; keep the person natural and do not transform the person into a pet");
        addReferenceEntries(draft, entries, "scene", 2,
                "background/scene reference only; do not use it to create or replace pets");
        addReferenceEntries(draft, entries, "prop", 2,
                "pet prop/product reference only; do not cover or alter pet identity");
        if (entries.isEmpty()) {
            return "No reference images are attached; generate from text only and avoid inventing extra pets.";
        }
        return String.join(" ", entries);
    }

    private void addReferenceEntries(JsonNode draft, List<String> entries, String role, int limit, String purpose) {
        int added = 0;
        for (JsonNode material : array(draft, "materials")) {
            if (entries.size() >= 6 || added >= limit || !role.equals(text(material, "role"))) {
                continue;
            }
            if (!StringUtils.hasText(text(material, "url"))) {
                continue;
            }
            entries.add("Image " + (entries.size() + 1)
                    + " = " + role
                    + labelSuffix(material)
                    + ". Purpose: " + purpose + ".");
            added++;
        }
    }

    private String labelSuffix(JsonNode material) {
        String label = text(material, "label");
        if (StringUtils.hasText(label)) {
            return " (" + label + ")";
        }
        String assetId = text(material, "assetId");
        if (StringUtils.hasText(assetId)) {
            return " (asset " + assetId + ")";
        }
        return "";
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
        String baseStylePrompt = switch (style) {
            case "realistic" -> "realistic pet photography, natural movement, soft home lighting";
            case "anime" -> "anime-inspired cute pet style, clean composition, expressive but stable characters";
            case "anthropomorphic" -> "light anthropomorphic acting, still visibly a real pet with original fur and face";
            case "funny" -> "funny short-video rhythm, expressive reactions, clear comedic timing";
            case "healing" -> "warm healing tone, soft light, gentle pacing";
            default -> "cute pet style, expressive face, bright clean composition";
        };
        JsonNode visual = draft == null ? null : draft.get("visualSettings");
        String customStylePrompt = text(visual, "stylePrompt");
        return baseStylePrompt
                + (StringUtils.hasText(customStylePrompt) ? ". User editable style direction: " + limit(customStylePrompt, 160) : "")
                + ". Aspect ratio " + text(draft, "aspectRatio", "9:16")
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

    private String stickerOverlayPolicy(JsonNode draft) {
        JsonNode visual = draft == null ? null : draft.get("visualSettings");
        JsonNode overlay = visual == null ? null : visual.get("stickerOverlay");
        boolean sticker = "sticker".equals(text(draft, "videoType")) || overlay != null;
        if (!sticker) {
            return "";
        }
        JsonNode subtitleStyle = draft == null ? null : draft.get("subtitleStyle");
        String staticFormat = text(overlay, "staticFormat", "png");
        String dynamicFormat = text(overlay, "dynamicFormat", "gif");
        String outputType = PetCreationDraftValidator.MODE_IMAGE_TO_VIDEO.equals(text(draft, "generationMode"))
                || "reference_video".equals(text(draft, "generationMode"))
                ? dynamicFormat
                : staticFormat;
        String text = text(overlay, "text");
        String icon = text(overlay, "icon", "none");
        StringBuilder builder = new StringBuilder();
        builder.append("Output type=").append(outputType)
                .append("; staticFormat=").append(staticFormat)
                .append("; dynamicFormat=").append(dynamicFormat)
                .append("; overlay text=\"").append(text).append("\"")
                .append("; font=").append(text(subtitleStyle, "fontFamily", "Microsoft YaHei"))
                .append("; fontSize=").append(text(subtitleStyle, "fontSize", "30"))
                .append("; textColor=").append(text(subtitleStyle, "textColor", "#2563eb"))
                .append("; outlineColor=").append(text(subtitleStyle, "outlineColor", "#ffffff"))
                .append("; strokeStyle=").append(text(subtitleStyle, "strokeMode", "strong"))
                .append("; textPosition x=").append(text(overlay, "textX", "50"))
                .append(", y=").append(text(overlay, "textY", "82"))
                .append("; icon=").append(icon)
                .append("; iconPosition x=").append(text(overlay, "iconX", "82"))
                .append(", y=").append(text(overlay, "iconY", "22"))
                .append(". Keep overlay inside the frame and away from the pet face. If direct video text rendering is unstable, reserve safe space for a post-production overlay and do not generate random text.");
        return builder.toString();
    }

    public String negativePrompt() {
        return "No car sales script, no vehicle model, no dealership promotion, no test-drive wording, no range, price, discount, or promotion information. No text watermark, no gibberish subtitles, no random text. No fused pets, no breed drift, no fur color change, no face shape change, no extra animals suddenly appearing. No deformed limbs, no extra limbs, no twisted body, no scary expression, no flickering, no excessive motion blur, no low clarity.";
    }

    private String negativePrompt(JsonNode draft) {
        StringBuilder builder = new StringBuilder(negativePrompt());
        JsonNode custom = draft == null ? null : draft.get("negativePrompt");
        if (custom != null && custom.isArray()) {
            for (JsonNode item : custom) {
                String value = item.asText("");
                if (StringUtils.hasText(value)) {
                    builder.append(" ").append(value.trim());
                }
            }
        } else {
            String value = text(draft, "negativePrompt");
            if (StringUtils.hasText(value)) {
                builder.append(" ").append(value);
            }
        }
        return builder.toString();
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

    private void appendCompactJson(StringBuilder builder, String label, JsonNode node, int maxLength) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        String raw = limit(node.toString(), maxLength);
        if (StringUtils.hasText(raw) && !"[]".equals(raw) && !"{}".equals(raw)) {
            builder.append(" ").append(label).append(": ").append(raw);
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
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
