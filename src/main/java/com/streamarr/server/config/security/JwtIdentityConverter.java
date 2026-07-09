package com.streamarr.server.config.security;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Parses identity claims once per request. The token's single scope becomes its only authority;
 * nesting happens through the role hierarchy, never through extra authorities. Household roles are
 * deliberately NOT authorities — the authorization facade checks them (ADR 0015).
 */
@Component
public class JwtIdentityConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    var identity = AuthenticatedIdentity.fromJwt(jwt);
    var authorities = List.of(new SimpleGrantedAuthority(identity.scope().authority()));
    return new StreamarrAuthenticationToken(identity, jwt, authorities);
  }
}
