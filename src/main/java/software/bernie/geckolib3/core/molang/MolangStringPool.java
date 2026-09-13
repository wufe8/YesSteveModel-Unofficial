package software.bernie.geckolib3.core.molang;

import java.util.HashMap;
import java.util.Map;

public final class MolangStringPool {

    public static final int EMPTY_ID = 0;

    /**
     * 池 id 的起始值。
     *
     * <p>字符串字面量在解析期被池化成整数，模型作者会把它们和普通数字混在同一个调用里
     * （{@code q.debug_output('level', 1)}）。如果从 1 开始编号，数字 {@code 1} 会和第一个
     * 池化的字符串撞车，于是"这个参数是字符串还是数字"就永远判不准。从一个模型表达式
     * 不可能算到的偏移开始编号后，{@link #isStringId(int)} 才是可靠的。</p>
     */
    public static final int STRING_ID_BASE = 1_000_000;

    private static final Map<String, Integer> IDS = new HashMap<>();
    private static final Map<Integer, String> VALUES = new HashMap<>();
    private static int nextId = STRING_ID_BASE;

    private MolangStringPool() {}

    public static synchronized int intern(String value) {
        if (value == null || value.isEmpty()) {
            return EMPTY_ID;
        }
        Integer existing = IDS.get(value);
        if (existing != null) {
            return existing;
        }
        int id = nextId++;
        IDS.put(value, id);
        VALUES.put(id, value);
        return id;
    }

    public static synchronized String get(int id) {
        return VALUES.get(id);
    }

    /**
     * 该数值是否可能是一个池化字符串 id（即落在 {@link #STRING_ID_BASE} 之后的区间）。
     *
     * <p>只做区间判断、不加锁，所以可以每帧调用；要想拿到真正的字符串仍须
     * {@link #get(int)} 返回非 {@code null}。反过来，一个模型表达式刚好算出
     * {@code >= 1000000} 的数值时会被误判成字符串 —— 这在实际模型里不会发生
     * （位置、时间、速度、血量都在几个数量级以下）。</p>
     */
    public static boolean isStringId(int id) {
        return id >= STRING_ID_BASE;
    }
}

