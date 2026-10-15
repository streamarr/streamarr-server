package com.streamarr.server.services.auth.architecturefixture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RequestServiceThreads {

  public Thread start(Runnable work) {
    return Thread.ofVirtual().start(work);
  }

  public ExecutorService executor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
