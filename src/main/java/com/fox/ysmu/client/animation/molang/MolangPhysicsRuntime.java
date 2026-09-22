package com.fox.ysmu.client.animation.molang;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.vecmath.Matrix4f;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import com.fox.ysmu.Config;
import com.fox.ysmu.client.entity.CustomPlayerEntity;
import com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime;

import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.core.molang.LazyVariable;
import software.bernie.geckolib3.core.molang.MolangStringPool;
import software.bernie.geckolib3.core.processor.AnimationProcessor;
import software.bernie.geckolib3.core.processor.IBone;
import software.bernie.geckolib3.geo.render.built.GeoBone;
import software.bernie.geckolib3.util.MatrixStack;

public final class MolangPhysicsRuntime {

    /** Replaces ThreadLocal to avoid ThreadLocalMap hash-collision overhead (~9% of profile).
     *  Safe because all client rendering happens on the Minecraft client thread. */
    private static FrameContext currentFrameContext;
    private static final Map<ScopeKey, ScopeState> STATES = new ConcurrentHashMap<>();

    /**
     * 弹射物（子模型）骨骼作用域：只提供"名字 → 骨骼"这一件事，供
     * {@code ysm.bone_pivot_abs()} / {@code bone_position|rotation|scale()} 使用。
     *
     * <p>弹射物的 GeoModel 不经过玩家的 {@link AnimationProcessor} 注册，所以
     * {@link #bone(int)} 原来对它一律返回 null：某弹射物动画的时间轴里
     * {@code ysm.bone_pivot_abs('Arrow')} 恒等于 0，粒子只能落在实体原点（而不是模型上算出来的位置）。
     * 这里用一份临时的名字表补上——弹射物渲染期间由
     * {@code ArrowProjectileRenderer} 调用 {@link #beginProjectileBones} 建立。</p>
     *
     * <p>刻意不复用 {@link FrameContext}：弹射物没有玩家侧的物理状态与变量作用域，
     * 借用 FrameContext 会让 {@code first_order} 之类的函数去读空的 {@code context.state}。
     * 这个作用域只在"玩家上下文没解析出骨骼"时兜底，因此不会影响玩家模型。</p>
     */
    private static Map<String, IBone> projectileBones;

    /**
     * 最近一帧 ScopeState 的 v.* 变量快照（debug 用）。
     * 嵌套赋值（如 {@code (cond) ? (v.wet = 30) : 0}）经由
     * {@code ScopedMolangVariable.set()} 只写入当前帧的 ScopeState；
     * frame context 在渲染帧结束（end()）后即被清空，debug overlay / /ysm query
     * 再读 MolangParser.VARIABLES 只会拿到默认 0。这里在 end() 时保存快照，
     * 让这些读取路径能拿到最近一帧的真实值。
     */
    private static Map<String, Double> lastFrameVariables = java.util.Collections.emptyMap();

    /** 全局 MolangParser.VARIABLES 中 v.* 变量的写入来源（模型显示名）。
     *  debug overlay 用：重名变量（如 v.roaming.a）可看到当前值由哪个模型
     *  注入/写入（残留来源 = 跨模型串变量的元凶）。读取隔离
     *  {@link #getGlobalScopedValue} 也用它在全局 fallback 时按模型过滤。 */
    private static final Map<String, String> GLOBAL_VAR_OWNER = new java.util.HashMap<>();

    /** 渲染路径骨骼绝对位置追踪（bone_pivot_abs 与几何渲染走同一矩阵路径）。
     *  每帧在 MatrixStack.transformBone 里按 模型+骨名 记录骨链累计后的完整 4×4 矩阵
     *  （blocks，含模型缩放，预 yaw / 预玩家位移）。bone_pivot_abs 用该矩阵计算
     *  枢轴点的世界位置（M×pivot），避免骨骼自身缩放/旋转污染平移列（如一个火把 locator
     *  的 scale [1.25, 2.5, 1.5] 会让平移列 z 虚增约 2 倍）。 */
    private static final Map<String, float[]> CAPTURED_BONE_MATRIX = new java.util.HashMap<>();
    private static boolean trackingEnabled = false;
    private static ResourceLocation trackingModelId = null;
    private static float trackScaleX = 1.0F;
    private static float trackScaleY = 1.0F;
    private static float trackScaleZ = 1.0F;

    static {
        MatrixStack.boneTransformSink = MolangPhysicsRuntime::captureBoneTransform;
    }

    /** Time delta (in seconds) since the last render frame, used by ysm.time_delta. */
    private static float timeDelta = 0f;
    private static double prevRenderTicks = -1;

    /** Returns the frame time delta in seconds (ysm.time_delta). */
    public static float getTimeDelta() { return timeDelta; }

    private MolangPhysicsRuntime() {}

    // ── 每帧的模型求值次数（诊断） ──────────────────────────────────────────
    //
    // 模型的 Molang 状态里有大量"每次求值推进一步"的累加器（`temp.x = v.x; v.x = ...-0.01`）
    // 和按求值采样的阈值判断。它们的推进速率 = 本帧的求值次数 × 帧率，所以"某个效果在
    // 60 fps 看不见、高帧率下闪烁"这类现象，第一个要确认的数就是这里。F3（Shift 展开）会显示。
    private static int evalsInFrame;
    private static int evalsPerFrame;

    /** 模型求值（= 一个模型 pass）计数 */
    private static void noteEvaluation() {
        evalsInFrame++;
    }

    /** 每个渲染帧调一次（{@code ClientEventHandler.onRenderTick}）：结算上一帧的求值次数。 */
    public static void endRenderFrame() {
        evalsPerFrame = evalsInFrame;
        evalsInFrame = 0;
    }

    /** 上一渲染帧里模型被求值了几次（含所有玩家/所有模型）。 */
    public static int evaluationsPerFrame() {
        return evalsPerFrame;
    }

    /**
     * Called at the start of each render frame for a player model.
     * Injects roaming variables set from outside the render loop (e.g. GUI)
     * into both this render frame's ScopeState and the controller RuntimeState.
     */
    public static void begin(CustomPlayerEntity animatable, double renderTicks, AnimationProcessor<?> processor) {
        if (animatable == null || processor == null) {
            currentFrameContext = null;
            return;
        }
        noteEvaluation();
        // 模型 pass 入口：重置本 pass 的时间轴派发预算。帧计数由真实渲染帧推进
        // （ClientEventHandler.onRenderTick → advanceRenderFrame），**不能**在这里推进——
        // 一帧渲染 N 个模型会把计数推进 N 次，"停放超过 10 帧"的再入判定就会每帧误判。
        com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime.beginModelPass();
        EntityPlayer player = animatable.getPlayer();
        ScopeKey key = ScopeKey.from(player, animatable.getMainModel());
        ScopeState state = STATES.computeIfAbsent(key, ignored -> new ScopeState());
        state.beginPass();
        ResourceLocation modelId = animatable.getMainModel();
        // Only inject roaming variables that belong to the current model.
        // Using getRoamingVarsForModel() instead of directly iterating
        // PENDING_ROAMING prevents cross-model variable contamination.
        Map<String, Double> modelRoaming = OpenYsmPlayerControllerRuntime.getRoamingVarsForModel(modelId);
        // 显示名一帧只算一次：下面注 roaming 的循环要写进 GLOBAL_VAR_OWNER，FrameContext 也要用它
        // 做跨模型隔离比较（见 FrameContext#displayName）。
        String frameDisplayName = modelId == null ? null : getModelDisplayName(modelId);
        if (!modelRoaming.isEmpty()) {
            // 注入 v. 前缀 + 原 case + 小写 + 去 "roaming." 前缀的裸名，使
            // ScopedMolangVariable（关键帧 Molang）能按多种写法命中同一变量；
            // 否则裸名引用（如 v.bq_eye）会因只存了 v.roaming.bq_eye 而读到 0。
            for (Map.Entry<String, Double> entry : modelRoaming.entrySet()) {
                OpenYsmPlayerControllerRuntime.injectRoamingVar(state.variables, state.dirtyVariables, "v.",
                    entry.getKey(), entry.getValue(), modelId);
            }
            // car_stuff@player_ctrl_parallel_6.molang:
            //   v.show_car=v.roaming.car && !(ctrl.tac_hold_gun||...)
            // 简化桥接: v.roaming.car == 1 时设为 1，否则 0。
            // 同时写入 PENDING_ROAMING 让条件映射（evaluateSimpleCondition）能读到。
            Double roamingCar = state.variables.get("v.roaming.car");
            if (roamingCar != null) {
                double showCar = roamingCar > 0 ? 1.0 : 0.0;
                state.putVariable("v.show_car", showCar);
                OpenYsmPlayerControllerRuntime.PENDING_ROAMING.put("show_car", showCar);
            }
            // Also inject roaming values into MolangParser.VARIABLES so that
            // timeline instructions (executed via MolangInstructionExecutor)
            // can read current roaming values through the parser's normal
            // variable resolution path even after end() clears the frame context.
            // Keys in modelRoaming already include the "roaming." prefix
            // (e.g. "roaming.bq_eye"), so we prepend "v." to match the
            // variable name format used by MolangParser ("v.roaming.bq_eye").
            // Additionally, for keys with "roaming." prefix, also inject
            // under the plain key (e.g. "v.bq_eye") so that bone keyframes
            // referencing v.bq_eye directly see the current roaming value
            // every frame, without depending on a transient animation's
            // timeline instruction (like "v.bq_eye = v.roaming.bq_eye != 0
            // ? v.roaming.bq_eye : ...") which only fires when that specific
            // animation plays and is then cached in executedKeyFrames.
            for (Map.Entry<String, Double> roamingEntry : modelRoaming.entrySet()) {
                String varKey = "v." + roamingEntry.getKey();
                MolangParser.VARIABLES.computeIfAbsent(varKey,
                    k -> new LazyVariable(k, 0)).set(roamingEntry.getValue());
                GLOBAL_VAR_OWNER.put(varKey, frameDisplayName);
                // Strip "roaming." prefix so v.roaming.bq_eye also sets v.bq_eye
                String roamingKey = roamingEntry.getKey();
                if (roamingKey.startsWith("roaming.")) {
                    String plainKey = "v." + roamingKey.substring("roaming.".length());
                    MolangParser.VARIABLES.computeIfAbsent(plainKey,
                        k -> new LazyVariable(k, 0)).set(roamingEntry.getValue());
                    GLOBAL_VAR_OWNER.put(plainKey, frameDisplayName);
                }
            }
        }
        // 应用 .molang 函数文件中定义的变量派生规则。
        // @player_ctrl_pre_main.molang 开头: v.anim_ctrl=1;
        // 但我们不执行 .molang 文件，所以在这里设置默认值。
        if (!state.variables.containsKey("v.anim_ctrl")) {
            state.putVariable("v.anim_ctrl", 1.0);
        }
        state.physics.update(renderTicks);
        // Compute time delta for ysm.time_delta
        if (prevRenderTicks >= 0 && renderTicks > prevRenderTicks) {
            timeDelta = (float) ((renderTicks - prevRenderTicks) / 20.0);
        }
        prevRenderTicks = renderTicks;
        currentFrameContext = new FrameContext(modelId, state, processor, frameDisplayName);
        // .molang 事件订阅（@player_init / @player_update）在这里触发：
        // 漫游变量已经注入完毕（wiki 要求 roaming 同步早于 player_init），
        // 而 setMolangQueries 正是"每次更新玩家动画之前"。
        com.fox.ysmu.client.animation.controller.OpenYsmScriptRuntime.runFrameScripts(player, modelId);
    }

    /**
     * 建立弹射物（子模型）骨骼作用域：把几何里的骨骼按名字登记，供
     * {@code ysm.bone_pivot_abs(...)} 等骨骼函数在弹射物动画的时间轴里解析。
     *
     * <p>由 {@code ArrowProjectileRenderer} 在一次弹射物渲染 pass 内成对调用
     * （{@link #beginProjectileBones} / {@link #endProjectileBones}），
     * 递归收集所有层级的骨骼；结束时会恢复调用前的作用域（弹射物渲染可能发生在
     * 玩家渲染之外，但也可能是嵌套的，所以用局部变量保存而不是简单置 null）。</p>
     */
    public static Map<String, IBone> beginProjectileBones(
        java.util.List<GeoBone> topLevelBones) {
        Map<String, IBone> previous = projectileBones;
        Map<String, IBone> bones = new java.util.HashMap<>();
        collectBones(topLevelBones, bones);
        projectileBones = bones;
        return previous;
    }

    /** 结束弹射物骨骼作用域，恢复 {@link #beginProjectileBones} 之前的那一份。 */
    public static void endProjectileBones(Map<String, IBone> previous) {
        projectileBones = previous;
    }

    private static void collectBones(java.util.List<GeoBone> bones, Map<String, IBone> out) {
        if (bones == null) {
            return;
        }
        for (GeoBone bone : bones) {
            if (bone == null) {
                continue;
            }
            if (bone.name != null) {
                out.put(bone.name, bone);
            }
            collectBones(bone.childBones, out);
        }
    }

    public static void end() {
        // 保存最近一帧的 ScopeState 变量快照（debug overlay / /ysm query 用）：
        // 嵌套赋值（如 v.wet）只写 ScopeState，frame context 结束后再读
        // MolangParser.VARIABLES 只会拿到默认 0，因此这里先存一份副本。
        FrameContext context = currentFrameContext;
        lastFrameVariables = context == null
            ? java.util.Collections.<String, Double>emptyMap()
            : new java.util.HashMap<>(context.state.variables);
        currentFrameContext = null;
        // Clear the per-frame roaming variable cache so the next frame
        // picks up any PENDING_ROAMING changes from the GUI thread.
        OpenYsmPlayerControllerRuntime.invalidateFrameRoamingCache();
    }

    /**
     * Runs {@code body} with the variable scope of {@code (player, modelId)} installed
     * as the current frame context, without a render pass.
     * <p>
     * Out-of-frame script events ({@code @sync}, dispatched from a scheduled task
     * between frames) have no {@code begin()}/{@code end()} of their own, so
     * {@link #setVariable} returned {@code false} and every {@code v.*} assignment in
     * them was silently dropped. Installing the scope makes such a write land in the
     * same {@link ScopeState} the next render frame of that player+model reads, and
     * keeps it isolated from whichever other player/model is being rendered.
     * <p>
     * The context has no {@link AnimationProcessor}, so bone/physics reads degrade to
     * their neutral defaults ({@link #bone(int)} returns {@code null}); a {@code @sync}
     * script has no rendered bones to read anyway. A previously installed frame context
     * (nested call) is restored afterwards.
     */
    public static void runWithVariableScope(EntityPlayer player, ResourceLocation modelId, Runnable body) {
        if (body == null) {
            return;
        }
        FrameContext previous = currentFrameContext;
        ScopeState state = modelId == null ? null
            : STATES.computeIfAbsent(ScopeKey.from(player, modelId), ignored -> new ScopeState());
        currentFrameContext = state == null ? null : new FrameContext(modelId, state, null);
        try {
            body.run();
        } finally {
            currentFrameContext = previous;
        }
    }

    public static void clear() {
        STATES.clear();
        CAPTURED_BONE_MATRIX.clear();
        lastFrameVariables = java.util.Collections.emptyMap();
        currentFrameContext = null;
        GLOBAL_VAR_OWNER.clear();
        OpenYsmPlayerControllerRuntime.invalidateFrameRoamingCache();
    }

    /** 最近一帧 ScopeState 的 v.* 变量快照（frame context 已结束后仍可读）。 */
    public static Map<String, Double> getLastFrameVariables() {
        return lastFrameVariables;
    }

    /** 读取最近一帧 ScopeState 中某个变量的值；该帧未设置则返回 null。 */
    public static Double getLastFrameVariable(String name) {
        return lastFrameVariables.get(name);
    }

    /** 清空最近一帧变量快照（/ysm reset 用），让 debug overlay 立即反映重置后的状态。 */
    public static void clearLastFrameSnapshot() {
        lastFrameVariables = java.util.Collections.emptyMap();
    }

    /** 清理指定玩家的全部 ScopeState（玩家登出时调用），避免断线后
     *  (player, model) 组合的物理/变量状态残留到下次 reload。 */
    public static void clearPlayer(UUID playerId) {
        if (playerId == null) {
            return;
        }
        java.util.Iterator<Map.Entry<ScopeKey, ScopeState>> it = STATES.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ScopeKey, ScopeState> e = it.next();
            if (playerId.equals(e.getKey().playerId)) {
                it.remove();
            }
        }
    }

    public static double firstOrder(int nameId, double input, double response) {
        if (nameId == MolangStringPool.EMPTY_ID) {
            return 0.0D;
        }
        FrameContext context = currentFrameContext;
        if (context == null) {
            return input;
        }
        return context.state.physics.firstOrder(nameId, input, response);
    }

    public static double secondOrder(int nameId, double input, double frequency, double coefficient, double response) {
        if (nameId == MolangStringPool.EMPTY_ID) {
            return 0.0D;
        }
        FrameContext context = currentFrameContext;
        if (context == null) {
            return input;
        }
        return context.state.physics.secondOrder(nameId, input, frequency, coefficient, response);
    }

    private static final java.util.Set<String> LOGGED_VARS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static double getVariable(String name, double fallback) {
        FrameContext context = currentFrameContext;
        if (context == null) {
            if (Config.DEBUG_ANIMATION && name.startsWith("v.roaming.") && LOGGED_VARS.add(name)) {
                com.fox.ysmu.ysmu.LOG.info(
                    "YSM MolangPhysicsRuntime: getVariable('{}') – NO FRAME CONTEXT, fallback={}",
                    name, fallback);
            }
            return fallback;
        }
        Double value = context.state.variables.get(name);
        // 只记录 v.roaming.* 变量，每个变量名只记录第一次（仅 DEBUG_ANIMATION 时输出）
        if (Config.DEBUG_ANIMATION && name.startsWith("v.roaming.") && LOGGED_VARS.add(name)) {
            com.fox.ysmu.ysmu.LOG.info(
                "YSM MolangPhysicsRuntime: getVariable('{}') = {} (fallback={})",
                name, value, fallback);
        }
        return value == null ? fallback : value;
    }

    /**
     * Checks whether a variable was explicitly set in the current frame context.
     * This is used by the null-coalescing operator (??) to distinguish between
     * "variable was explicitly set to 0" and "variable was never set (defaults to 0)".
     */
    public static boolean containsKey(String name) {
        FrameContext context = currentFrameContext;
        if (context == null) return false;
        return context.state.variables.containsKey(name);
    }

    public static boolean setVariable(String name, double value) {
        FrameContext context = currentFrameContext;
        if (context == null) {
            return false;
        }
        context.state.putVariable(name, value);
        // 常驻变量（v.roaming.*）赋值必须"粘住"：动画/时间轴写完的值若是只留在本帧作用域里，
        // 下一帧 begin() 注入 ysm.json 默认值时就会被冲掉（轮盘"变身"只能生效一次的根因）。
        // 这里是所有 v.* 赋值路径的唯一汇聚点——关键帧表达式、嵌套赋值、时间轴指令都走它。
        OpenYsmPlayerControllerRuntime.noteRoamingWrite(context.modelId, name, value);
        return true;
    }

    /** {@code "v.<name>"} -> {@code "<name>"}. Stripping the prefix allocates a new
     *  String per entry per call, and {@link #syncToRuntimeState} walks the whole
     *  scope table once per matching controller per frame. */
    private static final Map<String, String> STRIPPED_NAMES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Syncs v.* variables set by animation keyframe Molang expressions
     * (e.g. {@code v.idle_time = v.idle_time + 3}) back into the controller's
     * RuntimeState so that YSM controller transition conditions can see
     * the updated values.
     * <p>
     * Keyframes write to {@link ScopeState#variables} (via
     * {@link ScopedMolangVariable}), while controller conditions read from
     * {@link com.fox.ysmu.client.animation.controller.OpenYsmPlayerControllerRuntime.RuntimeState#variables}.
     * Without this sync, variables only updated in keyframe Molang would
     * appear stuck at their initial value to the controller.
     * <p>
     * 只遍历 {@link ScopeState#dirtyVariables}（本帧写过的键）。原来每帧整表遍历一次，
     * 而一帧要跑十来个控制器、每个都遍历一遍，实测 2.0 % 客户端线程。
     */
    public static void syncToRuntimeState(Map<String, Double> target) {
        FrameContext context = currentFrameContext;
        if (context == null) return;
        ScopeState scope = context.state;
        if (target.isEmpty()) {
            // 新控制器（RuntimeState 刚建）：作用域表里还带着**前几帧**写进去的值，
            // 那些不在本帧脏集里，所以这一次必须整表同步，之后靠脏集增量。
            for (Map.Entry<String, Double> entry : scope.variables.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("v.")) continue;
                target.put(strippedName(key), entry.getValue());
            }
            return;
        }
        // 增量：只处理本 pass / 上一 pass 写过的变量。值没变的不再 put
        // （RuntimeState 的 map 跨帧存活）。这条路径等价于原来的整表遍历：整表里其它键的
        // 值要么已经在 target 里，要么是新建 target 时由上面那条整表分支灌进去的。
        syncNames(scope, scope.dirtyVariables, null, target);
        syncNames(scope, scope.dirtyPreviousPass, scope.dirtyVariables, target);
    }

    /** 把 {@code names} 里当前存在的 {@code v.*} 值增量同步进 {@code target}；
     *  {@code skip}（可为 null）里的名字跳过，用于遍历两个脏集时去重。 */
    private static void syncNames(ScopeState scope, java.util.Set<String> names,
        java.util.Set<String> skip, Map<String, Double> target) {
        for (String key : names) {
            if (skip != null && skip.contains(key)) continue;
            if (!key.startsWith("v.")) continue;
            Double value = scope.variables.get(key);
            if (value == null) continue; // 同帧内被 clearVariable 移除
            String bare = strippedName(key);
            Double previous = target.get(bare);
            if (previous == null || !previous.equals(value)) {
                target.put(bare, value);
            }
        }
    }

    /** {@code "v.<name>"} -> {@code "<name>"}，命中缓存避免每次调用都 substring 分配。 */
    private static String strippedName(String key) {
        String bare = STRIPPED_NAMES.get(key);
        if (bare == null) {
            bare = key.substring(2);
            STRIPPED_NAMES.put(key, bare);
        }
        return bare;
    }

    /**
     * Removes a variable from the current frame's scope state. Used to make
     * conditional animation pulses transient (e.g. clearing v.swing_sword right
     * after the GUI-preview swing state machine consumes it, mimicking gameplay's
     * one-frame pulse instead of a sticky value that re-triggers tick-0 sound
     * keyframes every frame).
     */
    public static void clearVariable(String name) {
        FrameContext context = currentFrameContext;
        if (context == null) return;
        context.state.variables.remove(name);
    }

    /**
     * Clears swing/hold conditional animation variables from the GUI preview
     * scope (player == null) for the given model. Does NOT touch the real
     * player's scope, so in-game model state is unaffected.
     */
    public static void clearPreviewVariables(ResourceLocation modelId) {
        if (modelId == null) return;
        ScopeState state = STATES.get(new ScopeKey(null, modelId));
        if (state == null) return;
        state.variables.remove("v.swing_sword");
        state.variables.remove("v.swing");
        state.variables.remove("v.swing_end");
        state.variables.remove("v.attack");
        state.variables.remove("v.attacking");
        state.variables.remove("v.hold_mainhand");
        state.variables.remove("v.hold_offhand");
    }

    public static double boneRotation(int nameId, char axis) {
        IBone bone = bone(nameId);
        if (bone == null) {
            return 0.0D;
        }
        if (axis == 'x') {
            return -Math.toDegrees(bone.getRotationX());
        }
        if (axis == 'y') {
            return -Math.toDegrees(bone.getRotationY());
        }
        return Math.toDegrees(bone.getRotationZ());
    }

    public static double bonePosition(int nameId, char axis) {
        IBone bone = bone(nameId);
        if (bone == null) {
            return 0.0D;
        }
        if (axis == 'x') {
            return bone.getPositionX();
        }
        if (axis == 'y') {
            return bone.getPositionY();
        }
        return bone.getPositionZ();
    }

    /** 开启/关闭渲染期骨骼变换追踪（CustomPlayerRenderer 每帧包裹渲染调用）。
     *  开启时 MatrixStack.transformBone 记录每个骨的完整累计矩阵，供 bone_pivot_abs 读取。
     *
     * @param scaleX/scaleY/scaleZ 模型渲染缩放（renderEarly 应用的 width/height/width）
     * @param modelId 当前渲染的模型主 id（用于隔离不同模型的骨骼） */
    public static void setBoneTracking(boolean enabled, float scaleX, float scaleY, float scaleZ,
        ResourceLocation modelId) {
        trackingEnabled = enabled;
        if (enabled) {
            trackScaleX = scaleX;
            trackScaleY = scaleY;
            trackScaleZ = scaleZ;
            trackingModelId = modelId;
        } else {
            trackingModelId = null;
        }
    }

    /** MatrixStack 每骨渲染后回调：记录骨链累计后的完整矩阵
     *  （blocks，含模型缩放，预 yaw/预玩家位移）。回调须同步复制（Matrix4f 会被复用）。 */
    private static void captureBoneTransform(GeoBone bone, javax.vecmath.Matrix4f mat,
        float pivotX, float pivotY, float pivotZ) {
        if (!trackingEnabled || trackingModelId == null || bone == null || bone.getName() == null) {
            return;
        }
        String key = trackingModelId + "::" + bone.getName();
        float[] v = CAPTURED_BONE_MATRIX.get(key);
        if (v == null) {
            v = new float[16];
            CAPTURED_BONE_MATRIX.put(key, v);
        }
        v[0] = mat.m00;
        v[1] = mat.m01;
        v[2] = mat.m02;
        v[3] = mat.m03;
        v[4] = mat.m10;
        v[5] = mat.m11;
        v[6] = mat.m12;
        v[7] = mat.m13;
        v[8] = mat.m20;
        v[9] = mat.m21;
        v[10] = mat.m22;
        v[11] = mat.m23;
        v[12] = mat.m30;
        v[13] = mat.m31;
        v[14] = mat.m32;
        v[15] = mat.m33;
    }

    /** 骨骼绝对枢轴（模型单位，16 单位 = 1 格；OpenYSM bone_pivot_abs 语义）。
     *  优先读取渲染路径追踪到的骨骼矩阵（与几何渲染同一矩阵路径）；
     *  未追踪到（首帧/预览等）时回退为沿父链矩阵重算。
     *  <p>三轴都用 M×pivot（枢轴点世界位置）——平移列会被目标骨骼自身 scale/rotation
     *  污染（如某个火把 locator 的 scale [1.25,2.5,1.5]：平移列 z 虚增~2 倍、
     *  x/y 抖动），M×pivot 稳定且正确。
     *  <p><b>X 轴取负</b>：GeoBuilder 对 pivot.x/cube.x 取负（GeckoLib 内部约定），
     *  捕获矩阵在 GeoBone 空间（左手 X 为负）；而模型粒子公式
     *  {@code particle(..., bone_pivot_abs(...).x, ...)} 期望的 X 是渲染帧
     *  （无 GL 镜像）的坐标（左手为正），因此 x 轴结果需取反。z 轴由模型公式自带的
     *  {@code -bone_pivot_abs(...).z} 处理，y 轴无需取反。 */
    public static double bonePivot(int nameId, char axis) {
        IBone bone = bone(nameId);
        if (bone == null) {
            return 0.0D;
        }
        double matrixResult = matrixBonePivot(bone, axis);
        if (projectileBones != null) {
            // 弹射物作用域没有渲染期矩阵捕获，直接用父链重算；同时**避开**捕获路径：
            // 捕获表是按"当前玩家模型 + 骨骼名"存的，弹射物骨骼名可能和玩家模型重名
            // （例如都叫 Arrow），走了捕获就会拿到玩家那条骨骼的位置。
            return matrixResult;
        }
        if (trackingEnabled && trackingModelId != null) {
            String boneName = MolangStringPool.get(nameId);
            float[] m = boneName == null ? null
                : CAPTURED_BONE_MATRIX.get(trackingModelId + "::" + boneName);
            if (m != null) {
                // 枢轴点（blocks，与捕获矩阵同单位）
                float px = bone.getPivotX() / 16f;
                float py = bone.getPivotY() / 16f;
                float pz = bone.getPivotZ() / 16f;
                // 枢轴点的世界位置 = M × pivot（blocks），不受骨骼自身缩放/旋转污染。
                double wx = m[3] + (double) m[0] * px + (double) m[1] * py + (double) m[2] * pz;
                double wy = m[7] + (double) m[4] * px + (double) m[5] * py + (double) m[6] * pz;
                double wz = m[11] + (double) m[8] * px + (double) m[9] * py + (double) m[10] * pz;
                // GeoBone 空间 X 取负（GeoBuilder 约定）→ 渲染帧 X；模型公式 x 不取负。
                double capturedResult;
                if (axis == 'x') {
                    capturedResult = -wx * (16.0D / trackScaleX);
                } else if (axis == 'y') {
                    capturedResult = wy * (16.0D / trackScaleY);
                } else {
                    capturedResult = wz * (16.0D / trackScaleZ);
                }
                if (Config.DEBUG_PARTICLE) {
                    com.fox.ysmu.ysmu.LOG.info(
                        "[YSMU-PARTICLE] bonePivot('{}',{}) MxPivot=({},{},{}) -> {} | matrixFallback -> {}",
                        boneName, axis, wx, wy, wz, capturedResult, matrixResult);
                }
                return capturedResult;
            }
        }
        if (Config.DEBUG_PARTICLE) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-PARTICLE] bonePivot('{}',{}) no-capture -> matrixFallback {}",
                MolangStringPool.get(nameId), axis, matrixResult);
        }
        return matrixResult;
    }

    /** 沿 GeoBone 父链矩阵重算绝对枢轴（无渲染追踪时的回退，语义同 bonePivot：
     *  三轴都用 M×pivot；x 轴取负（GeoBone 空间 → 渲染帧，见 bonePivot 注释）。 */
    private static double matrixBonePivot(IBone bone, char axis) {
        if (!(bone instanceof GeoBone)) {
            // VirtualBone 等无父链/几何：退化为本地枢轴（GeoBone 空间，x 取负）
            if (axis == 'x') {
                return -bone.getPivotX();
            }
            if (axis == 'y') {
                return bone.getPivotY();
            }
            return bone.getPivotZ();
        }
        GeoBone geo = (GeoBone) bone;
        // 收集 root→target 骨链
        java.util.ArrayList<GeoBone> chain = new java.util.ArrayList<>();
        for (GeoBone b = geo; b != null; b = b.parent) {
            chain.add(b);
        }
        java.util.Collections.reverse(chain);
        Matrix4f acc = new Matrix4f();
        acc.setIdentity();
        Matrix4f tmp = new Matrix4f();
        for (GeoBone b : chain) {
            appendBoneTransform(acc, tmp, b);
        }
        // 枢轴点的世界位置 = M × pivot（模型单位），不受骨骼自身缩放/旋转污染。
        float px = geo.getPivotX();
        float py = geo.getPivotY();
        float pz = geo.getPivotZ();
        double wx = acc.m03 + (double) acc.m00 * px + (double) acc.m01 * py + (double) acc.m02 * pz;
        double wy = acc.m13 + (double) acc.m10 * px + (double) acc.m11 * py + (double) acc.m12 * pz;
        double wz = acc.m23 + (double) acc.m20 * px + (double) acc.m21 * py + (double) acc.m22 * pz;
        // x 轴取负（GeoBone 空间 → 渲染帧）；z 由模型公式自带取负，y 不变。
        if (axis == 'x') {
            return -wx;
        }
        if (axis == 'y') {
            return wy;
        }
        return wz;
    }

    /** acc = acc × M_bone（模型单位，与渲染 transformBone 相同的组合顺序）。 */
    private static void appendBoneTransform(Matrix4f acc, Matrix4f tmp, GeoBone b) {
        float px = b.getPivotX();
        float py = b.getPivotY();
        float pz = b.getPivotZ();
        float tx = -b.getPositionX();
        float ty = b.getPositionY();
        float tz = b.getPositionZ();
        float sx = b.getScaleX();
        float sy = b.getScaleY();
        float sz = b.getScaleZ();
        float rx = b.getRotationX();
        float ry = b.getRotationY();
        float rz = b.getRotationZ();
        // T(pos)
        tmp.setIdentity();
        tmp.m03 = tx;
        tmp.m13 = ty;
        tmp.m23 = tz;
        acc.mul(tmp);
        // T(pivot)
        tmp.setIdentity();
        tmp.m03 = px;
        tmp.m13 = py;
        tmp.m23 = pz;
        acc.mul(tmp);
        // Rz × Ry × Rx
        if (rz != 0.0F) {
            tmp.setIdentity();
            tmp.rotZ(rz);
            acc.mul(tmp);
        }
        if (ry != 0.0F) {
            tmp.setIdentity();
            tmp.rotY(ry);
            acc.mul(tmp);
        }
        if (rx != 0.0F) {
            tmp.setIdentity();
            tmp.rotX(rx);
            acc.mul(tmp);
        }
        // S
        tmp.setIdentity();
        tmp.m00 = sx;
        tmp.m11 = sy;
        tmp.m22 = sz;
        acc.mul(tmp);
        // T(-pivot)
        tmp.setIdentity();
        tmp.m03 = -px;
        tmp.m13 = -py;
        tmp.m23 = -pz;
        acc.mul(tmp);
    }

    public static double boneScale(int nameId, char axis) {
        IBone bone = bone(nameId);
        if (bone == null) {
            return axis == 'x' || axis == 'y' || axis == 'z' ? 1.0D : 0.0D;
        }
        if (axis == 'x') {
            return bone.getScaleX();
        }
        if (axis == 'y') {
            return bone.getScaleY();
        }
        return bone.getScaleZ();
    }

    private static IBone bone(int nameId) {
        if (nameId == MolangStringPool.EMPTY_ID) {
            return null;
        }
        String boneName = MolangStringPool.get(nameId);
        if (boneName == null) {
            return null;
        }
        FrameContext context = currentFrameContext;
        if (context != null && context.processor != null) {
            IBone bone = context.processor.getBone(boneName);
            if (bone != null) {
                return bone;
            }
        }
        // 弹射物（子模型）作用域兜底：GeoModel 没注册进玩家 processor，只能查这份名字表。
        Map<String, IBone> bones = projectileBones;
        return bones == null ? null : bones.get(boneName);
    }

    private static final class FrameContext {
        private final ResourceLocation modelId;
        private final ScopeState state;
        private final AnimationProcessor<?> processor;
        /**
         * 本帧模型的可读显示名，构造时算一次。
         *
         * <p>{@link #getGlobalScopedValue} 与 {@link #noteGlobalVarOwner} 每次读写一个全局
         * {@code v.*} 都要拿它与 {@link #GLOBAL_VAR_OWNER} 里的来源比较；以前两处各自现算
         * 一次 {@link #getModelDisplayName(ResourceLocation)}（去子段建 ResourceLocation +
         * 查 MODEL_DISPLAY_NAMES + hex 解码 + substring），而一帧内 modelId 是固定的 ——
         * 这是纯重复计算，实测占客户端线程约 1.8%（其中 ModelIdUtil 一侧 1.2%、
         * MolangPhysicsRuntime 一侧 0.6%）。</p>
         */
        private final String displayName;

        private FrameContext(ResourceLocation modelId, ScopeState state, AnimationProcessor<?> processor) {
            this(modelId, state, processor, modelId == null ? null : getModelDisplayName(modelId));
        }

        /** 已经算好显示名的重载：调用方同一帧内还要用同一个名字写 GLOBAL_VAR_OWNER。 */
        private FrameContext(ResourceLocation modelId, ScopeState state, AnimationProcessor<?> processor,
            String displayName) {
            this.modelId = modelId;
            this.state = state;
            this.processor = processor;
            this.displayName = displayName;
        }
    }

    /** 当前渲染帧所属的模型 id（null 表示无活动帧上下文）。供 ?? 运算符等
     *  需要按模型判断"用户显式设置"的场景使用。 */
    public static ResourceLocation getCurrentModelId() {
        FrameContext context = currentFrameContext;
        return context == null ? null : context.modelId;
    }

    /** debug overlay 用：全局 MolangParser.VARIABLES 中该 v.* 变量的写入来源
     *  （模型显示名）。返回 null 表示当前无全局写入记录（可能来自 ScopeState /
     *  PENDING_ROAMING / 当前模型注入）。 */
    public static String getGlobalVarSource(String name) {
        return GLOBAL_VAR_OWNER.get(name);
    }

    /** timeline 指令（MolangInstructionExecutor）写入全局 VARIABLES 时记录来源
     *  模型，供 debug overlay 显示跨模型残留来源。 */
    public static void noteGlobalVarOwner(String varName) {
        FrameContext context = currentFrameContext;
        if (context != null && context.modelId != null) {
            GLOBAL_VAR_OWNER.put(varName, context.displayName);
        }
    }

    /** ScopedMolangVariable 的全局 fallback 读取拦截：当前模型 scope 无该变量
     *  时，只允许读回“当前模型自己写入”的全局 v.*（begin() 注入的 roaming 或
     *  当前模型 timeline 写入的）；其他模型写入的残留（GLOBAL_VAR_OWNER 记录的
     *  来源 != 当前模型，如 14_momo 读别的模型的 v.roaming.a）返回 0，实现
     *  跨模型隔离。不删除任何 VARIABLES 条目，因此不会破坏系统注册变量，也
     *  没有并发遍历风险。无来源记录的变量（系统注册等）不隔离。 */
    public static double getGlobalScopedValue(String name, double fallback) {
        FrameContext ctx = currentFrameContext;
        String current = ctx == null ? null : ctx.displayName;
        return isGlobalVarReadable(GLOBAL_VAR_OWNER.get(name), current) ? fallback : 0.0D;
    }

    /** 帧外（无 {@link FrameContext}）写进全局 {@code VARIABLES} 的 {@code v.*} 来源标记：
     *  这种值不属于任何模型，读取侧一律挡住（见 {@link #isGlobalVarReadable}）。 */
    private static final String UNSCOPED_OWNER = "<unscoped>";

    /** {@link ScopedMolangVariable#unscopedWriteSink} 的落点：记下"这个全局 v.* 是帧外写的"。 */
    public static void noteUnscopedGlobalVarWrite(String varName) {
        if (varName != null) {
            GLOBAL_VAR_OWNER.put(varName, UNSCOPED_OWNER);
        }
    }

    /**
     * 全局 {@code v.*} 是否允许读回当前模型（纯策略，拆出来便于单测）。
     *
     * @param owner            写入来源模型显示名；{@code null} = 无记录（系统注册变量、
     *                         带 supplier 的静态变量），{@link #UNSCOPED_OWNER} = 帧外写入
     * @param currentModelName 当前渲染模型显示名；{@code null} = 没有模型上下文
     */
    public static boolean isGlobalVarReadable(String owner, String currentModelName) {
        if (currentModelName == null) {
            // 没有模型上下文（帧外：模型初始化、指令、单测里的 Molang 求值）：不比较模型，
            // 帧外写 → 帧外读的往返必须继续可用。
            return true;
        }
        if (UNSCOPED_OWNER.equals(owner)) {
            // 帧外写入、现在有模型在读：这个值不属于任何模型。以前它没有来源记录，于是
            // "无记录 = 放行"会把模型初始化阶段写的值端给正在渲染的模型。
            return false;
        }
        if (owner == null) {
            // 系统注册的 v.*（带 supplier）与旧行为一致：不隔离。
            return true;
        }
        return owner.equals(currentModelName);
    }

    /** 模型的可读显示名，用于 debug overlay 来源列（与模型预览页面一致）：
     *  1. 优先 MODEL_DISPLAY_NAMES（lang 文件 > ysm.json metadata.name，如 "MoMo Wine Fox"）；
     *  2. 否则解码 hex 编码的路径并取最后一段（如 wine_fox_fold/14_momo → 14_momo）。
     *  接受 main id（带 /main）或 base id，内部统一。 */
    public static String getModelDisplayName(ResourceLocation modelId) {
        if (modelId == null) {
            return null;
        }
        try {
            // base id（去掉 /main 等子段），与 MODEL_DISPLAY_NAMES 的 key 一致；
            // 也避免 hex 解码时被 "/main" 后缀中的 '/' 打断（getModelDisplayName
            // 遇非 hex 字符会返回原 path，导致显示成 _name_77696e655f666...）。
            ResourceLocation baseId = com.fox.ysmu.util.ModelIdUtil.getModelIdFromSubId(modelId);
            String meta = com.fox.ysmu.client.ClientModelManager.MODEL_DISPLAY_NAMES.get(baseId);
            if (meta != null && !meta.isEmpty()) {
                return meta;
            }
            String decoded = com.fox.ysmu.util.ModelIdUtil.getModelDisplayName(baseId.getResourcePath());
            int sep = decoded.lastIndexOf('/');
            return sep >= 0 ? decoded.substring(sep + 1) : decoded;
        } catch (RuntimeException e) {
            return modelId.getResourcePath();
        }
    }

    private static final class ScopeState {
        private final MolangPhysicsState physics = new MolangPhysicsState();
        /** Regular HashMap is safe: all ScopeState access is on the client render thread. */
        private final Map<String, Double> variables = new java.util.HashMap<>();
        /**
         * 最近两个渲染 pass 里被写过的变量名。{@link #syncToRuntimeState} 只遍历这两个集合，
         * 不再对每个控制器整表遍历一遍 —— 一帧要跑十来个控制器（预览页还有十三个模型），
         * "控制器数 × 变量数" 次 map 查找实测占客户端线程 2.0 %。
         *
         * <p>为什么要**两个**：pass 与 pass 之间还有一条写入路径（{@code @sync} 脚本事件，
         * 见 {@link #runWithVariableScope}），它写在最后一次 sync 之后。如果 begin() 直接清空，
         * 那个写入的标记会被丢掉、值再也进不了控制器 —— 而旧的整表实现是能看见它的。
         * 所以 begin() 把上一 pass 的集合"过继"到 {@code dirtyPreviousPass}，本 pass 的控制器
         * 仍能读到；再上一 pass 的才丢弃（那些值早就在所有现存 RuntimeState 里了，
         * 新建的 RuntimeState 由 {@code target.isEmpty()} 分支整表兜底）。
         */
        private final java.util.Set<String> dirtyVariables = new java.util.HashSet<>();
        private final java.util.Set<String> dirtyPreviousPass = new java.util.HashSet<>();

        /** 唯一的内部写入点：写值 + 标脏。 */
        private void putVariable(String name, double value) {
            variables.put(name, value);
            dirtyVariables.add(name);
        }

        /** pass 开始：上一 pass 的脏集过继到 previous，本 pass 从空集开始。 */
        private void beginPass() {
            dirtyPreviousPass.clear();
            dirtyPreviousPass.addAll(dirtyVariables);
            dirtyVariables.clear();
        }
    }

    private static final class ScopeKey {
        private final UUID playerId;
        private final ResourceLocation modelId;

        private ScopeKey(UUID playerId, ResourceLocation modelId) {
            this.playerId = playerId;
            this.modelId = modelId;
        }

        private static ScopeKey from(EntityPlayer player, ResourceLocation modelId) {
            UUID playerId = player == null ? null : player.getUniqueID();
            return new ScopeKey(playerId, modelId);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ScopeKey)) {
                return false;
            }
            ScopeKey other = (ScopeKey) obj;
            if (playerId == null ? other.playerId != null : !playerId.equals(other.playerId)) {
                return false;
            }
            return modelId == null ? other.modelId == null : modelId.equals(other.modelId);
        }

        @Override
        public int hashCode() {
            int result = playerId == null ? 0 : playerId.hashCode();
            result = 31 * result + (modelId == null ? 0 : modelId.hashCode());
            return result;
        }
    }
}
