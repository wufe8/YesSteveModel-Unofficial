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
            String k = entry.getKey();
            String v = entry.getValue();
            // If value starts with #, it's a config button reference — prefix key with #
            // so the wheel UI displays it as a config entry and the inner-ring click
            // handler can find the btnId from the value.
            if (v != null && v.startsWith("#")) {
                entries.put("#" + k, v);
            } else {
                entries.put(k, v);
            }
        }

        for (RawYsmModel.ExtraAnimationClassify classify : raw.properties.extraAnimationClassifies) {
            if (StringUtils.isNotBlank(classify.id)) {
                classifies.put(classify.id, new LinkedHashMap<>(classify.extras));
                // Add classify as a navigable submenu entry if not already present
                String key = "#" + classify.id;
                if (!entries.containsKey(key)) {
                    entries.put(key, classify.id);
                }
            }
        }

        for (ExtraAnimationButton btn : raw.properties.extraAnimationButtons) {
            if (StringUtils.isNotBlank(btn.id)) {
                configButtons.put(btn.id, btn);
                // Auto-add to entries only if no entry already references this btn_id
                // (extraAnimations may already have added it with key="#extraN").
                String btnRef = "#" + btn.id;
                boolean alreadyReferenced = entries.values().stream().anyMatch(v -> btnRef.equals(v));
                if (!alreadyReferenced) {
                    entries.put(btnRef, btnRef);
                }
            }
        }

        return new ExtraWheelData(entries, classifies, configButtons);
    }
}
