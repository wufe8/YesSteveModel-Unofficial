package com.fox.ysmu.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.keyframe.BoneAnimation;
import software.bernie.geckolib3.file.AnimationFile;

/**
 * 玩家的合并动画文件只能由玩家的动画文件构成。
 *
 * <p>回归的是一个"看名字完全无关、但会把形态开关整条吃掉"的覆盖：弹射物的动画文件用自己的
 * GeoModel id 注册，却和玩家共用同一套动画名。箭矢的 {@code arrow.animation.json} 就声明了
 * {@code parallel0} / {@code parallel1}（弓与箭自己的部件），而玩家的 {@code parallel1} 是
 * "人形 / 兽形"两根根骨的缩放所在（{@code AllBody.scale=1-v.roaming.a} /
 * {@code FOX.scale=v.roaming.a}）。</p>
 *
 * <p>首次同步走 {@link ClientModelManager#parseAnimationsToBundle} 时弹射物 key 会被单独
 * 注册并 {@code continue}；懒加载重载走
 * {@link ClientModelManager#parseAnimationFromCache} 时曾经没有这条过滤，于是重载出来的
 * 玩家动画被弹射物文件覆盖 —— 表现为"切到该模型（或删缓存后重新同步）后两种形态同时显示"，
 * 而 {@code /ysm reload} 又把当前模型换成 eager 解析、看起来"恢复了"。两条路径现在共用
 * {@link ClientModelManager#isPlayerAnimationSourceKey}。</p>
 */
class PlayerAnimationMergeFilterTest {

    @Test
    void projectileKeysAreNotPlayerAnimationSources() {
        assertFalse(ClientModelManager.isPlayerAnimationSourceKey("projectile_minecraft:arrow"),
            "弹射物动画不能并进玩家文件（名字会和玩家并行槽位撞车）");
        assertFalse(ClientModelManager.isPlayerAnimationSourceKey("projectile_ctrl_minecraft:arrow"));
        assertFalse(ClientModelManager.isPlayerAnimationSourceKey(null));
        assertFalse(ClientModelManager.isPlayerAnimationSourceKey(""));
    }

    @Test
    void playerAnimationSourcesStillMerge() {
        for (String key : new String[] { "main", "arm", "extra", "tac", "carryon", "tlm", "fp_arm",
            "__ysm_controller__model.animation_controllers", "slashblade", "parcool" }) {
            assertTrue(ClientModelManager.isPlayerAnimationSourceKey(key), key + " 应当并进玩家文件");
        }
    }

    @Test
    void mergingAProjectileFileWouldClobberTheFormSwitchAnimation() {
        // 玩家自己的 parallel1：包括两根形态根骨。
        AnimationFile player = file(anim("parallel1", "AllBody", "FOX", "MRoot"));
        assertTrue(bones(player, "parallel1").contains("AllBody"));

        // 弹射物的 parallel1 只有它自己的部件，而且是非空 —— 现有合并规则是"incoming 非空就覆盖"，
        // 所以一旦并进来，玩家的形态根骨就整条消失。这条断言钉住"为什么必须有 key 过滤"。
        AnimationFile projectile = file(anim("parallel1", "Board"));
        ClientModelManager.mergeAnimationFile(player, projectile);

        List<String> merged = bones(player, "parallel1");
        assertFalse(merged.contains("AllBody"), "被弹射物覆盖后 AllBody 的缩放写入丢失");
        assertFalse(merged.contains("FOX"), "被弹射物覆盖后 FOX 的缩放写入丢失");
    }

    private static AnimationFile file(Animation animation) {
        AnimationFile file = new AnimationFile();
        file.putAnimation(animation.animationName, animation);
        return file;
    }

    private static Animation anim(String name, String... boneNames) {
        Animation animation = new Animation();
        animation.animationName = name;
        animation.boneAnimations = new ArrayList<>();
        for (String boneName : boneNames) {
            BoneAnimation bone = new BoneAnimation();
            bone.boneName = boneName;
            animation.boneAnimations.add(bone);
        }
        return animation;
    }

    private static List<String> bones(AnimationFile file, String name) {
        List<String> out = new ArrayList<>();
        for (BoneAnimation bone : file.getAnimation(name).boneAnimations) {
            out.add(bone.boneName);
        }
        return out;
    }
}
