package com.fox.ysmu.client.renderer;

import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import com.fox.ysmu.client.ClientModelManager;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;
import com.fox.ysmu.util.ModelIdUtil;

import software.bernie.geckolib3.core.easing.EasingManager;
import software.bernie.geckolib3.core.easing.EasingType;
import software.bernie.geckolib3.core.keyframe.BoneAnimation;
import software.bernie.geckolib3.core.keyframe.KeyFrame;
import software.bernie.geckolib3.core.keyframe.VectorKeyFrameList;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.util.MathUtil;
import software.bernie.geckolib3.file.AnimationFile;
import software.bernie.geckolib3.geo.IGeoRenderer;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.geo.render.built.GeoCube;
import software.bernie.geckolib3.geo.render.built.GeoModel;
import software.bernie.geckolib3.geo.render.built.GeoQuad;
import software.bernie.geckolib3.resource.GeckoLibCache;
import software.bernie.geckolib3.core.builder.Animation;
import software.bernie.geckolib3.core.snapshot.BoneSnapshot;

import com.eliotlash.mclib.math.IValue;

/**
 * Renders a projectile sub-entity GeoModel (e.g. #arrow) in place of a vanilla entity.
 * Called from the RenderArrow mixin when the arrow has a custom model ID.
 */
public class ArrowProjectileRenderer {

    /**
     * Try to render a custom projectile model for the given entity and model ID.
     * @return true if a custom model was rendered (vanilla rendering should be skipped),
     *         false if no custom model is available (vanilla should proceed).
     */
    @SuppressWarnings("unchecked")
    public static boolean render(Entity entity, double x, double y, double z, float yaw, float partialTicks,
        ResourceLocation modelId, IGeoRenderer<?> geoRenderer) {

        if (modelId == null || entity == null) return false;

        com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] render start: modelId={}, entity={}", modelId, entity.getEntityId());

        // Find projectile entity type "minecraft:arrow" for this model
        List<String> projTypes = ClientModelManager.PROJECTILE_MODEL_IDS.get(modelId);
        if (projTypes == null) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] no PROJECTILE_MODEL_IDS for {}", modelId);
            return false;
        }
        com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] projTypes={}", projTypes);

        String arrowType = null;
        for (String t : projTypes) {
            if (t.contains("arrow")) {
                arrowType = t;
                break;
            }
        }
        if (arrowType == null) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] no arrowType found in {}", projTypes);
            return false;
        }

        // Get the projectile GeoModel
        ResourceLocation projGeoId = ModelIdUtil.getSubModelId(modelId, "projectile_" + arrowType);
        com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] looking for geo: {}", projGeoId);
        GeoModel projModel = GeckoLibCache.getInstance().getGeoModels().get(projGeoId);
        if (projModel == null) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] GeoModel not found: {}", projGeoId);
            return false;
        }
        com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] GeoModel found: topLevelBones={}", 
            projModel.topLevelBones != null ? projModel.topLevelBones.size() : 0);
        if (projModel.topLevelBones != null) {
            for (GeoBone b : projModel.topLevelBones) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW]   bone '{}': cubes={}, childBones={}, hidden={}",
                    b.name,
                    b.childCubes != null ? b.childCubes.size() : 0,
                    b.childBones != null ? b.childBones.size() : 0,
                    b.isHidden());
            }
        }

        // Find projectile texture
        List<ResourceLocation> projTexList = ClientModelManager.PROJECTILE_TEXTURE_IDS.get(modelId);
        ResourceLocation projTexId = null;
        if (projTexList != null) {
            String prefix = "projectile_" + arrowType + "_";
            for (ResourceLocation tid : projTexList) {
                if (tid.getResourcePath().contains(prefix)) {
                    projTexId = tid;
                    break;
                }
            }
        }
        if (projTexId == null) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] no texture found for {}", modelId);
            return false;
        }
        com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] texture: {}", projTexId);

        // === Do a quick sanity check: dump bone tree in debug mode ===
        if (com.fox.ysmu.Config.DEBUG_MODEL_LOAD) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] === Full bone tree dump for {} ===", projGeoId);
            dumpBoneTree(projModel.topLevelBones, 0);
        }

        // === Render the actual projectile GeoModel ===
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(x, y, z);

            // Rotate to match arrow flight direction (vanilla convention)
            float interpYaw = entity.prevRotationYaw
                + (entity.rotationYaw - entity.prevRotationYaw) * partialTicks;
            float interpPitch = entity.prevRotationPitch
                + (entity.rotationPitch - entity.prevRotationPitch) * partialTicks;
            GL11.glRotatef(interpYaw - 90.0F, 0.0F, 1.0F, 0.0F);
            GL11.glRotatef(interpPitch, 0.0F, 0.0F, 1.0F);
            GL11.glScalef(0.7f, 0.7f, 0.7f);

            net.geckominecraft.client.renderer.GlStateManager.disableCull();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            Minecraft.getMinecraft().renderEngine.bindTexture(projTexId);

            // Apply projectile animations (parallel0-7, post_main, etc.)
            // before rendering so bone transforms are correct.
            if (entity instanceof EntityArrow arrow) {
                applyProjectileAnimations(projModel, arrow, partialTicks, projGeoId, modelId, arrowType);
            }

            // Render all bone cubes through GeckoLib's renderRecursively
            // which properly applies MATRIX_STACK bone transforms (pivot/rotation).
            Tessellator tess = Tessellator.instance;
            tess.startDrawing(GL11.GL_QUADS);
            for (GeoBone bone : projModel.topLevelBones) {
                ((IGeoRenderer<Entity>) geoRenderer)
                    .renderRecursively(tess, entity, bone, 1.0F, 1.0F, 1.0F, 1.0F);
            }
            tess.draw();
        } catch (Exception e) {
            com.fox.ysmu.ysmu.LOG.warn("[YSMU-ARROW] Model render failed", e);
        } finally {
            GL11.glPopMatrix();
        }
        return true;
    }

    /**
     * Apply projectile animation keyframes to the GeoModel bones before rendering.
     * Evaluates Molang expressions in keyframes using the arrow entity context.
     */
    @SuppressWarnings("rawtypes")
    private static void applyProjectileAnimations(GeoModel model, EntityArrow arrow, float partialTicks,
        ResourceLocation projGeoId, ResourceLocation modelId, String arrowType) {
        // Get the animation file for this projectile
        AnimationFile animFile = GeckoLibCache.getInstance().getAnimations().get(projGeoId);
        if (animFile == null || animFile.animations == null) {
            // No animations defined for this projectile
            return;
        }

        // Calculate current animation time from arrow age
        double ageInTicks = arrow.ticksExisted + partialTicks;

        // Set projectile-specific Molang variables into the shared parser context.
        // MolangParser.VARIABLES is a static map shared across all evaluation.
        // Detect in-ground state from motion (inGround is private in 1.7.10 EntityArrow).
        boolean isInGround = !arrow.isDead
            && arrow.motionX * arrow.motionX + arrow.motionY * arrow.motionY + arrow.motionZ * arrow.motionZ < 0.0001;
        setMolangVar("ysm.in_ground", isInGround ? 1.0 : 0.0);
        double dx = arrow.posX - arrow.prevPosX;
        double dy = arrow.posY - arrow.prevPosY;
        double dz = arrow.posZ - arrow.prevPosZ;
        double deltaLength = Math.sqrt(dx * dx + dy * dy + dz * dz);
        setMolangVar("ysm.delta_movement_length", deltaLength);
        // In 1.7.10, arrows are always shot by bows (no crossbow).
        // The animation checks ysm.shoot_item_id!='minecraft:crossbow' to show the
        // bow bone. Setting to a non-zero double != string works correctly.
        setMolangVar("ysm.shoot_item_id", 1.0);
        setMolangVar("ysm.on_ground_time", isInGround ? ageInTicks * 0.05 : 0.0);

        // Inject roaming variables from PENDING_ROAMING (client-side GUI-set values).
        for (Map.Entry<String, Double> entry : OpenYsmPlayerControllerRuntime.PENDING_ROAMING.entrySet()) {
            setMolangVar("v.roaming." + entry.getKey(), entry.getValue());
        }

        // Save initial snapshots if not already done
        saveInitialSnapshots(model.topLevelBones);

        // Reset all bones to their initial snapshot before applying animations
        resetBonesToSnapshot(model.topLevelBones);

        // Process all animations in the animation file
        for (java.util.Map.Entry<String, Animation> entry : animFile.animations.entrySet()) {
            Animation anim = entry.getValue();
            if (anim == null || anim.boneAnimations == null) continue;

            double animLength = anim.animationLength != null ? anim.animationLength : 0;
            double animTick;
            if (animLength > 0) {
                // Wrap animation time for looping
                animTick = ageInTicks % animLength;
            } else {
                animTick = ageInTicks;
            }

            // For each bone animated by this animation
            for (BoneAnimation boneAnim : anim.boneAnimations) {
                GeoBone bone = findBone(model.topLevelBones, boneAnim.boneName);
                if (bone == null) continue;

                BoneSnapshot snap = bone.getInitialSnapshot();

                // Apply rotation keyframes
                applyKeyFrameList(bone, boneAnim.rotationKeyFrames, animTick,
                    snap.rotationValueX, snap.rotationValueY, snap.rotationValueZ,
                    true);

                // Apply position keyframes
                applyKeyFrameListPosition(bone, boneAnim.positionKeyFrames, animTick,
                    snap.positionOffsetX, snap.positionOffsetY, snap.positionOffsetZ);

                // Apply scale keyframes
                applyKeyFrameListScale(bone, boneAnim.scaleKeyFrames, animTick,
                    snap.scaleValueX, snap.scaleValueY, snap.scaleValueZ);
            }
        }
    }

    private static void saveInitialSnapshots(List<GeoBone> bones) {
        if (bones == null) return;
        for (GeoBone bone : bones) {
            bone.saveInitialSnapshot();
            saveInitialSnapshots(bone.childBones);
        }
    }

    private static void resetBonesToSnapshot(List<GeoBone> bones) {
        if (bones == null) return;
        for (GeoBone bone : bones) {
            BoneSnapshot snap = bone.getInitialSnapshot();
            if (snap != null) {
                bone.setRotationX(snap.rotationValueX);
                bone.setRotationY(snap.rotationValueY);
                bone.setRotationZ(snap.rotationValueZ);
                bone.setPositionX((float) snap.positionOffsetX);
                bone.setPositionY((float) snap.positionOffsetY);
                bone.setPositionZ((float) snap.positionOffsetZ);
                bone.setScaleX((float) snap.scaleValueX);
                bone.setScaleY((float) snap.scaleValueY);
                bone.setScaleZ((float) snap.scaleValueZ);
            }
            resetBonesToSnapshot(bone.childBones);
        }
    }

    private static GeoBone findBone(List<GeoBone> bones, String name) {
        if (bones == null) return null;
        for (GeoBone bone : bones) {
            if (bone.name.equals(name)) return bone;
            GeoBone found = findBone(bone.childBones, name);
            if (found != null) return found;
        }
        return null;
    }

    private static void applyKeyFrameList(GeoBone bone, VectorKeyFrameList<KeyFrame<IValue>> frames,
        double tick, double snapX, double snapY, double snapZ, boolean isRotation) {
        if (frames == null) return;
        float[] result = evaluateKeyFrameList(frames, tick);
        if (result != null) {
            // Animation values are ADDED to the initial snapshot (GeckoLib convention)
            bone.setRotationX((float) (result[0] + snapX));
            bone.setRotationY((float) (result[1] + snapY));
            bone.setRotationZ((float) (result[2] + snapZ));
        }
    }

    private static void applyKeyFrameListPosition(GeoBone bone, VectorKeyFrameList<KeyFrame<IValue>> frames,
        double tick, double snapX, double snapY, double snapZ) {
        if (frames == null) return;
        float[] result = evaluateKeyFrameList(frames, tick);
        if (result != null) {
            bone.setPositionX((float) (result[0] + snapX));
            bone.setPositionY((float) (result[1] + snapY));
            bone.setPositionZ((float) (result[2] + snapZ));
        }
    }

    private static void applyKeyFrameListScale(GeoBone bone, VectorKeyFrameList<KeyFrame<IValue>> frames,
        double tick, double snapX, double snapY, double snapZ) {
        if (frames == null) return;
        float[] result = evaluateKeyFrameList(frames, tick);
        if (result != null) {
            bone.setScaleX((float) (result[0] * snapX));  // Scale is multiplicative, not additive
            bone.setScaleY((float) (result[1] * snapY));
            bone.setScaleZ((float) (result[2] * snapZ));
        }
    }

    /**
     * Evaluate a VectorKeyFrameList at the given tick.
     * Returns [x, y, z] values interpolated from the current keyframe.
     */
    private static float[] evaluateKeyFrameList(VectorKeyFrameList<KeyFrame<IValue>> frames, double tick) {
        if (frames == null) return null;
        float[] result = new float[3];
        result[0] = evaluateAxis(frames.xKeyFrames, tick);
        result[1] = evaluateAxis(frames.yKeyFrames, tick);
        result[2] = evaluateAxis(frames.zKeyFrames, tick);
        return result;
    }

    /**
     * Evaluate a single axis keyframe list at the given tick.
     * Finds the current keyframe, evaluates start/end values (Molang), and interpolates.
     */
    private static float evaluateAxis(List<KeyFrame<IValue>> keyFrames, double tick) {
        if (keyFrames == null || keyFrames.isEmpty()) return 0;

        // If there's only one keyframe, return its end value
        if (keyFrames.size() == 1) {
            return (float) keyFrames.get(0).getEndValue().get();
        }

        // Find the current keyframe
        double totalTime = 0;
        for (int i = 0; i < keyFrames.size(); i++) {
            KeyFrame<IValue> frame = keyFrames.get(i);
            double frameLength = frame.getLength() != null ? frame.getLength() : 0;
            double nextTotal = totalTime + frameLength;

            if (nextTotal > tick || i == keyFrames.size() - 1) {
                // This is the current keyframe
                double localTick = tick - totalTime;
                double progress = frameLength > 0 ? Math.min(localTick / frameLength, 1.0) : 1.0;

                double startVal = frame.getStartValue().get();
                double endVal = frame.getEndValue().get();

                // Apply easing
                EasingType easing = frame.easingType != null ? frame.easingType : EasingType.Linear;
                double easedProgress = EasingManager.ease(progress, easing, frame.easingArgs);

                return (float) MathUtil.lerp(easedProgress, startVal, endVal);
            }
            totalTime = nextTotal;
        }

        // Past all keyframes - return last keyframe's end value
        KeyFrame<IValue> lastFrame = keyFrames.get(keyFrames.size() - 1);
        return (float) lastFrame.getEndValue().get();
    }

    /**
     * Set a Molang variable in the shared parser context.
     */
    private static void setMolangVar(String name, double value) {
        MolangParser.VARIABLES.computeIfAbsent(name, k -> new LazyVariable(k, () -> 0.0))
            .set(value);
    }

    /**
     * Dump the full bone tree structure to the log for debugging.
     */
    private static void dumpBoneTree(List<GeoBone> bones, int indent) {
        if (bones == null) return;
        for (GeoBone bone : bones) {
            if (bone == null) continue;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < indent; i++) sb.append("  ");
            sb.append("bone '").append(bone.name).append("'");
            sb.append(" cubes=").append(bone.childCubes != null ? bone.childCubes.size() : 0);
            sb.append(" childBones=").append(bone.childBones != null ? bone.childBones.size() : 0);
            sb.append(" hidden=").append(bone.isHidden());
            if (bone.childCubes != null) {
                for (int ci = 0; ci < bone.childCubes.size(); ci++) {
                    GeoCube c = bone.childCubes.get(ci);
                    sb.append("\n");
                    for (int j = 0; j < indent + 1; j++) sb.append("  ");
                    sb.append("cube[").append(ci).append("]: mesh=").append(c != null ? c.mesh : "null");
                    sb.append(" quads=").append((c != null && c.quads != null) ? c.quads.length : 0);
                    sb.append(" pivot=").append(c != null && c.pivot != null ? 
                        String.format("(%.3f,%.3f,%.3f)", c.pivot.x, c.pivot.y, c.pivot.z) : "null");
                    sb.append(" size=").append(c != null && c.size != null ? 
                        String.format("(%.3f,%.3f,%.3f)", c.size.x, c.size.y, c.size.z) : "null");
                    if (c != null && c.quads != null) {
                        for (int qi = 0; qi < c.quads.length; qi++) {
                            GeoQuad q = c.quads[qi];
                            if (q != null && q.vertices != null) {
                                sb.append("\n");
                                for (int j = 0; j < indent + 2; j++) sb.append("  ");
                                sb.append("quad[").append(qi).append("]: verts=").append(q.vertices.length);
                                if (q.vertices.length > 0 && q.vertices[0] != null) {
                                    sb.append(" pos0=(")
                                        .append(String.format("%.4f", q.vertices[0].position.x)).append(",")
                                        .append(String.format("%.4f", q.vertices[0].position.y)).append(",")
                                        .append(String.format("%.4f", q.vertices[0].position.z)).append(")");
                                }
                            } else {
                                sb.append(" [null quad]");
                            }
                        }
                    }
                }
            }
            com.fox.ysmu.ysmu.LOG.info("[YSMU-ARROW] {}", sb.toString());
            dumpBoneTree(bone.childBones, indent + 1);
        }
    }

}
