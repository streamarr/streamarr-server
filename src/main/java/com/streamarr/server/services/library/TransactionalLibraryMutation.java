package com.streamarr.server.services.library;

import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
class TransactionalLibraryMutation implements LibraryMutationTransaction {

  private final AuthorizationService authorizationService;
  private final TransactionTemplate transactionTemplate;

  @Override
  public <T> T execute(
      AuthenticatedIdentity identity, Intent<?> intent, Supplier<T> libraryMutation) {
    return transactionTemplate.execute(
        _ -> {
          authorizationService.requireAllowed(identity, intent);
          return libraryMutation.get();
        });
  }
}
