package com.streamarr.server.services.auth;

import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PortableIdentityMutationService {

  private final PortableIdentityTransactionExecutor transactionExecutor;

  public <T> T execute(Supplier<T> mutation) {
    return transactionExecutor.execute(mutation);
  }

  public void execute(Runnable mutation) {
    transactionExecutor.execute(mutation);
  }
}
