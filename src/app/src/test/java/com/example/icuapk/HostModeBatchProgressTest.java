package com.example.icuapk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HostModeBatchProgressTest {
    @Test
    public void emptyConfigurationMaySendModeIdentifierOnly() {
        HostModeBatchProgress progress = new HostModeBatchProgress();

        assertTrue(progress.canSendCareMode());
        progress.record(true);

        assertTrue(progress.isComplete());
        assertEquals(1, progress.attempted);
        assertEquals(1, progress.enqueued);
    }

    @Test
    public void completeBatchIncludesEveryCommandAndModeIdentifier() {
        HostModeBatchProgress progress = new HostModeBatchProgress();
        for (int i = 0; i < 20; i++) {
            progress.record(true);
        }

        assertTrue(progress.canSendCareMode());
        progress.record(true);

        assertTrue(progress.isComplete());
        assertEquals(21, progress.attempted);
        assertEquals(21, progress.enqueued);
        assertEquals(0, progress.skipped);
    }

    @Test
    public void failedPreconditionBlocksModeIdentifier() {
        HostModeBatchProgress progress = new HostModeBatchProgress();
        progress.record(true);
        progress.record(false);
        progress.record(true);

        assertFalse(progress.canSendCareMode());
        progress.skip();

        assertFalse(progress.isComplete());
        assertEquals(4, progress.attempted);
        assertEquals(2, progress.enqueued);
        assertEquals(1, progress.skipped);
    }

    @Test
    public void skippedDependentSwitchAlsoBlocksCompleteState() {
        HostModeBatchProgress progress = new HostModeBatchProgress();
        progress.record(false);
        progress.skip();

        assertFalse(progress.canSendCareMode());
        assertFalse(progress.isComplete());
        assertEquals(2, progress.attempted);
        assertEquals(0, progress.enqueued);
        assertEquals(1, progress.skipped);
    }
}
