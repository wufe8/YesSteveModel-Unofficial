package com.fox.ysmu.client;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;
import com.fox.ysmu.model.resource.pojo.RawYsmModel.ExtraAnimationButton;

/**
 * Extra animation wheel data parsed from ysm.json.
 * Supports YSM 2.3.0+ features: unlimited slot count, sub-pages via # prefix,
 * return button via #return, and config buttons via #buttonId.
 */
public class ExtraWheelData {

    /** Main wheel entries: key → display label. Keys starting with # are sub-page references. */
    public final Map<String, String> entries;
    /** Sub-pages: classify id → (key → display label). */
    public final Map<String, Map<String, String>> classifies;
    /** Config buttons: button id → button definition. */
    public final Map<String, ExtraAnimationButton> configButtons;

    public ExtraWheelData(Map<String, String> entries, Map<String, Map<String, String>> classifies,
        Map<String, ExtraAnimationButton> configButtons) {
        this.entries = entries;
        this.classifies = classifies;
        this.configButtons = configButtons;
    }

    public static ExtraWheelData from(RawYsmModel raw) {
        Map<String, String> entries = new LinkedHashMap<>();
        Map<String, Map<String, String>> classifies = new LinkedHashMap<>();
        Map<String, ExtraAnimationButton> configButtons = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : raw.properties.extraAnimations.entrySet()) {
            entries.put(entry.getKey(), entry.getValue());
        }

        for (RawYsmModel.ExtraAnimationClassify classify : raw.properties.extraAnimationClassifies) {
            if (StringUtils.isNotBlank(classify.id)) {
                classifies.put(classify.id, new LinkedHashMap<>(classify.extras));
            }
        }

        for (ExtraAnimationButton btn : raw.properties.extraAnimationButtons) {
            if (StringUtils.isNotBlank(btn.id)) {
                configButtons.put(btn.id, btn);
                // Auto-add to entries so it appears in the wheel.
                // OpenYSM convention: value (display label) starts with # for config buttons.
                String key = "#" + btn.id;
                if (!entries.containsKey(key)) {
                    String label = StringUtils.isNotBlank(btn.name) ? btn.name : btn.id;
                    entries.put(key, "#" + label);
                }
            }
        }

        return new ExtraWheelData(entries, classifies, configButtons);
    }
}
