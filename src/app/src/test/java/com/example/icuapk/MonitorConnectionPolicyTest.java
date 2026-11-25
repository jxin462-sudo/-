package com.example.icuapk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MonitorConnectionPolicyTest {

    @Test
    public void sameMonitorReconnectRetainsValuesButDeviceChangeClearsThem() {
        assertFalse(MonitorConnectionPolicy.shouldClearDisplayValues("AA:01", "AA:01"));
        assertTrue(MonitorConnectionPolicy.shouldClearDisplayValues("AA:01", "BB:02"));
        assertTrue(MonitorConnectionPolicy.shouldClearDisplayValues("", "AA:01"));
        assertTrue(MonitorConnectionPolicy.shouldClearDisplayValues("AA:01", null));
    }

    @Test
    public void retainedValueNeedsNewValidFrameAfterCurrentReady() {
        assertTrue(MonitorConnectionPolicy.isParamFresh(true, true, 0, 1000L, 1000L));
        assertTrue(MonitorConnectionPolicy.isParamFresh(true, true, 6, 1000L, 1001L));
        assertFalse(MonitorConnectionPolicy.isParamFresh(true, true, 0, 1000L, 999L));
        assertFalse(MonitorConnectionPolicy.isParamFresh(false, true, 0, 1000L, 1001L));
        assertFalse(MonitorConnectionPolicy.isParamFresh(true, false, 0, 1000L, 1001L));
        assertFalse(MonitorConnectionPolicy.isParamFresh(true, true, 7, 1000L, 1001L));
        assertFalse(MonitorConnectionPolicy.isParamFresh(true, true, 0, 0L, 1001L));
    }
}
