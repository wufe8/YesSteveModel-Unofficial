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

    // Debug Config
    public static boolean DEBUG_CONTROLLER = false;
    public static boolean DEBUG_WHEEL = false;
    public static boolean DEBUG_MODEL_LOAD = false;
    public static boolean DEBUG_ANIMATION = false;
    public static boolean DEBUG_SOUND = false;

    // Model sync config
    public static boolean ENABLE_SYNC_PROTOCOL = true;
    public static int THREAD_COUNT = 4;
    public static int BANDWIDTH_LIMIT = 0;
    public static int PLAYER_SYNC_TIMEOUT = 60;
    public static boolean LOW_BANDWIDTH_USAGE = false;
    public static boolean ACCEPT_SOUND_FX = true;

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
        GUI_MODEL_PREVIEW_REFRESH = syncInt("GuiModelPreviewRefresh", "gui", GUI_MODEL_PREVIEW_REFRESH, "Preview refresh interval in frames. 0 = static (no periodic refresh, only on interaction). 1-4 = refresh every N frames. Higher = smoother animation but more GPU load.", 0, 4, load);

        // Debug config values
        DEBUG_CONTROLLER = syncBoolean("DebugController", "debug", DEBUG_CONTROLLER, "Enable controller transition/roaming debug logging ([YSMU-CTRL])", load);
        DEBUG_WHEEL = syncBoolean("DebugWheel", "debug", DEBUG_WHEEL, "Enable wheel GUI debug logging ([YSMU-WHEEL], [YSMU-ROAM])", load);
        DEBUG_MODEL_LOAD = syncBoolean("DebugModelLoad", "debug", DEBUG_MODEL_LOAD, "Enable model loading/debug logging ([YSMU-MODEL])", load);
        DEBUG_ANIMATION = syncBoolean("DebugAnimation", "debug", DEBUG_ANIMATION, "Enable animation playback debug logging ([YSMU-ANIM])", load);
        DEBUG_SOUND = syncBoolean("DebugSound", "debug", DEBUG_SOUND, "Enable sound cache/playback debug logging ([YSM Sound])", load);

        // Model sync config values
        ENABLE_SYNC_PROTOCOL = syncBoolean("EnableSyncProtocol", "ysm_sync", ENABLE_SYNC_PROTOCOL, "Whether to use the appended hash/cache/chunk sync path before legacy fallback", load);
        THREAD_COUNT = syncInt("ThreadCount", "ysm_sync", THREAD_COUNT, "Target worker count for YSM model sync tasks", 1, 32, load);
        BANDWIDTH_LIMIT = syncInt("BandwidthLimit", "ysm_sync", BANDWIDTH_LIMIT, "model sync bandwidth limit in bytes per second. 0 means unlimited", 0, Integer.MAX_VALUE, load);
        PLAYER_SYNC_TIMEOUT = syncInt("PlayerSyncTimeout", "ysm_sync", PLAYER_SYNC_TIMEOUT, "model sync timeout in seconds", 5, Integer.MAX_VALUE, load);
        LOW_BANDWIDTH_USAGE = syncBoolean("LowBandwidthUsage", "ysm_sync", LOW_BANDWIDTH_USAGE, "Whether sync should use smaller chunks and conservative throttling", load);
        ACCEPT_SOUND_FX = syncBoolean("AcceptSoundFX", "ysm_sync", ACCEPT_SOUND_FX, "Whether sync should accept model sound effect resources", load);

        // 检查配置是否已更改，如果已更改，则保存
        if (configuration.hasChanged()) {
            configuration.save();
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
