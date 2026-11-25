package com.example.icuapk;

import java.util.ArrayList;
import java.util.List;

/** 被动超限提醒的事件状态：持续事件只报一次，恢复或换方向后才产生新事件。 */
final class MonitorAlarmTracker {
    private final int[] directions;

    MonitorAlarmTracker(int parameterCount) {
        directions = new int[Math.max(0, parameterCount)];
    }

    /** evaluated=false 表示无效帧/等待新帧，不算恢复，继续保持原事件。 */
    List<Integer> update(boolean[] high, boolean[] low, boolean[] evaluated) {
        ArrayList<Integer> events = new ArrayList<>();
        for (int i = 0; i < directions.length; i++) {
            if (evaluated != null && (i >= evaluated.length || !evaluated[i])) {
                continue;
            }
            boolean isHigh = high != null && i < high.length && high[i];
            boolean isLow = low != null && i < low.length && low[i];
            int direction = isHigh ? 1 : (isLow ? -1 : 0);
            if (direction == 0) {
                directions[i] = 0;
            } else if (directions[i] != direction) {
                directions[i] = direction;
                events.add(i);
            }
        }
        return events;
    }

    void clear() {
        for (int i = 0; i < directions.length; i++) {
            directions[i] = 0;
        }
    }
}
