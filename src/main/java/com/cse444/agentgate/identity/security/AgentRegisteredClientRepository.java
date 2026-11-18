package com.cse444.agentgate.identity.security;

import com.cse444.agentgate.identity.domain.AgentClient;
import com.cse444.agentgate.identity.domain.AgentClientRepository;
import com.cse444.agentgate.shared.config.AgentGateProperties;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Design Pattern: Adapter (FR2, FR8).
 * Presents the registered AI agents to Spring Authorization Server as OAuth2 clients that may use the
 * client_credentials grant, authenticating with client_secret_post or client_secret_basic against the
 * stored BCrypt hash. A disabled agent (kill switch) is not returned, so it cannot obtain new tokens.
 * Agents are registered through {@code POST /api/v1/agents}, so this repository is read-only.
 */
@Component
public class AgentRegisteredClientRepository implements RegisteredClientRepository {

    private final AgentClientRepository agentRepository;
    private final AgentGateProperties properties;

    public AgentRegisteredClientRepository(AgentClientRepository agentRepository, AgentGateProperties properties) {
        this.agentRepository = agentRepository;
        this.properties = properties;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException("Agents are registered through POST /api/v1/agents (FR2)");
    }

    @Override
    public RegisteredClient findById(String id) {
        UUID agentId;
        try {
            agentId = UUID.fromString(id);
        } catch (IllegalArgumentException notAnAgentId) {
            return null;
        }
        return agentRepository.findById(agentId)
                .filter(AgentClient::isEnabled)
                .map(this::toRegisteredClient)
                .orElse(null);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return agentRepository.findByClientId(clientId)
                .filter(AgentClient::isEnabled)
                .map(this::toRegisteredClient)
                .orElse(null);
    }

    private RegisteredClient toRegisteredClient(AgentClient agent) {
        RegisteredClient.Builder client = RegisteredClient.withId(agent.getId().toString())
                .clientId(agent.getClientId())
                .clientSecret(agent.getClientSecretHash())
                .clientName(agent.getAgentName())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(properties.security().accessTokenTtl())
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .build());
        for (String scope : agent.getScopes().split("[\\s,]+")) {
            if (!scope.isBlank()) {
                client.scope(scope);
            }
        }
        return client.build();
    }
}
