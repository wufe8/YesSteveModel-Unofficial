package software.bernie.geckolib3.core.molang.functions;

import com.eliotlash.mclib.math.IValue;
import com.eliotlash.mclib.math.functions.Function;
import com.eliotlash.mclib.math.functions.classic.Exp;

/**
 * Stub for {@code query.is_item_name_any}.
 * Returns 0 (false) — full implementation requires access to the player's held item.
 */
public class IsItemNameAny extends Function {

    public IsItemNameAny(IValue[] values, String name) throws Exception {
        super(values, name);
    }

    @Override
    public int getRequiredArguments() {
        // Takes at least one argument (the item name pattern)
        return 1;
    }

    @Override
    public double get() {
        // TODO: implement when player context is available
        return 0;
    }
}
