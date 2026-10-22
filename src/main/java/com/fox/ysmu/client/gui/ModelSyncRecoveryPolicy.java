package com.fox.ysmu.client.gui;

/** Recovery decision independent of GuiScreen initialization and its OpenGL dependencies. */
final class ModelSyncRecoveryPolicy {

    private ModelSyncRecoveryPolicy() {}

    static boolean shouldRequest(boolean modelsEmpty, boolean syncInProgress, boolean alreadyRequested) {
        // No models have been applied yet during a healthy sync; do not invalidate its generation.
        return modelsEmpty && !syncInProgress && !alreadyRequested;
    }
}
