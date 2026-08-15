package com.streamarr.server.config.security;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
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

  public StreamarrAuthenticationToken(
      AuthenticatedIdentity identity,
      Jwt token,
      Collection<? extends GrantedAuthority> authorities) {
    super(authorities);
    this.identity = identity;
    this.token = token;
    setAuthenticated(true);
  }

  @Override
  public AuthenticatedIdentity getPrincipal() {
    return identity;
  }

  @Override
  public Object getCredentials() {
    return token;
  }
}
