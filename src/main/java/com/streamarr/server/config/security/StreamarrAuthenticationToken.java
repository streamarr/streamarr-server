package com.streamarr.server.config.security;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.RequestAuthorizationStateResolver.AuthorizationState;
import java.util.Collection;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

// AbstractAuthenticationToken.equals already compares getPrincipal() (identity) and
// getCredentials() (token), so the added fields participate; a separate override is redundant.
@SuppressWarnings("java:S2160")
public class StreamarrAuthenticationToken extends AbstractAuthenticationToken {

  private final transient AuthenticatedIdentity identity;
  private final transient Jwt token;
  private final transient Object requestAuthorizationMutexKey = new Object();
  private transient AuthorizationState requestAuthorizationState;

  /**
   * Creates an authenticated token with the specified identity, JWT, and authorities.
   *
   * @param identity the authenticated identity
   * @param token the JWT associated with the authentication
   * @param authorities the authorities granted to the identity
   */
  public StreamarrAuthenticationToken(
      AuthenticatedIdentity identity,
      Jwt token,
      Collection<? extends GrantedAuthority> authorities) {
    super(authorities);
    this.identity = identity;
    this.token = token;
    setAuthenticated(true);
  }

  /**
   * Retrieves the authenticated identity represented by this token.
   *
   * @return the authenticated identity
   */
  @Override
  public AuthenticatedIdentity getPrincipal() {
    return identity;
  }

  /**
   * Retrieves the JWT used as the token's credentials.
   *
   * @return the JWT credentials
   */
  @Override
  public Object getCredentials() {
    return token;
  }

  /**
   * Retrieves the current request authorization state.
   *
   * @return the request authorization state
   */
  public AuthorizationState getRequestAuthorizationState() {
    return requestAuthorizationState;
  }

  /**
   * Provides the mutex key used to coordinate request authorization.
   *
   * @return the request authorization mutex key
   */
  public Object getRequestAuthorizationMutexKey() {
    return requestAuthorizationMutexKey;
  }

  /**
   * Updates the request authorization state.
   *
   * @param requestAuthorizationState the new request authorization state
   */
  public void setRequestAuthorizationState(AuthorizationState requestAuthorizationState) {
    this.requestAuthorizationState = requestAuthorizationState;
  }
}
