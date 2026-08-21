package com.streamarr.server.fakes;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.library.LibraryMutationTransaction;
import java.util.function.Supplier;

public class FakeLibraryMutationTransaction implements LibraryMutationTransaction {

  @Override
  public <T> T execute(
      AuthenticatedIdentity identity, Intent<?> intent, Supplier<T> libraryMutation) {
    return libraryMutation.get();
  }
}
