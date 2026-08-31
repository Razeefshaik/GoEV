package com.goev.dashboard;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class DashboardController {

    private final DashboardBroadcaster broadcaster;

    public DashboardController(DashboardBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping("/")
    public String dashboard() {
        return "dashboard";
    }

    @GetMapping("/api/dashboard/snapshot")
    @ResponseBody
    public DashboardSnapshot snapshot() {
        return new DashboardSnapshot(
                List.copyOf(broadcaster.evSnapshot()),
                List.copyOf(broadcaster.evcsSnapshot()),
                broadcaster.recentAssignmentsSnapshot());
    }
}
