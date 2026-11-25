package com.example.icuapk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PdfDownloadRequestTrackerTest {
    @Test
    public void newerRequestSupersedesOlderRequest() {
        PdfDownloadRequestTracker tracker = new PdfDownloadRequestTracker();
        long first = tracker.begin();
        long second = tracker.begin();

        assertTrue(second > first);
        assertFalse(tracker.isActive(first));
        assertTrue(tracker.isActive(second));
    }

    @Test
    public void staleRequestCannotFinishCurrentRequest() {
        PdfDownloadRequestTracker tracker = new PdfDownloadRequestTracker();
        long first = tracker.begin();
        long second = tracker.begin();

        assertFalse(tracker.finish(first));
        assertTrue(tracker.isActive(second));
        assertTrue(tracker.finish(second));
    }

    @Test
    public void finalResultCanOnlyBePublishedOnce() {
        PdfDownloadRequestTracker tracker = new PdfDownloadRequestTracker();
        long request = tracker.begin();

        assertTrue(tracker.finish(request));
        assertFalse(tracker.finish(request));
        assertFalse(tracker.isActive(request));
    }

    @Test
    public void invalidRequestIdsNeverPublish() {
        PdfDownloadRequestTracker tracker = new PdfDownloadRequestTracker();
        tracker.begin();

        assertFalse(tracker.isActive(0L));
        assertFalse(tracker.finish(-1L));
    }
}
