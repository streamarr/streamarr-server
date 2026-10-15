package com.streamarr.server.support.security;

import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import java.util.UUID;
import java.util.function.Supplier;
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
    var identity = AuthenticatedIdentityFixture.accountScopedBuilder().accountId(accountId).build();
    SecurityContextHolder.setContext(StreamarrSecurityContextFactory.contextFor(identity));
    try {
      return work.get();
    } finally {
      SecurityContextHolder.setContext(previous);
    }
  }
}
