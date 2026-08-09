package software.bernie.geckolib3.core.molang.functions;

/**
 * Host-mod-injected physics bridge for the vendored {@code ysm.*} physics
 * functions ({@link BonePosition}, {@link BoneRotation}, {@link BoneScale},
 * {@link FirstOrder}, {@link SecondOrder}). Inverted control — this vendored
 * file has no compile-time dependency on mod code (same pattern as
 * {@code MolangParser.ysmFunctionRegistrar}). Set once at mod init;
 * read-only afterwards; {@code null} → the physics functions return 0.0
 * (graceful fallback, matching the no-frame-context behavior).
 */
public final class MolangPhysicsBridge {

    /** 与 {@code MolangPhysicsRuntime} 的物理方法一一对应（由宿主 mod 实现）。 */
    public interface Physics {
        double bonePosition(int nameId, char axis);
        double boneRotation(int nameId, char axis);
        double boneScale(int nameId, char axis);
        double firstOrder(int nameId, double input, double response);
        double secondOrder(int nameId, double input, double frequency, double coefficient, double response);
    }

    public static volatile Physics physics = null;

    private MolangPhysicsBridge() {}
}
