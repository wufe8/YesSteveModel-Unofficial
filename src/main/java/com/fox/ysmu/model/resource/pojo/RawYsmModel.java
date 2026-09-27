package com.fox.ysmu.model.resource.pojo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RawYsmModel {

    public String modelId = "";
    public int formatVersion;
    public RawMetadata metadata = new RawMetadata();
    public RawProperties properties = new RawProperties();
    public RawMainEntity mainEntity = new RawMainEntity();
    public Map<String, RawSubEntity> vehicles = new LinkedHashMap<>();
    public Map<String, RawSubEntity> projectiles = new LinkedHashMap<>();
    public Map<String, RawDataFile> soundFiles = new LinkedHashMap<>();
    public Map<String, RawDataFile> functionFiles = new LinkedHashMap<>();
    public Map<String, RawLanguageFile> languageFiles = new LinkedHashMap<>();
    public RawFooter footer = new RawFooter();

    public static class RawMainEntity {
        public RawGeometry mainModel;
        public RawGeometry armModel;
        public Map<String, RawTexture> textures = new LinkedHashMap<>();
        public Map<String, RawAnimationFile> animationFiles = new LinkedHashMap<>();
        public List<RawAnimationControllerFile> animationControllerFiles = new ArrayList<>();
    }

    public static class RawAnimationControllerFile {
        public String name = "";
        public String hash = "";
        public int legacyUnknownInt;
        public byte[] sourceJson;
        public Map<String, RawAnimationController> controllers = new LinkedHashMap<>();
    }

    public static class RawSubEntity {
        public String identifier = "";
        public String[] matchIds;
        public RawGeometry model;
        public Map<String, RawTexture> textures = new LinkedHashMap<>();
        public Map<String, RawAnimationFile> animationFiles = new LinkedHashMap<>();
        public List<RawAnimationControllerFile> animationControllerFiles = new ArrayList<>();
    }

    public static class RawGeometry {
        public int modelType;
        public String identifier = "";
        public String sha256 = "";
        public float textureWidth = 64f;
        public float textureHeight = 64f;
        public float visibleBoundsWidth;
        public float visibleBoundsHeight;
        public float[] visibleBoundsOffset;
        public float unkFloat1;
        public float unkFloat2;
        public int footerPad1;
        public int footerPad2;
        public int footerPad3;
        public byte[] sourceJson;
        public List<RawBone> bones = new ArrayList<>();
    }

    public static class RawBone {
        public String name = "";
        public String parentName = "";
        public float[] pivot = new float[3];
        public float[] rotation = new float[3];
        public int unkPad1;
        public int unkPad2;
        public int unkPad3;
        public int unkPad4;
        public int unkPad5;
        public List<RawCube> cubes = new ArrayList<>();
    }

    public static class RawCube {
        public List<RawFace> faces = new ArrayList<>();
        public int unkInt1;
        public int unkInt2;
        public int unkInt3;
    }

    public static class RawFace {
        public float[] normal = new float[3];
        public float[][] positions = new float[4][3];
        public float[] u = new float[4];
        public float[] v = new float[4];
    }

    public static class RawAnimationFile {
        public int animType;
        public String fileHash = "";
        public byte[] sourceJson;
        public Map<String, RawAnimation> animations = new LinkedHashMap<>();
    }

    public static class RawAnimation {
        public String name = "";
        public float length;
        public int loopMode;
        public Object blendWeight;
        /** YSMU: Bedrock-style anim_time_update expression（秒） */
        public String animTimeUpdate;
        /** YSMU: per-animation playback speed multiplier（数字或 Molang 表达式） */
        public String animSpeed;
        public int unkInt1;
        public int unkInt2;
        public int unkInt4;
        public List<RawBoneAnimation> boneAnimations = new ArrayList<>();
        public List<RawTimelineEvent> timelineEvents = new ArrayList<>();
        public List<RawSoundEffect> soundEffects = new ArrayList<>();
    }

    public static class RawBoneAnimation {
        public String boneName = "";
        public List<RawKeyframe> rotation = new ArrayList<>();
        public List<RawKeyframe> position = new ArrayList<>();
        public List<RawKeyframe> scale = new ArrayList<>();
    }

    /**
     * 一个关键帧的三轴取值（post 与可选的 pre）。
     *
     * <p>旧表示是两个 {@code Object[3]}，每个数值都装成一个 {@code Float}。VisualVM 堆快照
     * （2026-09-27）里 852,711 个 RawKeyframe 对应着约 5.19M 个 {@code java.lang.Float} 和
     * {@code Object[]} 总数（3.13M）里的一大半 —— 而这些 raw POJO 会被
     * {@code ServerModelManager.RAW_MODEL_INFO} 长期持有（集成服务端还要靠它重新序列化模型
     * 发给客户端），所以这是常驻占用而不是一次性垃圾。
     *
     * <p>现在数值内联成 6 个 float（post 三轴 + pre 三轴），通道类型压进一个 int
     * （每通道 2 bit：缺省 / 数值 / 表达式）；只有真的出现 Molang 表达式（罕见）时才额外分配
     * 一个 {@code String[3]}。通道语义与旧的 {@code Object[]} 一一对应：
     * 数值 → 0x01、字符串 → 0x02、缺省 → 两者都不是，
     * {@link #setPostData}/{@link #setPreData} 保留旧写法作为构造入口。
     */
    public static class RawKeyframe {

        /** 通道没有值（二进制里 datatype 既不是 0x01 也不是 0x02）。 */
        public static final int CHANNEL_ABSENT = 0;
        /** 通道是一个数值。 */
        public static final int CHANNEL_NUMBER = 1;
        /** 通道是一个 Molang 表达式字符串。 */
        public static final int CHANNEL_EXPRESSION = 2;

        public float timestamp;
        public int interpolationMode;
        public boolean hasPreData;

        private float postX;
        private float postY;
        private float postZ;
        private float preX;
        private float preY;
        private float preZ;
        /** 每通道 2 bit：bit 0..5 = post 的 xyz，bit 6..11 = pre 的 xyz。 */
        private int kinds;
        /** 表达式通道的文本；只有出现过表达式才分配。 */
        private String[] postExpressions;
        private String[] preExpressions;

        /** 通道类型：{@link #CHANNEL_ABSENT} / {@link #CHANNEL_NUMBER} / {@link #CHANNEL_EXPRESSION}。 */
        public int kind(boolean pre, int axis) {
            return (kinds >>> (kindShift(pre, axis))) & 3;
        }

        /** 数值通道的值（{@link #kind} 为 {@link #CHANNEL_NUMBER} 时才有意义）。 */
        public float number(boolean pre, int axis) {
            switch (axis) {
                case 0:
                    return pre ? preX : postX;
                case 1:
                    return pre ? preY : postY;
                default:
                    return pre ? preZ : postZ;
            }
        }

        /** 表达式通道的文本（{@link #kind} 为 {@link #CHANNEL_EXPRESSION} 时才有意义）。 */
        public String expression(boolean pre, int axis) {
            String[] expressions = pre ? preExpressions : postExpressions;
            return expressions == null ? null : expressions[axis];
        }

        public void setNumber(boolean pre, int axis, float value) {
            switch (axis) {
                case 0:
                    if (pre) {
                        preX = value;
                    } else {
                        postX = value;
                    }
                    break;
                case 1:
                    if (pre) {
                        preY = value;
                    } else {
                        postY = value;
                    }
                    break;
                default:
                    if (pre) {
                        preZ = value;
                    } else {
                        postZ = value;
                    }
                    break;
            }
            setKind(pre, axis, CHANNEL_NUMBER);
        }

        public void setExpression(boolean pre, int axis, String value) {
            if (pre) {
                if (preExpressions == null) {
                    preExpressions = new String[3];
                }
                preExpressions[axis] = value;
            } else {
                if (postExpressions == null) {
                    postExpressions = new String[3];
                }
                postExpressions[axis] = value;
            }
            setKind(pre, axis, CHANNEL_EXPRESSION);
        }

        /** 旧写法（{@code Object[3]}）的构造入口：数值 / 字符串 / null(=缺省)。 */
        public void setPostData(Object[] values) {
            setSide(false, values);
        }

        /** 见 {@link #setPostData}。 */
        public void setPreData(Object[] values) {
            setSide(true, values);
        }

        private void setSide(boolean pre, Object[] values) {
            for (int axis = 0; axis < 3; axis++) {
                Object value = values != null && values.length > axis ? values[axis] : null;
                if (value instanceof Number) {
                    setNumber(pre, axis, ((Number) value).floatValue());
                } else if (value != null) {
                    setExpression(pre, axis, value.toString());
                } else {
                    setKind(pre, axis, CHANNEL_ABSENT);
                }
            }
        }

        private static int kindShift(boolean pre, int axis) {
            return (pre ? 3 : 0) * 2 + axis * 2;
        }

        private void setKind(boolean pre, int axis, int kind) {
            int shift = kindShift(pre, axis);
            kinds = (kinds & ~(3 << shift)) | (kind << shift);
        }
    }

    public static class RawTimelineEvent {
        public float timestamp;
        public List<String> events = new ArrayList<>();
    }

    public static class RawSoundEffect {
        public String effectName = "";
        public float timestamp;
    }

    public static class RawTexture {
        public String name = "";
        public String sourceFileName = "";
        public String hash = "";
        public int width;
        public int height;
        public int imageFormat;
        public byte[] data;
        public int unknownFlag = 1;
        public List<SubTexture> subTextures = new ArrayList<>();

        public static class SubTexture {
            public String hash = "";
            public int specularType;
            public int width;
            public int height;
            public int imageFormat;
            public byte[] data;
            public int unknownFlag = 1;
        }
    }

    public static class RawAnimationController {
        public String animationName = "";
        public String initialState = "";
        public List<RawControllerState> states = new ArrayList<>();
    }

    public static class RawControllerState {
        public String name = "";
        public Map<String, String> animations = new LinkedHashMap<>();
        public Map<String, String> transitions = new LinkedHashMap<>();
        public List<String> onEntry = new ArrayList<>();
        public List<String> onExit = new ArrayList<>();
        public List<String> soundEffects = new ArrayList<>();
        public float blendTransitionValue;
        public boolean blendViaShortestPath;
        public Map<Float, Float> blendTransitions = new LinkedHashMap<>();
    }

    public static class RawMetadata {
        public String name = "";
        public String tips = "";
        public String licenseType = "";
        public String licenseDescription = "";
        public List<Author> authors = new ArrayList<>();
        public Map<String, String> links = new LinkedHashMap<>();
        public List<RawImage> extraAvatars = new ArrayList<>();

        public static class Author {
            public String name = "";
            public String role = "";
            public String comment = "";
            public Map<String, String> contacts = new LinkedHashMap<>();
            public String avatar = "";
            public RawImage avatarImage;
        }
    }

    public static class RawImage {
        public String name = "";
        public byte[] data;
        public int width;
        public int height;
        public int format;
        public int unknownFlag = 1;
        public boolean isPng;
    }

    public static class RawProperties {
        public String sha256 = "";
        public float widthScale = 0.7f;
        public float heightScale = 0.7f;
        public String defaultTexture = "default";
        public String previewAnimation = "";
        public boolean isFree;
        public boolean renderLayersFirst;
        public boolean allCutout;
        public boolean disablePreviewRotation;
        public boolean guiNoLighting;
        public boolean mergeMultilineExpr = true;
        public String guiForeground = "";
        public String guiBackground = "";
        public List<RawImage> backgroundImages = new ArrayList<>();
        public Map<String, String> extraAnimations = new LinkedHashMap<>();
        public List<ExtraAnimationClassify> extraAnimationClassifies = new ArrayList<>();
        public List<ExtraAnimationButton> extraAnimationButtons = new ArrayList<>();
    }

    public static class ExtraAnimationClassify {
        public String id = "";
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    public static class ExtraAnimationButton {
        public String id = "";
        public String name = "";
        public String description = "";
        public List<ConfigForm> forms = new ArrayList<>();
    }

    public static class ConfigForm {
        public String type = "";
        public String title = "";
        public String description = "";
        public String defaultValue = "";
        public float step;
        public float min;
        public float max;
        public Map<String, String> labels = new LinkedHashMap<>();
    }

    public static class RawDataFile {
        public String hash = "";
        public byte[] data;

        public RawDataFile() {}

        public RawDataFile(String hash, byte[] data) {
            this.hash = hash;
            this.data = data;
        }
    }

    public static class RawLanguageFile {
        public String hash = "";
        public Map<String, String> data = new LinkedHashMap<>();

        public RawLanguageFile() {}

        public RawLanguageFile(String hash, Map<String, String> data) {
            this.hash = hash;
            this.data = data;
        }
    }

    public static class RawFooter {
        public int version = 65535;
        public int unkInt1 = 1;
        public String rand = "";
        public long time;
        public String extra = "";
        public int unkInt2;
    }
}
