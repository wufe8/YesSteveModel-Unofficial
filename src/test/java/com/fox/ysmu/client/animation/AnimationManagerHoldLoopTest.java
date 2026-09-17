package com.fox.ysmu.client.animation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;

import net.minecraft.util.ResourceLocation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.util.json.JsonAnimationUtils;

/**
 * 持握动画（{@code hold_mainhand:*} / {@code hold_offhand:*}）必须"播一次然后停在最后一帧"。
 *
 * <p>YSM-wiki: 动画制作/手部条件动画「持有动画」——持有动画在玩家切换物品时从头播放一次来做
 * 切枪动作，所以循环类型一定是 hold-on-last-frame。模型作者写得很随意：res/ 里
 * {@code hold_offhand:axe} 有 6 个模型写 {@code "loop": true}，其余写 {@code "loop":
 * "hold_on_last_frame"}，两种都当持握动画用。所以播放前必须统一覆盖。</p>
 *
 * <p>回归点：覆盖只做在主手分支时，副手持斧头会无限重复掏出动作（永远停不下来）。
 * 主手与副手现在共用 {@link AnimationManager#prepareHoldAnimation}，这个测试锁住它。</p>
 */
class AnimationManagerHoldLoopTest {

    private static final ResourceLocation ID = new ResourceLocation("ysmu", "_test_hold_loop");

    @AfterEach
    void tearDown() {
        GeckoLibCache.getInstance().getAnimations().remove(ID);
    }

    private static void register(String json) {
        AnimationFile file = new AnimationFile();
        JsonElement animations = new JsonParser().parse(json).getAsJsonObject().get("animations");
        MolangParser parser = new MolangParser();
        for (Map.Entry<String, JsonElement> entry : animations.getAsJsonObject().entrySet()) {
            try {
                Animation animation = JsonAnimationUtils.deserializeJsonToAnimation(entry, parser);
                file.animations.put(entry.getKey(), animation);
            } catch (Exception e) {
                throw new IllegalStateException("fixture parse failed for " + entry.getKey(), e);
            }
        }
        GeckoLibCache.getInstance().getAnimations().put(ID, file);
    }

    private static ILoopType loopOf(String name) {
        return GeckoLibCache.getInstance().getAnimations().get(ID).getAnimation(name).loop;
    }

    @Test
    void offhandHoldIsForcedToHoldOnLastFrame() {
        // 模型把副手持斧写成循环 —— 真实模型里的主流写法。
        register("{\"animations\":{\"hold_offhand:axe\":{\"loop\":true,\"animation_length\":1.0,"
            + "\"bones\":{\"RightArm\":{\"rotation\":{\"0.0\":0.0,\"1.0\":45.0}}}}}}");
        String resolved = AnimationManager.prepareHoldAnimation(ID, "hold_offhand:axe");
        assertEquals("hold_offhand:axe", resolved, "helper 必须原样返回动画名供 playIfPresent 使用");
        assertSame(ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME, loopOf("hold_offhand:axe"));
    }

    @Test
    void mainhandHoldIsForcedTheSameWay() {
        register("{\"animations\":{\"hold_mainhand:axe\":{\"loop\":true,\"animation_length\":1.0,"
            + "\"bones\":{\"RightArm\":{\"rotation\":{\"0.0\":0.0,\"1.0\":45.0}}}}}}");
        AnimationManager.prepareHoldAnimation(ID, "hold_mainhand:axe");
        assertSame(ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME, loopOf("hold_mainhand:axe"));
    }

    /** 没有 loop 字段的动画默认是 PLAY_ONCE，同样要被覆盖成停在最后一帧。 */
    @Test
    void holdWithoutExplicitLoopIsAlsoForced() {
        register("{\"animations\":{\"hold_offhand:pickaxe\":{\"animation_length\":1.0,"
            + "\"bones\":{\"RightArm\":{\"rotation\":{\"0.0\":0.0,\"1.0\":10.0}}}}}}");
        assertSame(ILoopType.EDefaultLoopTypes.PLAY_ONCE, loopOf("hold_offhand:pickaxe"));
        AnimationManager.prepareHoldAnimation(ID, "hold_offhand:pickaxe");
        assertSame(ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME, loopOf("hold_offhand:pickaxe"));
    }

    /** 同一个文件里的非持握动画（例如 idle）不能被顺手改掉。 */
    @Test
    void otherAnimationsAreUntouched() {
        register("{\"animations\":{"
            + "\"idle\":{\"loop\":true,\"animation_length\":1.0,"
            + "\"bones\":{\"Head\":{\"rotation\":{\"0.0\":0.0,\"1.0\":5.0}}}},"
            + "\"hold_offhand:axe\":{\"loop\":true,\"animation_length\":1.0,"
            + "\"bones\":{\"RightArm\":{\"rotation\":{\"0.0\":0.0,\"1.0\":45.0}}}}}}");
        AnimationManager.prepareHoldAnimation(ID, "hold_offhand:axe");
        assertSame(ILoopType.EDefaultLoopTypes.HOLD_ON_LAST_FRAME, loopOf("hold_offhand:axe"));
        assertSame(ILoopType.EDefaultLoopTypes.LOOP, loopOf("idle"));
    }

    /** 名字或模型缺失时不能抛异常，且必须原样返回动画名（控制器的 hold 条目可能不存在）。 */
    @Test
    void missingInputsAreTolerated() {
        assertEquals("hold_offhand:axe", AnimationManager.prepareHoldAnimation(null, "hold_offhand:axe"));
        assertEquals("hold_offhand:axe", AnimationManager.prepareHoldAnimation(ID, "hold_offhand:axe"));
        assertEquals("", AnimationManager.prepareHoldAnimation(ID, ""));
        assertNull(AnimationManager.prepareHoldAnimation(ID, null));
    }
}
