package com.cse444.agentgate.shared.error;

/**
 * Thrown when a gateway call names an end user other than the one its agent token was issued for
 * (FR2, QA1). The end user is bound to the token, so a prompt-injected agent cannot act for a
 * different customer by changing the request body or the X-On-Behalf-Of-User header.
 */
public class DelegatedUserMismatchException extends RuntimeException {

    private final String tokenDelegatedUser;
    private final String requestedDelegatedUser;

    public DelegatedUserMismatchException(String tokenDelegatedUser, String requestedDelegatedUser) {
        super("The request names delegated user '" + requestedDelegatedUser + "', but this agent token was issued "
                + (tokenDelegatedUser == null ? "without a delegated user" : "for '" + tokenDelegatedUser + "'")
                + "; request a token for that user instead (FR2)");
        this.tokenDelegatedUser = tokenDelegatedUser;
        this.requestedDelegatedUser = requestedDelegatedUser;
    }

    public String getTokenDelegatedUser() {
        return tokenDelegatedUser;
    }

    public String getRequestedDelegatedUser() {
        return requestedDelegatedUser;
    }
}
