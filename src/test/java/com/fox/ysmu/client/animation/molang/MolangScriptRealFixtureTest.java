package com.fox.ysmu.client.animation.molang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import software.bernie.geckolib3.core.molang.MolangParser;

/**
 * 拿**内置 {@code wine_fox} 包的真实脚本**当夹具执行解释器：{@code res/} 是 gitignored，
 * 测试只读 {@code src/test/resources/molang/} 下的 LF 副本。
 * <p>
 * 这类脚本是 YSM 实际在用的写法（嵌套闭包块 + {@code args[]} + {@code return} 穿透），
 * 比手写片段更能锁住解释器的行为。
 */
class MolangScriptRealFixtureTest {

    /** 内圈光环指示饱食度：4 层三元 + 嵌套块 + return。 */
    private static final String HALO = "molang/wine_fox_15_kluonoa/halo_battery_indicator.molang";
    /** sync 事件订阅脚本：局部变量 + 两层嵌套块 + 写回宿主变量。 */
    private static final String SYNC = "molang/wine_fox_15_kluonoa/eventsubscriber@sync.molang";

    @BeforeEach
    void clearGlobalVariables() {
        MolangParser.VARIABLES.clear();
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = MolangScriptRealFixtureTest.class.getResourceAsStream("/" + name)) {
            if (in != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                return out.toByteArray();
            }
        }
        // Gradle 的 test 工作目录就是项目根目录。
        return Files.readAllBytes(Paths.get("src/test/resources", name));
    }

    private static String script(String name) throws IOException {
        return new String(fixture(name), StandardCharsets.UTF_8);
    }

    private static double runHalo(double haloNo, double foodLevel, double lifeTime) throws IOException {
        FakeScope scope = new FakeScope().args(haloNo)
            .var("ysm.food_level", foodLevel)
            .var("q.life_time", lifeTime);
        return MolangScriptInterpreter.evaluate(script(HALO), scope);
    }

    /**
     * {@code halo_no} 的每一层都对应一条饱食度阈值，最内层再按 {@code q.life_time} 闪烁。
     * 参数在每次调用时重新读（{@code t.halo_no=args[0]} 是单次调用的局部变量）。
     */
    @Test
    void theHaloIndicatorFollowsHaloNumberAndFoodLevel() throws IOException {
        // halo_no=4 → food > 10
        assertEquals(1.0d, runHalo(4, 11, 0.0d), 0.0001d);
        assertEquals(0.0d, runHalo(4, 10, 0.0d), 0.0001d);
        // halo_no=3 → food > 8
        assertEquals(1.0d, runHalo(3, 9, 0.0d), 0.0001d);
        assertEquals(0.0d, runHalo(3, 8, 0.0d), 0.0001d);
        // halo_no=2 → food > 6
        assertEquals(1.0d, runHalo(2, 7, 0.0d), 0.0001d);
        assertEquals(0.0d, runHalo(2, 6, 0.0d), 0.0001d);
        // halo_no=1 且吃饱 → return 1
        assertEquals(1.0d, runHalo(1, 10, 0.0d), 0.0001d);
        // 其它 halo_no → return 0
        assertEquals(0.0d, runHalo(0, 20, 0.0d), 0.0001d);
        assertEquals(0.0d, runHalo(9, 20, 0.0d), 0.0001d);
    }

    /** 最内层闪烁分支：饿（<=6 且 >2）用 1 秒周期，很饿（<=2）用 0.5 秒周期。 */
    @Test
    void theHaloIndicatorBlinksWithThePeriodOfTheHungerLevel() throws IOException {
        // food=4：math.mod(life_time, 1) < 0.5
        assertEquals(1.0d, runHalo(1, 4, 0.2d), 0.0001d);
        assertEquals(1.0d, runHalo(1, 4, 0.49d), 0.0001d);
        assertEquals(0.0d, runHalo(1, 4, 0.5d), 0.0001d);
        assertEquals(0.0d, runHalo(1, 4, 0.8d), 0.0001d);
        // food=1：math.mod(life_time, 0.5) < 0.25
        assertEquals(1.0d, runHalo(1, 1, 0.1d), 0.0001d);
        assertEquals(0.0d, runHalo(1, 1, 0.3d), 0.0001d);
        // food=2 仍走"很饿"分支（<=2 且不 >2）
        assertEquals(1.0d, runHalo(1, 2, 0.1d), 0.0001d);
        // food=6 仍走"饿"分支（<=6 且 >2）
        assertEquals(1.0d, runHalo(1, 6, 0.2d), 0.0001d);
    }

    private static double runSync(FakeScope scope) throws IOException {
        return MolangScriptInterpreter.evaluate(script(SYNC), scope);
    }

    /**
     * sync 事件订阅脚本读 {@code args[0]}/{@code args[1]}，把 host 变量写回 scope。
     * 写回是解释器对渲染有意义的必要条件（{@code t.*} 局部变量不写回）。
     */
    @Test
    void theSyncEventSubscriberWritesTheHostVariable() throws IOException {
        FakeScope on = new FakeScope().args(0, 0);
        runSync(on);
        assertEquals(1.0d, on.writes.get("v.roaming.horn_checker"), 0.0001d);

        FakeScope off = new FakeScope().args(0, 1);
        runSync(off);
        assertEquals(0.0d, off.writes.get("v.roaming.horn_checker"), 0.0001d);

        FakeScope otherEvent = new FakeScope().args(1, 0);
        runSync(otherEvent);
        assertFalse(
            otherEvent.writes.containsKey("v.roaming.horn_checker"),
            "other events must not touch the variable: " + otherEvent.writes);
    }

    /** 假 scope：记录变量读 / 写、函数调用与调用参数。 */
    private static final class FakeScope implements MolangScriptInterpreter.MolangScriptScope {

        private final Map<String, Double> variables = new HashMap<>();
        private final List<Double> arguments = new ArrayList<>();
        final Map<String, Double> writes = new LinkedHashMap<>();

        FakeScope var(String name, double value) {
            this.variables.put(name, value);
            return this;
        }

        FakeScope args(double... values) {
            for (double value : values) {
                this.arguments.add(value);
            }
            return this;
        }

        @Override
        public double variableValue(String name) {
            Double value = this.variables.get(name);
            return value == null ? 0.0d : value;
        }

        @Override
        public void setVariableValue(String name, double value) {
            this.writes.put(name, value);
            this.variables.put(name, value);
        }

        @Override
        public double functionValue(String name, List<MolangScriptInterpreter.Argument> args) {
            return 0.0d;
        }

        @Override
        public double argument(int index) {
            return index >= 0 && index < this.arguments.size() ? this.arguments.get(index) : 0.0d;
        }

        @Override
        public int argumentCount() {
            return this.arguments.size();
        }

        @Override
        public String functionScript(String name) {
            return null;
        }
    }

    @Test
    void theFixturesAreTheOnesTheInterpreterIsTestedAgainst() throws IOException {
        assertTrue(script(HALO).contains("args[0]"), "halo fixture uses args");
        assertTrue(script(SYNC).contains("v.roaming.horn_checker"), "sync fixture writes a host variable");
    }
}
