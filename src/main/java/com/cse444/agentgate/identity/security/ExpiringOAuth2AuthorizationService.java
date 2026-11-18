package com.cse444.agentgate.identity.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

import java.time.Duration;

/**
 * Bounded, expiring store for the authorizations Spring Authorization Server records when it issues
 * client_credentials tokens (FR2). The framework's default in-memory service keeps every authorization
 * forever; here an entry lives as long as its access token (plus a minute), so memory stays bounded at
 * 100 calls per second while token introspection and revocation keep working for live tokens.
 * Agent tokens are self-contained JWTs: the resource server validates them without this store, and the
 * agent kill switch (FR8) is what stops a compromised agent immediately.
 */
public class ExpiringOAuth2AuthorizationService implements OAuth2AuthorizationService {

    private static final long MAX_AUTHORIZATIONS = 100_000;

    private final Cache<String, OAuth2Authorization> authorizationsById;
    private final Cache<String, String> authorizationIdsByAccessToken;

    public ExpiringOAuth2AuthorizationService(Duration accessTokenTimeToLive) {
        Duration retention = accessTokenTimeToLive.plusMinutes(1);
        this.authorizationsById = Caffeine.newBuilder()
                .maximumSize(MAX_AUTHORIZATIONS)
                .expireAfterWrite(retention)
                .build();
        this.authorizationIdsByAccessToken = Caffeine.newBuilder()
                .maximumSize(MAX_AUTHORIZATIONS)
                .expireAfterWrite(retention)
                .build();
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        authorizationsById.put(authorization.getId(), authorization);
        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
        if (accessToken != null) {
            authorizationIdsByAccessToken.put(accessToken.getToken().getTokenValue(), authorization.getId());
        }
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        authorizationsById.invalidate(authorization.getId());
        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
        if (accessToken != null) {
            authorizationIdsByAccessToken.invalidate(accessToken.getToken().getTokenValue());
        }
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return authorizationsById.getIfPresent(id);
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        if (tokenType != null && !OAuth2TokenType.ACCESS_TOKEN.equals(tokenType)) {
            return null;
        }
        String authorizationId = authorizationIdsByAccessToken.getIfPresent(token);
        return authorizationId != null ? authorizationsById.getIfPresent(authorizationId) : null;
    }
}
