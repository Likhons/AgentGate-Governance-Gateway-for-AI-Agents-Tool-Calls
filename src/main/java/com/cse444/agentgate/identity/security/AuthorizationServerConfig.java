package com.cse444.agentgate.identity.security;

import com.cse444.agentgate.shared.config.AgentGateProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadata;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Spring Authorization Server for agent identity (FR2, C3).
 * <p>
 * Issues agent access tokens with the OAuth2 client_credentials grant at {@code /oauth2/token} and serves
 * token introspection, revocation and the authorization server metadata (RFC 8414). It runs before the
 * resource-server chain in {@code SecurityConfig}, which validates the same HS256 tokens on every
 * gateway, MCP and admin request. Clients are the registered agents
 * ({@link AgentRegisteredClientRepository}); token contents come from {@link AgentAccessTokenCustomizer};
 * signing uses the gateway's {@code JwtEncoder} bean, so no RSA key pair or JWK Set is published.
 */
@Configuration
public class AuthorizationServerConfig {

    static final String ISSUER = "https://agentgate.cse444.com";

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .oauth2AuthorizationServer(authorizationServer -> {
                    http.securityMatcher(authorizationServer.getEndpointsMatcher());
                    authorizationServer.authorizationServerMetadataEndpoint(metadataEndpoint -> metadataEndpoint
                            .authorizationServerMetadataCustomizer(AuthorizationServerConfig::advertiseAgentGrantOnly));
                })
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * The RFC 8414 metadata lists only what a registered agent can use: the client_credentials grant with
     * client_secret_basic or client_secret_post (FR2). Spring Authorization Server's defaults also list the
     * authorization-code, refresh-token and token-exchange grants, JWT and mTLS client authentication,
     * PKCE, DPoP and a JWK Set; no agent is registered for those, and the HS256 signing key is secret, so
     * no JWK Set is published. The authorization endpoint and response types stay because the metadata
     * format requires them.
     */
    static void advertiseAgentGrantOnly(OAuth2AuthorizationServerMetadata.Builder metadata) {
        List<String> clientSecretMethods = List.of(
                ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue(),
                ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue());
        metadata.claims(claims -> {
            claims.put(OAuth2AuthorizationServerMetadataClaimNames.GRANT_TYPES_SUPPORTED,
                    List.of(AuthorizationGrantType.CLIENT_CREDENTIALS.getValue()));
            claims.put(OAuth2AuthorizationServerMetadataClaimNames.TOKEN_ENDPOINT_AUTH_METHODS_SUPPORTED,
                    clientSecretMethods);
            claims.put(OAuth2AuthorizationServerMetadataClaimNames.INTROSPECTION_ENDPOINT_AUTH_METHODS_SUPPORTED,
                    clientSecretMethods);
            claims.put(OAuth2AuthorizationServerMetadataClaimNames.REVOCATION_ENDPOINT_AUTH_METHODS_SUPPORTED,
                    clientSecretMethods);
            claims.remove(OAuth2AuthorizationServerMetadataClaimNames.JWKS_URI);
            claims.remove(OAuth2AuthorizationServerMetadataClaimNames.CODE_CHALLENGE_METHODS_SUPPORTED);
            claims.remove(OAuth2AuthorizationServerMetadataClaimNames.TLS_CLIENT_CERTIFICATE_BOUND_ACCESS_TOKENS);
            claims.remove(OAuth2AuthorizationServerMetadataClaimNames.DPOP_SIGNING_ALG_VALUES_SUPPORTED);
        });
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().issuer(ISSUER).build();
    }

    @Bean
    public OAuth2AuthorizationService agentAuthorizationService(AgentGateProperties properties) {
        return new ExpiringOAuth2AuthorizationService(properties.security().accessTokenTtl());
    }
}
