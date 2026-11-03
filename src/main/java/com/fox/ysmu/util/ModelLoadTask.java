package com.fox.ysmu.util;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Gives each model parse exactly one terminal outcome, including recoverable resource failures. */
public final class ModelLoadTask {

    private ModelLoadTask() {}

    public static void run(BooleanSupplier parse, Runnable success, Consumer<Throwable> failure, Runnable finished) {
        try {
            boolean loaded;
            try {
                loaded = parse.getAsBoolean();
            } catch (Exception | OutOfMemoryError | StackOverflowError | LinkageError error) {
                // ExecutorService.submit stores Errors in its Future. These tasks have no
                // Future consumer, so report them here rather than silently losing a model.
                failure.accept(error);
                return;
            }
            if (loaded) {
                success.run();
            } else {
                failure.accept(null);
            }
        } finally {
            // ThreadDeath and other fatal VM errors propagate, but never strand progress.
            finished.run();
        }
    }
}
