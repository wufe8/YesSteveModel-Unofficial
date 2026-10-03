package com.fox.ysmu.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * 专用服务器侧的签名约束：{@code com.fox.ysmu.network.message} 下的类里，**消息类本身**两侧都会
 * 构造/序列化，**serverbound 的 {@code Handler}** 由 {@code registerServerboundMessages} 以
 * {@code Side.SERVER} 注册、服务端也会实例化 —— 这两类只要字段/方法描述符里出现
 * {@code net.minecraft.client.*} 就会去加载被 LaunchClassLoader 拒载的客户端类，专用服直接起不来
 * （1.9a1-08 的实际事故）。
 *
 * <p>clientbound 的 {@code Handler} 现在不在服务端注册（见
 * {@code NetworkHandler.registerClientbound}：专用服只往 codec 登记判别号），所以它们已经不会
 * 在服务端被加载；本测试仍然把它们一起扫掉，是一条**便宜的统一规则**：签名不出现客户端类型，
 * 将来万一注册方式再变也不会重新踩坑。</p>
 *
 * <p>方法体里引用客户端类是允许的（延迟解析，服务端执行不到那个分支），{@code S2CSetDebugOverlay}
 * 就是这种写法的参照；所以这里只扫描述符，不扫常量池里的内部名。</p>
 */
class NetworkMessageSideSafetyTest {

    private static final String CLIENT_PACKAGE = "net/minecraft/client/";
    private static final String RESOURCE_DIR = "com/fox/ysmu/network/message";

    @Test
    void messageClassesKeepClientTypesOutOfTheirDescriptors() throws Exception {
        List<Path> classes = compiledMessageClasses();
        assertFalse(classes.isEmpty(), "no compiled message classes found next to " + RESOURCE_DIR);

        List<String> offenders = new ArrayList<>();
        for (Path cls : classes) {
            for (String descriptor : descriptorsIn(cls)) {
                if (descriptor.contains(CLIENT_PACKAGE)) {
                    offenders.add(
                        RESOURCE_DIR + "/" + cls.getFileName() + "  ->  " + descriptor);
                }
            }
        }
        Collections.sort(offenders);
        assertEquals(
            Collections.emptyList(),
            offenders,
            "a dedicated server loads and instantiates every message handler during init, so a client "
                + "type in a field/method descriptor there aborts startup with NoClassDefFoundError. "
                + "Move the client work into a method body (or a client-only helper) instead.");
    }

    /** Compiled {@code .class} files of the message package, wherever the build put them. */
    private static List<Path> compiledMessageClasses() throws Exception {
        URL url = NetworkMessageSideSafetyTest.class.getClassLoader().getResource(RESOURCE_DIR);
        assertFalse(url == null, "missing classpath entry for " + RESOURCE_DIR);
        Path dir = Paths.get(url.toURI());
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> out = new ArrayList<>();
            walk.filter(p -> p.getFileName()
                .toString()
                .endsWith(".class"))
                .forEach(out::add);
            out.sort(Comparator.comparing(Path::toString));
            return out;
        }
    }

    /**
     * Constant-pool {@code Utf8} entries that are type descriptors: method descriptors start with
     * {@code (}, class/array descriptors start with {@code L} or {@code [}. Plain internal names
     * (body references such as {@code net/minecraft/client/Minecraft}) deliberately do not match.
     */
    private static List<String> descriptorsIn(Path classFile) throws IOException {
        List<String> descriptors = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(Files.newInputStream(classFile))) {
            if (in.readInt() != 0xCAFEBABE) {
                return descriptors;
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int count = in.readUnsignedShort();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1: { // Utf8
                        String text = in.readUTF();
                        if (isDescriptor(text)) {
                            descriptors.add(text);
                        }
                        break;
                    }
                    case 7: // Class
                    case 8: // String
                    case 16: // MethodType
                    case 19: // Module
                    case 20: // Package
                        in.skipBytes(2);
                        break;
                    case 15: // MethodHandle
                        in.skipBytes(3);
                        break;
                    case 3: // Integer
                    case 4: // Float
                    case 9: // Fieldref
                    case 10: // Methodref
                    case 11: // InterfaceMethodref
                    case 12: // NameAndType
                    case 17: // Dynamic
                    case 18: // InvokeDynamic
                        in.skipBytes(4);
                        break;
                    case 5: // Long
                    case 6: // Double
                        in.skipBytes(8);
                        i++; // eight-byte constants take two pool slots
                        break;
                    default:
                        throw new IOException("unexpected constant pool tag " + tag + " in " + classFile);
                }
            }
        }
        return descriptors;
    }

    private static boolean isDescriptor(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        if (first == '(') {
            return true;
        }
        return (first == 'L' || first == '[') && text.endsWith(";");
    }
}
