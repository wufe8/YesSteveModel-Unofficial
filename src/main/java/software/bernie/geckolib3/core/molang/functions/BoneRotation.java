package software.bernie.geckolib3.core.molang.functions;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

public class BoneRotation extends Function {

    public BoneRotation(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 1;
    }

    @Override
    public double get() {
        MolangPhysicsBridge.Physics physics = MolangPhysicsBridge.physics;
        return physics == null ? 0.0D : physics.boneRotation((int) getArg(0), axis());
    }

    private char axis() {
        return this.name == null || this.name.isEmpty() ? 'x' : this.name.charAt(this.name.length() - 1);
    }
}
