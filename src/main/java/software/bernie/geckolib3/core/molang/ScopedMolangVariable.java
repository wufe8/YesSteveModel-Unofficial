package software.bernie.geckolib3.core.molang;

import java.util.function.DoubleSupplier;

/**
 * A {@link LazyVariable} for {@code v.} Molang variables that scopes reads
 * and writes to the per-(player, model) frame context.
 * <p>
 * {@link #get()} delegates to the host-mod {@link ScopedVariableStore} hook,
 * which looks up the value in the current frame's per-model scope. If no frame
 * context is active or the variable has not been set in the current scope, the
 * fallback is {@code 0.0} — a neutral default. This prevents cross-model
 * contamination via the global {@link MolangParser#VARIABLES} map: each
 * model's {@code v.} variables live in their own scope, isolated by
 * {@code (playerId, modelId)} key.
 * <p>
 * {@link #set(double)} writes through to the frame's scope when a frame
 * context is active. Outside a frame context (e.g. during model
 * initialisation), the value is stored in the global {@code VARIABLES} map
 * via the parent {@link LazyVariable#set(double)} for compatibility, but
 * {@link #get()} will never read it — cross-model reads always default to 0.
 * <p>
 * The store is injected by the host mod through the static {@link #store}
 * field (inverted control — this vendored file has no dependency on mod code).
 * A {@code null} store means "no frame scope active": reads/writes fall back
 * to the global {@code VARIABLES} map.
 */
public class ScopedMolangVariable extends LazyVariable {

    /**
     * Host-mod-injected hook: per-(player, model) {@code v.} variable scope
     * storage. Set once at mod init; read-only afterwards. {@code null} → the
     * variable falls back to the global VARIABLES map (LazyVariable behavior).
     */
    public interface ScopedVariableStore {
        boolean contains(String name);
        double get(String name, double fallback);
        boolean set(String name, double value);
    }

    public static volatile ScopedVariableStore store = null;

    public ScopedMolangVariable(String name, double value) {
        super(name, () -> value);
    }

    public ScopedMolangVariable(String name, DoubleSupplier fallbackSupplier) {
        super(name, fallbackSupplier);
    }

    @Override
    public void set(double value) {
        ScopedVariableStore s = store;
        if (s == null || !s.set(getName(), value)) {
            super.set(value);
        }
    }

    @Override
    public void set(DoubleSupplier valueSupplier) {
        super.set(valueSupplier);
    }

    @Override
    public double get() {
        // During a render frame, read from the per-(player, model) scope to
        // keep v. variables isolated across models. If the variable has not
        // been set in this model's scope yet, fall through to super.get()
        // which returns the global VARIABLES value (set by an earlier set()
        // outside a frame context, e.g. model init or test evaluation).
        // Roaming/wheel variables are injected into the scope every frame by
        // the host mod, so they always take the frame path.
        ScopedVariableStore s = store;
        if (s != null && s.contains(getName())) {
            return s.get(getName(), 0.0);
        }
        return super.get();
    }
}
