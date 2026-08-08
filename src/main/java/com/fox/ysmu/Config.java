package com.fox.ysmu;

import java.io.File;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

public class Config {
    private static Configuration configuration;

    // General Config
    public static boolean DISCLAIMER_SHOW = true;
    public static boolean PRINT_ANIMATION_ROULETTE_MSG = true;
    public static boolean DISABLE_SELF_MODEL = false;
    public static boolean DISABLE_OTHER_MODEL = false;
    public static boolean DISABLE_SELF_HANDS = false;
    public static String DEFAULT_MODEL_ID = "default";
    public static String DEFAULT_MODEL_TEXTURE = "default.png";

    // Extra Player Screen Config
    public static boolean DISABLE_PLAYER_RENDER = false;
    public static int PLAYER_POS_X = 10;
    public static int PLAYER_POS_Y = 10;
    public static double PLAYER_SCALE = 40.0;
    public static double PLAYER_YAW_OFFSET = 5.0;
    public static boolean SWAP_CONFIG_SIDES = false;

    // GUI Config
    public static boolean GUI_ENHANCEMENTS = true;
    public static boolean SHOW_LOADING_PROGRESS = true;
    /** Preview FBO refresh interval in frames. 0 = no periodic refresh (only on interaction). 1-4 = refresh every N frames. default: 2*/
    public static int GUI_MODEL_PREVIEW_REFRESH = 2;
    /** HUD selfie model FBO cache. When true (default), renders to an off-screen
     *  framebuffer every 10 frames and blits the cached texture between refreshes.
     *  Set to false to force every-frame rendering (no performance gain). */
    public static boolean GUI_HUD_PREVIEW_CACHE = true;

    // Local asset config
    /** Path to a high-version Minecraft game directory (e.g. C:/Users/x/.minecraft) */
    public static String HIGH_VERSION_GAME_PATH = "";
    /** Asset version to use (e.g. "1.21"). Should match the game directory's assets/indexes/ */
    public static String HIGH_VERSION_ASSET_VERSION = "1.21";

    // Debug Config
    public static boolean DEBUG_CONTROLLER = false;
    public static boolean DEBUG_WHEEL = false;
    public static boolean DEBUG_MODEL_LOAD = false;
    /** 子开关：细分 DEBUG_MODEL_LOAD 的日志域，避免大型模型库初始化时刷爆日志。
     *  每个子开关只有在 DEBUG_MODEL_LOAD 为 true 时才生效。 */
    public static boolean DEBUG_MODEL_SCAN = false;
    public static boolean DEBUG_MODEL_SYNC = false;
    public static boolean DEBUG_MODEL_PARSE = false;
    /** 二进制 .ysm 解析细节（偏移地址/HEXDUMP/逐动画逐骨骼日志），默认关，避免刷屏。
     *  仅在 DEBUG_MODEL_LOAD 为 true 时生效，独立于 DEBUG_MODEL_PARSE。 */
    public static boolean DEBUG_MODEL_BINARY = false;
    public static boolean DEBUG_MODEL_RENDER = false;
    public static boolean DEBUG_ANIMATION = false;
    public static boolean DEBUG_SOUND = false;
    public static boolean DEBUG_PARTICLE = false;
    /** 调试/测试用：粒子生成时从 Y 偏移额外减去的值（格）。默认 0。
     *  用于临时校正模型粒子高度偏差（如 mingf 火焰偏高约 2 格）。 */
    public static double PARTICLE_Y_ADJUST = 0.0;
    /** 调试/测试用：粒子 xyz 偏移全部置 0（直接生成在实体位置）。 */
    public static boolean PARTICLE_ZERO_OFFSET = false;
    public static boolean DEBUG_MERGED_ANIMATIONS = false;
    public static boolean SHOW_WELCOME_MESSAGE = true;

    // External wearable rendering (AdventureBackpack2 backpacks/copter/jetpack etc.)
    public static boolean RENDER_WEARABLE = true;
    public static double WEARABLE_RENDER_SCALE = 0.8;

    // Direct Buffer watchdog
    /** Safety-net: periodically check Direct Buffer usage and GC if over threshold */
    public static boolean ENABLE_DIRECT_BUFFER_WATCHDOG = true;
    /** Direct Buffer GC trigger threshold in MB */
    public static int DIRECT_BUFFER_WATCHDOG_THRESHOLD_MB = 1024;

    // Texture VRAM config
    /** Target texture dimension (px) uploaded to VRAM; 0 = disabled (full resolution).
     *  Textures larger than the target are uniformly downscaled by a power of two
     *  so the result lands in [target, 2*target) — e.g. target 1024 keeps 2048->1024,
     *  and anything under 2048 passes through. Bound VRAM on large model libraries;
     *  GeckoLib samples with normalized UVs, so an aspect-preserving resize never
     *  shifts the mapped content. Default 0 (off): downscaling still has visual
     *  artifacts on some models (e.g. GUMI2.6.2), so it is opt-in until a better
     *  downscale strategy lands. */
    public static int TEXTURE_TARGET_SIZE = 0;

    /** GPU VRAM budget for YSM model textures in MB; 0 = disabled.
     *  When the total VRAM footprint of uploaded YSM textures exceeds this budget,
     *  least-recently-used models' GPU textures are freed (raw bytes stay in RAM, so
     *  re-upload is a cheap GPU upload — never a white model). Bounds the VRAM peak
     *  on large model libraries instead of per-texture downscaling, which still has
     *  visual artifacts (e.g. GUMI2.6.2). Roughly 24-36 typical models fit in
     *  128-256 MB; raise it if on-screen demand regularly exceeds the budget. */
    public static int TEXTURE_VRAM_BUDGET_MB = 256;

    /** 30s 空闲卸载 GPU 纹理时，是否一并释放堆内的原始字节。
     *  释放的字节在下次使用时由 ensureTexturesLoaded → restoreTextureData 从加密客户端
     *  缓存重解密（与 geo/anim 懒恢复同一条已验证路径）。消除大库纹理字节的线性堆增长；
     *  代价是闲置模型重新入场时会在渲染线程短暂解密其缓存文件。 */
    public static boolean TEXTURE_RELEASE_BYTES_ON_IDLE = true;

    // Model sync config
    public static boolean ENABLE_SYNC_PROTOCOL = true;
    public static int THREAD_COUNT = 4;
    public static int BANDWIDTH_LIMIT = 0;
    public static int PLAYER_SYNC_TIMEOUT = 60;
    public static boolean LOW_BANDWIDTH_USAGE = false;
    public static boolean ACCEPT_SOUND_FX = true;
    /** 玩家手动 /ysm sync 重新同步的冷却秒数：-1 = 完全拒绝，0 = 不限。 */
    public static int RESYNC_COOLDOWN_SECONDS = 600;

    // Offhand item render hiding
    /** 隐藏副手物品渲染的总开关（第二页 GUI 开关，默认开启）。 */
    public static boolean HIDE_OFFHAND_DEFOLIAGE_AXE = true;
    /** 在副手中时完全不渲染的物品（格式 modid:itemname，精确匹配不区分大小写）。
     *  影响第一人称手持渲染、第三人称模型手持物品层与 HUD 自拍模型。
     *  默认隐藏 Extra Utilities 的除叶子斧（defoliage axe）。 */
    public static String[] HIDDEN_OFFHAND_ITEMS = new String[] { "ExtraUtilities:defoliageAxe" };

    // Animation config
    /** 全局 GeckoLib 动画过渡时长（单位 tick，20 tick = 1 秒）。
     *  0 = 立即切换，2 = 100ms，4 = 200ms。
     *  仅作为默认值：若模型控制器 JSON 定义了 blend_transition 会按状态覆盖此值。 */
    public static int ANIMATION_TRANSITION_TICKS = 4;

    /** 移动动画防滑步（stride matching）总开关（默认关闭）：
     *  按真实水平速度缩放 walk/run/sneak 等移动类动画的播放倍速，
     *  使步态周期与位移匹配，减少脚在地面滑动的观感。
     *  默认关闭：该功能假设每个动画循环恰好是两步（一个完整步幅），
     *  但不少模型设计成 1 步或 3/4 步，强行匹配反而更怪，按需开启。 */
    public static boolean ANIMATION_SPEED_MATCH = false;
    /** 防滑步基础倍率：整体缩放防滑步播放倍速（1.0 = 各步态规范步幅）。
     *  唯一的外部调节旋钮——所有模型动画整体偏快/偏慢时在此微调；
     *  各步态的规范步幅是内部常量（walk/run 4.317、sneak 1.295、swim 1.727），
     *  最终的可视化逐模型校准页（方案 C）会覆盖此值。 */
    public static double ANIMATION_SPEED_MATCH_BASE = 1.0;
    /** 防滑步倍速平滑响应系数（配置允许 0.05~1.0，默认 0.5；每 tick 向目标倍速逼近的比例）。
     *  越小越平滑但反应越慢；越大反应越快但急起急停可能可见。 */
    public static double ANIMATION_SPEED_MATCH_RESPONSE = 0.5;

    /**
     * 在 Mod preInit 阶段调用，用于初始化配置文件并进行首次加载。
     * @param configFile a suggested configuration file from the FMLPreInitializationEvent.
     */
    public static void init(File configFile) {
        if (configuration == null) {
            configuration = new Configuration(configFile);
            sync(true); // true 表示执行加载操作
        }
    }

    /**
     * 在需要保存配置时（如关闭GUI）调用。
     */
    public static void save() {
        sync(false); // false 表示执行保存操作
    }

    /**
     * 根据参数决定加载或保存。
     * @param load 如果为 true，则从配置文件加载到静态变量；如果为 false，则从静态变量保存到配置文件。
     */
    private static void sync(boolean load) {
        if (load) {
            configuration.load();
        }

        // General config values
        DISCLAIMER_SHOW = syncBoolean("DisclaimerShow", Configuration.CATEGORY_GENERAL, DISCLAIMER_SHOW, "Whether to display disclaimer GUI", load);
        PRINT_ANIMATION_ROULETTE_MSG = syncBoolean("PrintAnimationRouletteMsg", Configuration.CATEGORY_GENERAL, PRINT_ANIMATION_ROULETTE_MSG, "Whether to print animation roulette play message", load);
        DISABLE_SELF_MODEL = syncBoolean("DisableSelfModel", Configuration.CATEGORY_GENERAL, DISABLE_SELF_MODEL, "Prevents rendering of self player's model", load);
        DISABLE_OTHER_MODEL = syncBoolean("DisableOtherModel", Configuration.CATEGORY_GENERAL, DISABLE_OTHER_MODEL, "Prevents rendering of other player's model", load);
        DISABLE_SELF_HANDS = syncBoolean("DisableSelfHands", Configuration.CATEGORY_GENERAL, DISABLE_SELF_HANDS, "Prevents rendering of self player's hand", load);
        DEFAULT_MODEL_ID = syncString("DefaultModelId", Configuration.CATEGORY_GENERAL, DEFAULT_MODEL_ID, "The default model ID when a player first enters the game", load);
        DEFAULT_MODEL_TEXTURE = syncString("DefaultModelTexture", Configuration.CATEGORY_GENERAL, DEFAULT_MODEL_TEXTURE, "The default model texture when a player first enters the game", load);

        // Extra player render config values
        DISABLE_PLAYER_RENDER = syncBoolean("DisablePlayerRender", "extra_player_render", DISABLE_PLAYER_RENDER, "Whether to display player", load);
        PLAYER_POS_X = syncInt("PlayerPosX", "extra_player_render", PLAYER_POS_X, "Player position x in screen", 0, Integer.MAX_VALUE, load);
        PLAYER_POS_Y = syncInt("PlayerPosY", "extra_player_render", PLAYER_POS_Y, "Player position y in screen", 0, Integer.MAX_VALUE, load);
        PLAYER_SCALE = syncDouble("PlayerScale", "extra_player_render", PLAYER_SCALE, "Player scale in screen", 8.0, 360.0, load);
        PLAYER_YAW_OFFSET = syncDouble("PlayerYawOffset", "extra_player_render", PLAYER_YAW_OFFSET, "Player yaw offset in screen", load);
        SWAP_CONFIG_SIDES = syncBoolean("SwapConfigSides", "extra_player_render", SWAP_CONFIG_SIDES, "Swap wheel config panel and preview sides", load);

        // GUI config values
        GUI_ENHANCEMENTS = syncBoolean("GuiEnhancements", "gui", GUI_ENHANCEMENTS, "Enable model selection GUI enhancements (foreground/background textures and GUI animations)", load);
        SHOW_LOADING_PROGRESS = syncBoolean("ShowLoadingProgress", "gui", SHOW_LOADING_PROGRESS, "Show model sync progress bar overlay", load);
        GUI_MODEL_PREVIEW_REFRESH = syncInt("GuiModelPreviewRefresh", "gui", GUI_MODEL_PREVIEW_REFRESH, "Preview refresh interval in frames. 0 = static (no periodic refresh, only on interaction). 1-4 = refresh every N frames. Higher = smoother animation but more GPU load.", 0, 4, load);        GUI_HUD_PREVIEW_CACHE = syncBoolean("GuiHudPreviewCache", "gui", GUI_HUD_PREVIEW_CACHE, "HUD selfie model FBO cache. Disable for every-frame rendering (no performance gain).", load);
        // Local asset config values
        HIGH_VERSION_GAME_PATH = syncString("HighVersionGamePath", "local_assets", HIGH_VERSION_GAME_PATH, "Path to a high-version Minecraft game directory (e.g. C:/Users/x/.minecraft). YSMU reads sounds.json and OGG files from here to play high-version sounds that Et-Futurum doesn't cover.", load);
        HIGH_VERSION_ASSET_VERSION = syncString("HighVersionAssetVersion", "local_assets", HIGH_VERSION_ASSET_VERSION, "Asset version to use (e.g. '1.21'). Must match the version subfolder under assets/indexes/ in the game directory.", load);

        // Debug config values
        DEBUG_CONTROLLER = syncBoolean("DebugController", "debug", DEBUG_CONTROLLER, "Enable controller transition/roaming debug logging ([YSMU-CTRL])", load);
        DEBUG_WHEEL = syncBoolean("DebugWheel", "debug", DEBUG_WHEEL, "Enable wheel GUI debug logging ([YSMU-WHEEL], [YSMU-ROAM])", load);
        DEBUG_MODEL_LOAD = syncBoolean("DebugModelLoad", "debug", DEBUG_MODEL_LOAD, "Enable model loading/debug logging ([YSMU-MODEL]). Master switch; sub-areas are gated by DebugModelScan/Sync/Parse/Render", load);
        DEBUG_MODEL_SCAN = syncBoolean("DebugModelScan", "debug", DEBUG_MODEL_SCAN, "Server-side model discovery/cache build logging (needs DebugModelLoad)", load);
        DEBUG_MODEL_SYNC = syncBoolean("DebugModelSync", "debug", DEBUG_MODEL_SYNC, "Client model sync protocol logging (needs DebugModelLoad)", load);
        DEBUG_MODEL_PARSE = syncBoolean("DebugModelParse", "debug", DEBUG_MODEL_PARSE, "Model JSON parse & registration logging (needs DebugModelLoad)", load);
        DEBUG_MODEL_BINARY = syncBoolean("DebugModelBinary", "debug", DEBUG_MODEL_BINARY, "Binary .ysm parse detail logging: offsets/HEXDUMP/per-anim/per-bone (needs DebugModelLoad). Default off to avoid log spam on large libraries.", load);
        DEBUG_MODEL_RENDER = syncBoolean("DebugModelRender", "debug", DEBUG_MODEL_RENDER, "Model render/arrow/bone-dump logging (needs DebugModelLoad)", load);
        DEBUG_ANIMATION = syncBoolean("DebugAnimation", "debug", DEBUG_ANIMATION, "Enable animation playback debug logging ([YSMU-ANIM])", load);
        DEBUG_SOUND = syncBoolean("DebugSound", "debug", DEBUG_SOUND, "Enable sound cache/playback debug logging ([YSM Sound])", load);
        DEBUG_PARTICLE = syncBoolean("DebugParticle", "debug", DEBUG_PARTICLE, "Enable particle()/abs_particle() debug logging ([YSMU-PARTICLE])", load);
        PARTICLE_Y_ADJUST = syncDouble("ParticleYAdjust", "debug", PARTICLE_Y_ADJUST, "Extra Y offset subtracted from particle()/abs_particle() spawn position, in blocks (debug/testing; default 0)", -10.0, 10.0, load);
        PARTICLE_ZERO_OFFSET = syncBoolean("ParticleZeroOffset", "debug", PARTICLE_ZERO_OFFSET, "Force particle()/abs_particle() xyz offsets to 0 (spawn at entity position; debug/testing)", load);
        DEBUG_MERGED_ANIMATIONS = syncBoolean("DebugMergedAnimations", "debug", DEBUG_MERGED_ANIMATIONS, "Show __ysm_merged__ animations in the preview GUI for debugging", load);
        SHOW_WELCOME_MESSAGE = syncBoolean("ShowWelcomeMessage", "debug", SHOW_WELCOME_MESSAGE, "Show the welcome/info message when joining a world", load);

        // Model sync config values
        RENDER_WEARABLE = syncBoolean("RenderWearable", "compatibility", RENDER_WEARABLE, "Whether to render external wearable models (e.g. AdventureBackpack2 backpack/copter/jetpack) on YSM model's back", load);
        WEARABLE_RENDER_SCALE = syncDouble("WearableRenderScale", "compatibility", WEARABLE_RENDER_SCALE, "Scale factor for wearable model rendering (1.0 = default)", 0.1, 5.0, load);
        ENABLE_DIRECT_BUFFER_WATCHDOG = syncBoolean("EnableDirectBufferWatchdog", "watchdog", ENABLE_DIRECT_BUFFER_WATCHDOG, "Safety-net: periodically check Direct Buffer usage and trigger GC when over threshold", load);
        DIRECT_BUFFER_WATCHDOG_THRESHOLD_MB = syncInt("DirectBufferWatchdogThreshold", "watchdog", DIRECT_BUFFER_WATCHDOG_THRESHOLD_MB, "Direct Buffer GC trigger threshold in MB", 128, 8192, load);
        ENABLE_SYNC_PROTOCOL = syncBoolean("EnableSyncProtocol", "ysm_sync", ENABLE_SYNC_PROTOCOL, "Use the unified model sync protocol (covers all model formats: folders, BOM+YSGP and legacy bare-YSGP .ysm). When disabled or version-mismatched, falls back to the legacy MD5/AES sync", load);
        THREAD_COUNT = syncInt("ThreadCount", "ysm_sync", THREAD_COUNT, "Target worker count for YSM model sync tasks", 1, 32, load);
        BANDWIDTH_LIMIT = syncInt("BandwidthLimit", "ysm_sync", BANDWIDTH_LIMIT, "model sync bandwidth limit in bytes per second. 0 means unlimited", 0, Integer.MAX_VALUE, load);
        PLAYER_SYNC_TIMEOUT = syncInt("PlayerSyncTimeout", "ysm_sync", PLAYER_SYNC_TIMEOUT, "model sync timeout in seconds", 5, Integer.MAX_VALUE, load);
        RESYNC_COOLDOWN_SECONDS = syncInt("ResyncCooldownSeconds", "ysm_sync", RESYNC_COOLDOWN_SECONDS,
            "Cooldown in seconds between player-triggered /ysm sync resyncs. -1 rejects the resync command entirely; 0 = no cooldown", -1, Integer.MAX_VALUE, load);
        LOW_BANDWIDTH_USAGE = syncBoolean("LowBandwidthUsage", "ysm_sync", LOW_BANDWIDTH_USAGE, "Whether sync should use smaller chunks and conservative throttling", load);
        ACCEPT_SOUND_FX = syncBoolean("AcceptSoundFX", "ysm_sync", ACCEPT_SOUND_FX, "Whether sync should accept model sound effect resources", load);
        TEXTURE_TARGET_SIZE = syncInt("TextureTargetSize", "ysm_sync", TEXTURE_TARGET_SIZE, "Target texture dimension (px) uploaded to VRAM. 0 = full resolution. Larger textures are downscaled by a power of two (result in [target, 2*target)).", 0, 8192, load);
        TEXTURE_VRAM_BUDGET_MB = syncInt("TextureVramBudget", "ysm_sync", TEXTURE_VRAM_BUDGET_MB, "YSM model texture VRAM budget in MB. 0 = unlimited. When uploaded texture VRAM exceeds this, least-recently-used models' GPU textures are freed (raw bytes stay in RAM, so re-upload is cheap and never white).", 0, 8192, load);
        TEXTURE_RELEASE_BYTES_ON_IDLE = syncBoolean("TextureReleaseBytesOnIdle", "ysm_sync", TEXTURE_RELEASE_BYTES_ON_IDLE, "Release raw texture bytes from heap when a model's GPU textures idle-unload (30s). Re-decrypted from the encrypted client cache on next use (same path as geo/anim lazy reload). Eliminates the linear heap growth from texture bytes on large libraries; the first re-entry of an idle model briefly decrypts its cache file on the render thread.", load);
        HIDE_OFFHAND_DEFOLIAGE_AXE = syncBoolean("HideOffhandDefoliageAxe", Configuration.CATEGORY_GENERAL, HIDE_OFFHAND_DEFOLIAGE_AXE, "Hide the Extra Utilities defoliage axe while held in the offhand (first-person hand, model in-hand layer, HUD selfie).", load);
        HIDDEN_OFFHAND_ITEMS = syncStringList("HiddenOffhandItems", Configuration.CATEGORY_GENERAL, HIDDEN_OFFHAND_ITEMS, "Offhand items (format modid:itemname) that are never rendered while held in the offhand (first-person hand, model in-hand layer, HUD selfie). Default: Extra Utilities defoliage axe.", load);
        ANIMATION_TRANSITION_TICKS = syncInt("AnimationTransitionTicks", "animation", ANIMATION_TRANSITION_TICKS, "Global GeckoLib animation transition length in ticks (20 ticks = 1s). 0 = instant, 2 = 100ms, 4 = 200ms. Model-defined blend_transition overrides this per state.", 0, 40, load);
        ANIMATION_SPEED_MATCH = syncBoolean("AnimationSpeedMatch", "animation", ANIMATION_SPEED_MATCH, "Scale locomotion animation playback rate to actual movement speed to reduce foot sliding (stride matching).", load);
        ANIMATION_SPEED_MATCH_BASE = syncDouble("AnimationSpeedMatchBase", "animation", ANIMATION_SPEED_MATCH_BASE, "Baseline rate for stride matching playback speed (1.0 = canonical per-gait strides). Playback = base x actualSpeed x cycleTime / gaitStride. Player speed is read live each frame so potion buffs/debuffs adapt automatically. Increase if animations play too slow, decrease if too fast.", 0.25, 4.0, load);
        ANIMATION_SPEED_MATCH_RESPONSE = syncDouble("AnimationSpeedMatchResponse", "animation", ANIMATION_SPEED_MATCH_RESPONSE, "Smoothing response per tick for the stride-match speed multiplier (0.05-1.0). Lower = smoother but slower reaction.", 0.05, 1.0, load);

        // 检查配置是否已更改，如果已更改，则保存
        if (configuration.hasChanged()) {
            configuration.save();
        }
    }

    private static String[] syncStringList(String name, String category, String[] currentValue, String comment, boolean load) {
        Property prop = configuration.get(category, name, currentValue, comment);
        if (load) {
            return prop.getStringList();
        } else {
            prop.set(currentValue);
            return currentValue;
        }
    }

    private static boolean syncBoolean(String name, String category, boolean currentValue, String comment, boolean load) {
        Property prop = configuration.get(category, name, currentValue, comment);
        if (load) {
            return prop.getBoolean(currentValue);
        } else {
            prop.set(currentValue);
            return currentValue;
        }
    }

    private static String syncString(String name, String category, String currentValue, String comment, boolean load) {
        Property prop = configuration.get(category, name, currentValue, comment);
        if (load) {
            return prop.getString();
        } else {
            prop.set(currentValue);
            return currentValue;
        }
    }

    private static int syncInt(String name, String category, int currentValue, String comment, int min, int max, boolean load) {
        Property prop = configuration.get(category, name, currentValue, comment, min, max);
        if (load) {
            return prop.getInt(currentValue);
        } else {
            prop.set(currentValue);
            return currentValue;
        }
    }

    private static double syncDouble(String name, String category, double currentValue, String comment, double min, double max, boolean load) {
        Property prop = configuration.get(category, name, currentValue, comment, min, max);
        if (load) {
            return prop.getDouble(currentValue);
        } else {
            prop.set(currentValue);
            return currentValue;
        }
    }

    private static double syncDouble(String name, String category, double currentValue, String comment, boolean load) {
        Property prop = configuration.get(category, name, currentValue, comment);
        if (load) {
            return prop.getDouble(currentValue);
        } else {
            prop.set(currentValue);
            return currentValue;
        }
    }
}
