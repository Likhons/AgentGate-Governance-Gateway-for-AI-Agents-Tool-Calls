package com.cse444.agentgate.identity.security;

import com.cse444.agentgate.identity.domain.AgentClientRepository;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

/**
 * Shapes the access tokens Spring Authorization Server issues to agents (FR2, FR5):
 * <ul>
 *   <li>HS256-signed with the gateway's own secret, which the resource server's JwtDecoder validates;</li>
 *   <li>{@code client_id} and the agent's PII {@code clearance_level};</li>
 *   <li>{@code delegated_user}: the end user the agent acts for, taken from the token request. The hosting
 *       application requests one token per user session, and the gateway then rejects calls that name a
 *       different user, so a prompt-injected agent cannot switch users per call;</li>
 *   <li>the agent's registered scopes when the request asked for none (as before the migration).</li>
 * </ul>
 */
@Component
public class AgentAccessTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final AgentClientRepository agentRepository;

    public AgentAccessTokenCustomizer(AgentClientRepository agentRepository) {
        this.agentRepository = agentRepository;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        RegisteredClient client = context.getRegisteredClient();
        context.getJwsHeader().algorithm(MacAlgorithm.HS256).type("JWT");

        JwtClaimsSet.Builder claims = context.getClaims();
        claims.claim("client_id", client.getClientId());
        agentRepository.findByClientId(client.getClientId())
                .ifPresent(agent -> claims.claim("clearance_level", agent.getClearanceLevel().name()));
        if (context.getAuthorizedScopes().isEmpty() && !client.getScopes().isEmpty()) {
            claims.claim("scope", String.join(" ", client.getScopes()));
        }
        if (context.getAuthorizationGrant() instanceof OAuth2ClientCredentialsAuthenticationToken grant
                && grant.getAdditionalParameters().get("delegated_user") instanceof String delegatedUser
                && !delegatedUser.isBlank()) {
            claims.claim("delegated_user", delegatedUser.trim());
        }
    }
}
