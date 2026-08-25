package com.streamarr.server.fakes;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.library.LibraryMutationTransaction;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class FakeLibraryMutationTransaction implements LibraryMutationTransaction {

  private final List<Execution> executions = new ArrayList<>();

  @Override
  public <T> T execute(
      AuthenticatedIdentity identity, Intent.UnitIntent intent, Supplier<T> libraryMutation) {
    executions.add(new Execution(identity, intent));
    return libraryMutation.get();
  }

  public List<Execution> executions() {
    return List.copyOf(executions);
  }

  public record Execution(AuthenticatedIdentity identity, Intent.UnitIntent intent) {}
}
