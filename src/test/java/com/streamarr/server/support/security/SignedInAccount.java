package com.streamarr.server.support.security;

import com.streamarr.server.config.security.StreamarrAuthenticationToken;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.services.auth.TokenScope;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Runs test work on the calling thread as if an Account had signed in, then restores the thread.
 */
public final class SignedInAccount {

  private SignedInAccount() {}

  public static void runSignedInAs(UUID accountId, Runnable work) {
    callSignedInAs(
        accountId,
        () -> {
          work.run();
          return null;
        });
  }

  public static <T> T callSignedInAs(UUID accountId, Supplier<T> work) {
    var previous = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    var identity = AuthenticatedIdentityFixture.accountScopedBuilder().accountId(accountId).build();
    context.setAuthentication(
        new StreamarrAuthenticationToken(
            identity, null, List.of(new SimpleGrantedAuthority(TokenScope.ACCOUNT.authority()))));
    SecurityContextHolder.setContext(context);
    try {
      return work.get();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }
}
