package com.goev.dashboard;

import java.util.List;

import com.goev.mqtt.EvStatusMessage;
import com.goev.mqtt.EvcsStatusMessage;

/** Initial hydration payload for a freshly opened dashboard, before the next live heartbeat arrives over STOMP. */
public record DashboardSnapshot(
        List<EvStatusMessage> evs,
        List<EvcsStatusMessage> evcsStations,
        List<AssignmentNotification> recentAssignments
) {
}
