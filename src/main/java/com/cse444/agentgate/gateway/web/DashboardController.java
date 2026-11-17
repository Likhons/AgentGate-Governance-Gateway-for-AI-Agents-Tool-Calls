package com.cse444.agentgate.gateway.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the clean, minimal AgentGate Control Plane & Gateway Simulator UI at "/".
 */
@Controller
public class DashboardController {

    @GetMapping({"/", "/index.html"})
    public String renderDashboard() {
        return "dashboard";
    }
}
