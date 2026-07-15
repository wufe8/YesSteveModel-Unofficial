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

    private MolangInstructionExecutor() {}

    public static void execute(String instructions) {
        if (StringUtils.isBlank(instructions)) {
            return;
        }
        // DEBUG: trace timeline execution
        if (instructions.contains("bq_eye") || instructions.contains("bq_mouth")) {
            com.fox.ysmu.ysmu.LOG.info("[YSMU-TL] EXEC received: {}...", instructions.substring(0, Math.min(instructions.length(), 200)));
        }
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
                        IValue val = parser.parseExpression(valueExpr);
                        if (val != null) {
                            double d = val.get();
                            // DEBUG
                            if ("v.bq_eye".equals(target) || "v.bq_mouth".equals(target)) {
                                com.fox.ysmu.ysmu.LOG.info("[YSMU-TL] EXEC {} = {} (from '{}')", target, d, trimmed.substring(0, Math.min(trimmed.length(), 120)));
                            }
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
                        }
                    } catch (Exception e) {
                        warnOnce(trimmed, e);
                    }
                }
                continue;
            }
            // Not an assignment — evaluate as a normal expression
            try {
                IValue result = parser.parseExpression(trimmed);
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

    private static void warnOnce(String instruction, Exception e) {
        if (WARNED_INSTRUCTIONS.add(instruction)) {
            ysmu.LOG
                .warn("Failed to execute OpenYSM timeline Molang instruction '{}': {}", instruction, e.getMessage());
        }
    }
}
