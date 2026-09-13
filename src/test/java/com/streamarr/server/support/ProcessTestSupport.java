package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class ProcessTestSupport {

  private ProcessTestSupport() {}

  public static void awaitCompletion(Process process, Duration timeout, String description)
      throws InterruptedException {
    try {
      assertThat(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS))
          .as(description)
          .isTrue();
    } finally {
      terminate(process.toHandle());
    }
  }

  private static void terminate(ProcessHandle process) {
    process.children().forEach(ProcessTestSupport::terminate);
    process.destroyForcibly();
    process.onExit().orTimeout(5, TimeUnit.SECONDS).join();
  }
}
