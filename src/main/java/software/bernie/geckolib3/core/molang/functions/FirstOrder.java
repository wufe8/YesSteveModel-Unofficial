package software.bernie.geckolib3.core.molang.functions;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

public class FirstOrder extends Function {

    public FirstOrder(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        return 2;
    }

    @Override
    public double get() {
        int nameId = (int) this.getArg(0);
        double input = this.getArg(1);
        double response = this.args.length >= 3 ? this.getArg(2) : 1.0D;
        MolangPhysicsBridge.Physics physics = MolangPhysicsBridge.physics;
        return physics == null ? 0.0D : physics.firstOrder(nameId, input, response);
    }
}
