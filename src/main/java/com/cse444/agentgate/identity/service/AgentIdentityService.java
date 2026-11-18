package com.cse444.agentgate.identity.service;

import com.cse444.agentgate.identity.domain.AgentClient;
import com.cse444.agentgate.identity.domain.AgentClientRepository;
import com.cse444.agentgate.identity.dto.AgentClientResponse;
import com.cse444.agentgate.identity.dto.RegisterAgentRequest;
import com.cse444.agentgate.shared.config.AgentGateProperties;
import com.cse444.agentgate.shared.domain.AgentCallIdentity;
import com.cse444.agentgate.shared.domain.ClearanceLevel;
import com.cse444.agentgate.shared.event.KillSwitchActivatedEvent;
import com.cse444.agentgate.shared.util.FailSafeCaches;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Manages OAuth2 Client Credentials authentication for AI Agents (FR2)
 * and instant Agent Kill Switches (FR8).
 */
@Service
public class AgentIdentityService {

    private record CachedAgentSnapshot(String clientId, boolean enabled, ClearanceLevel clearanceLevel) {}

    private final AgentClientRepository agentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final AgentGateProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Last-known-good agent state (QA4): refreshed in the background every policy refresh interval and
     * served from cache while PostgreSQL is unreachable, for at most the low-risk fail-safe TTL.
     * Kill switches on this instance invalidate the entry immediately (FR8).
     */
    private final LoadingCache<String, CachedAgentSnapshot> agentCache;

    public AgentIdentityService(
            AgentClientRepository agentRepository,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            AgentGateProperties properties,
            ApplicationEventPublisher eventPublisher
    ) {
        this.agentRepository = agentRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.agentCache = FailSafeCaches.lastKnownGood(
                2000,
                properties.policy().refreshInterval(),
                properties.policy().lowRiskFailsafeTtl(),
                clientId -> agentRepository.findByClientId(clientId)
                        .map(found -> new CachedAgentSnapshot(
                                found.getClientId(), found.isEnabled(), found.getClearanceLevel()))
                        .orElse(null)
        );
    }

    @Transactional
    public AgentClientResponse registerAgent(RegisterAgentRequest request) {
        if (agentRepository.existsByClientId(request.clientId())) {
            throw new IllegalArgumentException("Agent client_id already exists: " + request.clientId());
        }
        AgentClient agent = new AgentClient(
                UUID.randomUUID(),
                request.clientId(),
                passwordEncoder.encode(request.clientSecret()),
                request.agentName(),
                request.owningTeam(),
                request.clearanceLevel(),
                request.scopes()
        );
        AgentClient saved = agentRepository.save(agent);
        agentCache.invalidate(saved.getClientId());
        return AgentClientResponse.from(saved);
    }

    /**
     * Issues an OAuth2 access token using RFC 6749 section 4.4 Client Credentials Grant (FR2).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> issueClientCredentialsToken(
            String clientId,
            String clientSecret,
            String requestedDelegatedUser
    ) {
        AgentClient agent = agentRepository.findByClientId(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Invalid OAuth2 client_id or client_secret"));

        if (!agent.isEnabled()) {
            throw new IllegalStateException("Agent '" + clientId + "' is disabled by Kill Switch (FR8)");
        }

        if (!passwordEncoder.matches(clientSecret, agent.getClientSecretHash())) {
            throw new IllegalArgumentException("Invalid OAuth2 client_id or client_secret");
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.security().accessTokenTtl());

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .issuer("https://agentgate.cse444.com")
                .subject(agent.getClientId())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("client_id", agent.getClientId())
                .claim("clearance_level", agent.getClearanceLevel().name())
                .claim("scope", agent.getScopes());

        if (requestedDelegatedUser != null && !requestedDelegatedUser.isBlank()) {
            claimsBuilder.claim("delegated_user", requestedDelegatedUser);
        }

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String tokenValue = jwtEncoder.encode(JwtEncoderParameters.from(header, claimsBuilder.build())).getTokenValue();

        return Map.of(
                "access_token", tokenValue,
                "token_type", "Bearer",
                "expires_in", properties.security().accessTokenTtl().toSeconds(),
                "scope", agent.getScopes(),
                "clearance_level", agent.getClearanceLevel().name()
        );
    }

    /**
     * Not transactional on purpose: the cached path must not need a database connection (QA4).
     */
    public AgentCallIdentity verifyAndResolveAgentIdentity(String clientId, String delegatedUserId) {
        CachedAgentSnapshot agent = getCachedSnapshot(clientId);
        if (agent == null) {
            throw new EntityNotFoundException("Registered agent client not found: " + clientId);
        }
        if (!agent.enabled()) {
            throw new IllegalStateException("Agent '" + clientId + "' is disabled by Kill Switch (FR8)");
        }
        return new AgentCallIdentity(agent.clientId(), delegatedUserId, agent.clearanceLevel());
    }

    public boolean isAgentEnabled(String clientId) {
        CachedAgentSnapshot agent = getCachedSnapshot(clientId);
        return agent != null && agent.enabled();
    }

    private CachedAgentSnapshot getCachedSnapshot(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        return agentCache.get(clientId);
    }

    @Transactional(readOnly = true)
    public Page<AgentClientResponse> listAgents(Pageable pageable) {
        return agentRepository.findAll(pageable).map(AgentClientResponse::from);
    }

    /**
     * FR8 Kill Switch: Instantly disables an agent and publishes KillSwitchActivatedEvent
     * so pending approvals requested by this agent are cancelled immediately.
     */
    @Transactional
    public AgentClientResponse disableAgent(String clientId, String reason, String adminActor) {
        AgentClient agent = agentRepository.findByClientId(clientId)
                .orElseThrow(() -> new EntityNotFoundException("Agent not found: " + clientId));
        agent.disable();
        AgentClient saved = agentRepository.save(agent);
        agentCache.invalidate(clientId);

        eventPublisher.publishEvent(new KillSwitchActivatedEvent(
                KillSwitchActivatedEvent.Scope.AGENT,
                clientId,
                reason,
                adminActor,
                Instant.now()
        ));
        return AgentClientResponse.from(saved);
    }

    @Transactional
    public AgentClientResponse enableAgent(String clientId) {
        AgentClient agent = agentRepository.findByClientId(clientId)
                .orElseThrow(() -> new EntityNotFoundException("Agent not found: " + clientId));
        agent.enable();
        AgentClient saved = agentRepository.save(agent);
        agentCache.invalidate(saved.getClientId());
        return AgentClientResponse.from(saved);
    }
}
