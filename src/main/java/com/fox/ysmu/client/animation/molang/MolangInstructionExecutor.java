package com.fox.ysmu.client.animation.molang;

import com.eliotlash.mclib.math.IValue;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang3.StringUtils;

import com.fox.ysmu.ysmu;

import software.bernie.geckolib3.core.molang.MolangException;
import software.bernie.geckolib3.core.molang.MolangParser;
import software.bernie.geckolib3.resource.GeckoLibCache;

public final class MolangInstructionExecutor {

    private static final Set<String> WARNED_INSTRUCTIONS = Collections
        .newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    /** Cache for parsed Molang expressions — avoids re-parsing same string every frame. */
    private static final ConcurrentHashMap<String, IValue> EXPRESSION_CACHE = new ConcurrentHashMap<>();
    /** Tracks whether any instructions have executed since the last frame.
     *  Used by OpenYsmPlayerControllerRuntime to skip prepareFrameVariables step 1
     *  when no timeline changes occurred (the RuntimeState values are already current). */
    private static boolean dirtySinceLastFrame = false;

    private MolangInstructionExecutor() {}

    /** Returns true if instructions have executed since last reset. */
    public static boolean hasPendingChanges() {
        return dirtySinceLastFrame;
    }

    /** Resets the dirty flag — call at the start of each render frame. */
    public static void resetDirtyFlag() {
        dirtySinceLastFrame = false;
    }

    public static void execute(String instructions) {
        if (StringUtils.isBlank(instructions)) {
            return;
        }
        dirtySinceLastFrame = true;
        MolangParser parser = GeckoLibCache.getInstance().parser;
        Iterable<String> statements;
        try {
            statements = MolangParser.splitStatements(instructions);
        } catch (MolangException e) {
            warnOnce(instructions, e);
            return;
        }
        for (String statement : statements) {
            String trimmed = statement.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // Check if this is an assignment (e.g. "v.roaming.h=0" or "v.hold=5")
            int eqIdx = findAssignmentOperator(trimmed);
            if (eqIdx > 0) {
                String target = trimmed.substring(0, eqIdx).trim();
                String valueExpr = trimmed.substring(eqIdx + 1).trim();
                if (target.startsWith("v.")) {
                    try {
                        IValue val = parseCached(parser, valueExpr);
                        if (val != null) {
                            double d = val.get();
                            // Write through MolangParser.VARIABLES so ScopedMolangVariable
                            // (if it exists) sees the change. This works because
                            // LazyVariable.set() / ScopedMolangVariable.set() will
                            // propagate to MolangPhysicsRuntime when inside a render frame.
                            MolangParser.VARIABLES.computeIfAbsent(target,
                                k -> new software.bernie.geckolib3.core.molang.LazyVariable(k, 0)).set(d);
                            // Also write directly to MolangPhysicsRuntime so that
                            // syncToRuntimeState() can see the value even when no
                            // ScopedMolangVariable was previously registered for this key
                            // (e.g. v.bq_eye set by pre_parallel7's timeline).
                            com.fox.ysmu.client.animation.molang.MolangPhysicsRuntime.setVariable(target, d);
                            // Log v.qh timeline variable assignments for debugging
                            if (("v.qh".equals(target) || "v.qh2".equals(target)
                                || "v.jump".equals(target) || "v.random".equals(target))
                                && com.fox.ysmu.Config.DEBUG_CONTROLLER) {
                                ysmu.LOG.info("[YSMU-TL-SET] {} = {} (from '{}')", target, d, valueExpr);
                            }
                        }
                    } catch (Exception e) {
                        warnOnce(trimmed, e);
                    }
                }
                continue;
            }
            // Not an assignment — evaluate as a normal expression
            try {
                IValue result = parseCached(parser, trimmed);
                if (result == null) {
                    continue;
                }
                result.get();
            } catch (Exception e) {
                warnOnce(trimmed, e);
            }
        }
    }

    /**
     * Locates the first {@code =} character that acts as an assignment
     * operator (ignoring {@code ==}, {@code !=}, {@code <=}, {@code >=},
     * and {@code ?=}/{@code ?:}).
     */
    private static int findAssignmentOperator(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=') {
                // Skip two-character operators: ==, !=, <=, >=
                if (i > 0) {
                    char prev = text.charAt(i - 1);
                    if (prev == '=' || prev == '!' || prev == '<' || prev == '>') {
                        continue;
                    }
                }
                // Skip if this is part of ?= or ?: operator
                if (i > 0 && text.charAt(i - 1) == '?') {
                    continue;
                }
                if (i + 1 < text.length() && (text.charAt(i + 1) == '=')) {
                    continue;
                }
                return i;
            }
        }
        return -1;
    }

    public static void clearWarnings() {
        WARNED_INSTRUCTIONS.clear();
    }

    /** Cache lookup: returns cached parsed expression, or parses on first access. */
    private static IValue parseCached(MolangParser parser, String expr) {
        IValue cached = EXPRESSION_CACHE.get(expr);
        if (cached != null) return cached;
        try {
            IValue parsed = parser.parseExpression(expr);
            if (parsed != null) {
                EXPRESSION_CACHE.put(expr, parsed);
            }
            return parsed;
        } catch (MolangException e) {
            return null;
        }
    }

    /** Clear parsed expression cache — call when models are reloaded. */
    public static void clearCache() {
        EXPRESSION_CACHE.clear();
    }

    private static void warnOnce(String instruction, Exception e) {
        if (WARNED_INSTRUCTIONS.add(instruction)) {
            ysmu.LOG
                .warn("Failed to execute OpenYSM timeline Molang instruction '{}': {}", instruction, e.getMessage());
        }
    }
}
