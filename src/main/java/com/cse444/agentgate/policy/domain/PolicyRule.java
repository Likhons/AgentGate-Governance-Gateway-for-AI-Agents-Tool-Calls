package com.cse444.agentgate.policy.domain;

import com.cse444.agentgate.shared.domain.PolicyEffect;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Versioned Policy Rule entity (FR3, FR9).
 * Editing a policy rule creates a new version while deactivating older versions of the same ruleName.
 */
@Entity
@Table(
        name = "policy_rules",
        uniqueConstraints = @UniqueConstraint(name = "uq_policy_rule_name_version", columnNames = {"rule_name", "version"})
)
public class PolicyRule {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "rule_name", nullable = false, length = 140)
    private String ruleName;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "agent_pattern", nullable = false, length = 120)
    private String agentPattern;

    @Column(name = "tool_pattern", nullable = false, length = 120)
    private String toolPattern;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false, length = 16)
    private PolicyEffect effect;

    @Column(name = "conditions_json", nullable = false, columnDefinition = "TEXT")
    private String conditionsJson;

    @Column(name = "dry_run", nullable = false)
    private boolean dryRun;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_by", nullable = false, length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PolicyRule() {}

    public PolicyRule(
            UUID id,
            String ruleName,
            int version,
            int priority,
            String agentPattern,
            String toolPattern,
            PolicyEffect effect,
            String conditionsJson,
            boolean dryRun,
            boolean active,
            String description,
            String createdBy
    ) {
        this.id = id != null ? id : UUID.randomUUID();
        this.ruleName = ruleName;
        this.version = version;
        this.priority = priority;
        this.agentPattern = agentPattern;
        this.toolPattern = toolPattern;
        this.effect = effect;
        this.conditionsJson = conditionsJson != null ? conditionsJson : "[]";
        this.dryRun = dryRun;
        this.active = active;
        this.description = description;
        this.createdBy = createdBy != null ? createdBy : "admin";
        this.createdAt = Instant.now();
    }

    public void deactivate() {
        this.active = false;
    }

    public UUID getId() { return id; }
    public String getRuleName() { return ruleName; }
    public int getVersion() { return version; }
    public int getPriority() { return priority; }
    public String getAgentPattern() { return agentPattern; }
    public String getToolPattern() { return toolPattern; }
    public PolicyEffect getEffect() { return effect; }
    public String getConditionsJson() { return conditionsJson; }
    public boolean isDryRun() { return dryRun; }
    public boolean isActive() { return active; }
    public String getDescription() { return description; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
