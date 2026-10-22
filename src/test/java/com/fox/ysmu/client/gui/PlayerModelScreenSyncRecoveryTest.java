package com.fox.ysmu.client.gui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlayerModelScreenSyncRecoveryTest {

    @Test
    void emptyListDoesNotRestartAnActiveSync() {
        assertFalse(ModelSyncRecoveryPolicy.shouldRequest(true, true, false));
        assertFalse(ModelSyncRecoveryPolicy.shouldRequest(true, true, true));
    }

    @Test
    void idleEmptyListCanRequestRecoveryOncePerScreen() {
        assertTrue(ModelSyncRecoveryPolicy.shouldRequest(true, false, false));
        assertFalse(ModelSyncRecoveryPolicy.shouldRequest(true, false, true));
        assertFalse(ModelSyncRecoveryPolicy.shouldRequest(false, false, false));
        assertFalse(ModelSyncRecoveryPolicy.shouldRequest(false, true, false));
    }
}
