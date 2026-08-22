package com.streamarr.server.services.library;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.Intent;
import java.util.function.Supplier;

public interface LibraryMutationTransaction {

  <T> T execute(AuthenticatedIdentity identity, Intent<?> intent, Supplier<T> libraryMutation);

  default void execute(AuthenticatedIdentity identity, Intent<?> intent, Runnable libraryMutation) {
    execute(
        identity,
        intent,
        () -> {
          libraryMutation.run();
          return null;
        });
  }
}
