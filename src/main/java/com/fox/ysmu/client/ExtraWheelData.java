package com.fox.ysmu.client;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.model.resource.pojo.RawYsmModel;

/**
 * Extra animation wheel data parsed from ysm.json.
 * Supports YSM 2.3.0+ features: unlimited slot count, sub-pages via # prefix,
 * and return button via #return.
 */
public class ExtraWheelData {

    /** Main wheel entries: key → display label. Keys starting with # are sub-page references. */
    public final Map<String, String> entries;
    /** Sub-pages: classify id → (key → display label). */
    public final Map<String, Map<String, String>> classifies;

    public ExtraWheelData(Map<String, String> entries, Map<String, Map<String, String>> classifies) {
        this.entries = entries;
        this.classifies = classifies;
    }

    public static ExtraWheelData from(RawYsmModel raw) {
        Map<String, String> entries = new LinkedHashMap<>();
        Map<String, Map<String, String>> classifies = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : raw.properties.extraAnimations.entrySet()) {
            entries.put(entry.getKey(), entry.getValue());
        }

        for (RawYsmModel.ExtraAnimationClassify classify : raw.properties.extraAnimationClassifies) {
            if (StringUtils.isNotBlank(classify.id)) {
                classifies.put(classify.id, new LinkedHashMap<>(classify.extras));
            }
        }

        return new ExtraWheelData(entries, classifies);
    }
}
