@org.springframework.modulith.ApplicationModule(
        displayName = "MCP Front-Door Gateway Orchestrator (FR1-FR10)",
        allowedDependencies = {
                "shared",
                "registry :: service",
                "registry :: domain",
                "registry :: dto",
                "registry :: adapter",
                "identity :: service",
                "policy :: service",
                "policy :: dto",
                "guard :: service",
                "guard :: dto",
                "approval :: service",
                "approval :: dto"
        }
)
package com.cse444.agentgate.gateway;
