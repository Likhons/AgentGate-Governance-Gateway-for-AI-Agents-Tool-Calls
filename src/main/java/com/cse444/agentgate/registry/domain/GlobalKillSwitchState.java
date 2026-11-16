package com.cse444.agentgate.registry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persists global kill-switch state such as disabling all write tools instantly (FR8).
 */
@Entity
@Table(name = "global_kill_switch_state")
public class GlobalKillSwitchState {

    public static final String ALL_WRITE_TOOLS_KEY = "ALL_WRITE_TOOLS";

    @Id
    @Column(name = "switch_key", nullable = false, length = 64)
    private String switchKey;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GlobalKillSwitchState() {
    }

    public GlobalKillSwitchState(String switchKey, boolean active, String reason, String updatedBy) {
        this.switchKey = switchKey;
        this.active = active;
        this.reason = reason;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public void update(boolean active, String reason, String updatedBy) {
        this.active = active;
        this.reason = reason;
        this.updatedBy = updatedBy;
        this.updatedAt = Instant.now();
    }

    public String getSwitchKey() {
        return switchKey;
    }

    public boolean isActive() {
        return active;
    }

    public String getReason() {
        return reason;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
