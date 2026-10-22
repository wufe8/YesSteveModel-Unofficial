package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.animation.AnimationRegister;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.keyframe.BoneAnimation;
import software.bernie.geckolib3.core.keyframe.VectorKeyFrameList;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.util.json.JsonAnimationUtils;

class WikiQueryKeyframeTest {

    private MolangParser parser;

    @BeforeEach
    void setup() {
        MolangParser.VARIABLES.clear();
        AnimationRegister.registerMolangHooks();
        AnimationRegister.registerVariables();
        parser = new MolangParser();
    }

    @AfterEach
    void cleanup() {
        MolangParser.VARIABLES.clear();
        QueryBiomeTagFunction.clearReportedTags();
    }

    @Test
    void allSevenFunctionNamesRetainRealDeserializedBoneChannels() throws Exception {
        String[] expressions = {
            "query.rotation_to_camera(0)",
            "q.rotation_to_camera(1)",
            "query.relative_block_has_all_tags(0,-0.5,0,'minecraft:replaceable')",
            "query.biome_has_all_tags('minecraft:is_overworld','minecraft:is_forest')",
            "q.biome_has_any_tag('minecraft:is_forest','minecraft:is_ocean')",
            "ysm.relative_block_name_any(0,-1,0,'minecraft:air','minecraft:stone')",
            "ysm.has_any_curios_with_all_tags('ring','minecraft:swords','minecraft:axes')",
            "ysm.has_any_curios_with_any_tag('necklace','minecraft:swords','forge:ingots/iron')"
        };
        for (String expression : expressions) {
            assertDoesNotThrow(() -> parser.parseExpression(expression), expression);
            Animation animation = deserialize(expression);
            BoneAnimation bone = animation.boneAnimations.get(0);
            assertRetained(bone.scaleKeyFrames, expression + " scale");
            assertRetained(bone.positionKeyFrames, expression + " position");
            assertRetained(bone.rotationKeyFrames, expression + " rotation");
        }
    }

    @Test
    void unknownFunctionControlActuallyClearsTheFailingChannels() throws Exception {
        BoneAnimation bone = deserialize("ysm.n1_unregistered(1)").boneAnimations.get(0);
        assertTrue(bone.scaleKeyFrames.xKeyFrames.isEmpty());
        assertTrue(bone.positionKeyFrames.xKeyFrames.isEmpty());
        assertTrue(bone.rotationKeyFrames.xKeyFrames.isEmpty());
    }

    @Test
    void functionsEnforceTheirMinimumDocumentedArguments() {
        for (String invalid : new String[] {
            "query.rotation_to_camera()", "query.relative_block_has_all_tags(0,0,0)",
            "query.biome_has_all_tags()", "query.biome_has_any_tag()",
            "ysm.relative_block_name_any(0,0,0)", "ysm.has_any_curios_with_all_tags('ring')",
            "ysm.has_any_curios_with_any_tag('ring')"
        }) assertThrows(Exception.class, () -> parser.parseExpression(invalid), invalid);
    }

    @Test
    void bareDumpCuriosIsRegisteredAndItsSupplierReallyExecutes() throws Exception {
        assertNotNull(MolangParser.VARIABLES.get("ysm.dump_curios"));
        assertRetained(deserialize("ysm.dump_curios").boneAnimations.get(0).scaleKeyFrames, "bare dump");
        AtomicInteger calls = new AtomicInteger();
        YsmDebugDumpFunction.registerCuriosVariable(parser, calls::incrementAndGet);
        com.eliotlash.mclib.math.IValue value = parser.parseExpression("ysm.dump_curios");
        int before = calls.get();
        assertEquals(0.0d, value.get());
        assertEquals(before + 1, calls.get(), "bare variable reads must execute the debug supplier");
        assertThrows(Exception.class, () -> parser.parseExpression("ysm.dump_curios()"),
            "function registration must not stand in for the documented bare variable");
    }

    @Test
    void cameraAxisMappingIsPitchThenYawAndInvalidAxesAreZero() {
        assertEquals(-25.0d, QueryRotationToCameraFunction.component(0, -25, 70, false));
        assertEquals(70.0d, QueryRotationToCameraFunction.component(1, -25, 70, false));
        assertEquals(25.0d, QueryRotationToCameraFunction.component(0, -25, 70, true));
        assertEquals(250.0d, QueryRotationToCameraFunction.component(1, -25, 70, true));
        assertEquals(0.0d, QueryRotationToCameraFunction.component(2, -25, 70, false));
        assertEquals(0.0d, QueryRotationToCameraFunction.component(0.5, -25, 70, false));
    }

    @Test
    void allBlockTagsUseConjunctionAndUnsupportedTagsDoNotMasqueradeAsSupported() {
        Block air = new Block(Material.air) {};
        assertTrue(QueryBlockTagFunction.matchesTags(air, null, 0, 0, 0,
            Arrays.asList("minecraft:replaceable", "replaceable"), true));
        assertFalse(QueryBlockTagFunction.matchesTags(air, null, 0, 0, 0,
            Arrays.asList("minecraft:replaceable", "minecraft:unmapped"), true));
        assertTrue(QueryBlockTagFunction.matchesTags(air, null, 0, 0, 0,
            Arrays.asList("minecraft:replaceable", "minecraft:unmapped"), false));
        assertFalse(QueryBlockTagFunction.matchesTags(air, null, 0, 0, 0,
            Collections.singletonList("other_mod:replaceable"), true));
        assertTrue(QueryBlockTagFunction.matchesTags(air, null, 0, 0, 0,
            Collections.singletonList("other_mod:replaceable"), false), "existing any-tag namespace aliases stay unchanged");
        assertFalse(QueryBlockTagFunction.matchesTags(air, null, 0, 0, 0, Collections.emptyList(), true));
    }

    @Test
    void existingAnyTagPathDoesNotEvaluateTagsAfterItsFirstMatch() {
        java.util.List<String> lazyTags = new java.util.AbstractList<String>() {
            @Override
            public String get(int index) {
                if (index == 0) return "minecraft:replaceable";
                throw new AssertionError("legacy any-tag query must short-circuit");
            }

            @Override
            public int size() { return 2; }
        };
        assertTrue(QueryBlockTagFunction.matchesTags(new Block(Material.air) {}, null, 0, 0, 0, lazyTags, false));
    }

    @Test
    void relativeBlockIdsStayNamespaceSpecificAndOffsetsAreBounded() {
        assertTrue(RelativeBlockNameAnyFunction.matchesName("minecraft:stone", Arrays.asList("minecraft:air", "minecraft:stone")));
        assertFalse(RelativeBlockNameAnyFunction.matchesName("mod_a:stone", Collections.singletonList("mod_b:stone")));
        assertTrue(RelativeBlockNameFunction.validOffsets(8, -8, 0, 8));
        assertFalse(RelativeBlockNameFunction.validOffsets(8.01, 0, 0, 8));
        assertFalse(RelativeBlockNameFunction.validOffsets(Double.NaN, 0, 0, 8));
        assertFalse(RelativeBlockNameFunction.validOffsets(0, Double.POSITIVE_INFINITY, 0, 8));
    }

    @Test
    void biomeFallbackIsFalseAndWarningsAreDeduplicatedPerFullTagEvenWithoutDebug() {
        boolean previous = Config.DEBUG_CONTROLLER;
        try {
            Config.DEBUG_CONTROLLER = false;
            assertFalse(QueryBiomeTagFunction.matchesTags(Arrays.asList("namespace_a:forest", "namespace_b:forest")));
            assertEquals(2, QueryBiomeTagFunction.reportedTagCount());
            assertFalse(QueryBiomeTagFunction.matchesTags(Collections.singletonList("namespace_a:forest")));
            assertEquals(2, QueryBiomeTagFunction.reportedTagCount());
        } finally {
            Config.DEBUG_CONTROLLER = previous;
        }
    }

    private Animation deserialize(String expression) throws Exception {
        JsonObject vector = new JsonObject();
        for (String channel : new String[] { "scale", "position", "rotation" }) {
            com.google.gson.JsonArray values = new com.google.gson.JsonArray();
            values.add(new com.google.gson.JsonPrimitive(expression));
            values.add(new com.google.gson.JsonPrimitive(1));
            values.add(new com.google.gson.JsonPrimitive(1));
            vector.add(channel, values);
        }
        JsonObject bones = new JsonObject();
        bones.add("root", vector);
        JsonObject body = new JsonObject();
        body.addProperty("animation_length", 1);
        body.add("bones", bones);
        return JsonAnimationUtils.deserializeJsonToAnimation(
            new java.util.AbstractMap.SimpleImmutableEntry<String, JsonElement>("wiki_query", body), parser);
    }

    private static void assertRetained(VectorKeyFrameList<?> frames, String label) {
        assertFalse(frames.xKeyFrames.isEmpty(), label + " x must survive real deserialization");
        assertFalse(frames.yKeyFrames.isEmpty(), label + " y must survive real deserialization");
        assertFalse(frames.zKeyFrames.isEmpty(), label + " z must survive real deserialization");
    }
}
