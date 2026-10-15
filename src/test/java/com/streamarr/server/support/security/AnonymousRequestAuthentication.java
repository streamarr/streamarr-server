package com.streamarr.server.support.security;

import java.util.List;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** The authentication Spring Security gives a request made without a signed-in account. */
public final class AnonymousRequestAuthentication {

  private AnonymousRequestAuthentication() {}

  public static AnonymousAuthenticationToken token() {
    return new AnonymousAuthenticationToken(
        "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
  }
}
