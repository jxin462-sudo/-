package com.example.icuapk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class MonitorAlarmTrackerTest {
    private final boolean[] high = new boolean[2];
    private final boolean[] low = new boolean[2];
    private final boolean[] evaluated = {true, true};

    @Test
    public void continuousEventNotifiesOnlyOnce() {
        MonitorAlarmTracker tracker = new MonitorAlarmTracker(2);
        high[0] = true;
        assertEquals(Arrays.asList(0), tracker.update(high, low, evaluated));
        assertTrue(tracker.update(high, low, evaluated).isEmpty());
        assertTrue(tracker.update(high, low, evaluated).isEmpty());
    }

    @Test
    public void invalidFrameIsNotRecoveryAndDoesNotRetrigger() {
        MonitorAlarmTracker tracker = new MonitorAlarmTracker(2);
        high[0] = true;
        assertEquals(Arrays.asList(0), tracker.update(high, low, evaluated));
        evaluated[0] = false;
        high[0] = false;
        assertTrue(tracker.update(high, low, evaluated).isEmpty());
        evaluated[0] = true;
        high[0] = true;
        assertTrue(tracker.update(high, low, evaluated).isEmpty());
    }

    @Test
    public void realRecoveryOrDirectionChangeCreatesNewEvent() {
        MonitorAlarmTracker tracker = new MonitorAlarmTracker(2);
        high[0] = true;
        assertEquals(Arrays.asList(0), tracker.update(high, low, evaluated));
        high[0] = false;
        low[0] = true;
        assertEquals(Arrays.asList(0), tracker.update(high, low, evaluated));
        low[0] = false;
        assertTrue(tracker.update(high, low, evaluated).isEmpty());
        high[0] = true;
        assertEquals(Arrays.asList(0), tracker.update(high, low, evaluated));
    }

    @Test
    public void anotherParameterEventDoesNotRepeatExistingAlarm() {
        MonitorAlarmTracker tracker = new MonitorAlarmTracker(2);
        high[0] = true;
        assertEquals(Arrays.asList(0), tracker.update(high, low, evaluated));
        low[1] = true;
        assertEquals(Arrays.asList(1), tracker.update(high, low, evaluated));
        assertTrue(tracker.update(high, low, evaluated).isEmpty());
        tracker.clear();
        assertEquals(Arrays.asList(0, 1), tracker.update(high, low, evaluated));
    }
}
