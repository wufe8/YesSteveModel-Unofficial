package software.bernie.geckolib3.core.molang.functions;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;

public class SecondOrder extends Function {

    public SecondOrder(IValue[] values, String name) throws Exception {
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
        double frequency = this.args.length >= 3 ? this.getArg(2) : 1.0D;
        double coefficient = this.args.length >= 4 ? this.getArg(3) : 1.0D;
        double response = this.args.length >= 5 ? this.getArg(4) : 1.0D;
        MolangPhysicsBridge.Physics physics = MolangPhysicsBridge.physics;
        return physics == null ? 0.0D : physics.secondOrder(nameId, input, frequency, coefficient, response);
    }
}
