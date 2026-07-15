package software.bernie.geckolib3.core.molang;

import java.util.function.DoubleSupplier;

import com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime;

public class ScopedMolangVariable extends LazyVariable {

    private DoubleSupplier fallbackSupplier;

    public ScopedMolangVariable(String name, double value) {
        this(name, () -> value);
    }

    public ScopedMolangVariable(String name, DoubleSupplier fallbackSupplier) {
        super(name, fallbackSupplier);
        this.fallbackSupplier = fallbackSupplier;
    }

    @Override
    public void set(double value) {
        if (!MolangPhysicsRuntime.setVariable(getName(), value)) {
            if ("v.roaming.bq_eye".equals(getName()) || "v.roaming.bq_mouth".equals(getName())) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-SCOPED] set({}) = {} – NO FRAME CTX, fallback=() -> {}", getName(), value, (int) value);
            }
            this.fallbackSupplier = () -> value;
            super.set(value);
        } else {
            if ("v.roaming.bq_eye".equals(getName()) || "v.roaming.bq_mouth".equals(getName())) {
                com.fox.ysmu.ysmu.LOG.info("[YSMU-SCOPED] set({}) = {} – FRAME CTX ALIVE, written to ScopeState", getName(), value);
            }
        }
    }

    @Override
    public void set(DoubleSupplier valueSupplier) {
        this.fallbackSupplier = valueSupplier;
        super.set(valueSupplier);
    }

    @Override
    public double get() {
        double fallback = fallbackSupplier.getAsDouble();
        double result = MolangPhysicsRuntime.getVariable(getName(), fallback);
        if ("v.roaming.bq_eye".equals(getName()) || "v.roaming.bq_mouth".equals(getName())) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-SCOPED] get({}) = {} (fallback={})", getName(), result, fallback);
        }
        return result;
    }
}
