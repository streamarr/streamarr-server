package com.streamarr.server.services.streaming;

import com.streamarr.server.services.concurrency.MutexFactory;
import com.streamarr.server.services.concurrency.MutexFactoryProvider;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class StreamSessionCleanupService implements StreamSessionCleanup {

  private final StreamingService streamingService;
  private final StreamSessionLifecycleTransactions lifecycleTransactions;
  private final MutexFactory<UUID> cleanupMutex;
  private final StreamSessionTransactionRetry transactionRetry;

  public StreamSessionCleanupService(
      StreamingService streamingService,
      StreamSessionLifecycleTransactions lifecycleTransactions,
      StreamSessionTransactionRetry transactionRetry,
      MutexFactoryProvider mutexFactoryProvider) {
    this.streamingService = streamingService;
    this.lifecycleTransactions = lifecycleTransactions;
    this.transactionRetry = transactionRetry;
    this.cleanupMutex = mutexFactoryProvider.getMutexFactory();
  }

  @Override
  public void cleanup(UUID streamSessionId) {
    var lock = cleanupMutex.getMutex(streamSessionId);
    lock.lock();
    try {
      if (!streamingService.terminateRuntime(streamSessionId)) {
        return;
      }
      transactionRetry.execute(() -> lifecycleTransactions.deleteTerminating(streamSessionId));
    } finally {
      lock.unlock();
    }
  }
}
