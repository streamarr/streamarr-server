package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

public final class ProcessTestSupport {

  private ProcessTestSupport() {}

  public static void awaitCompletion(Process process, Duration timeout, String description)
      throws InterruptedException {
    assertThat(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)).as(description).isTrue();
  }
}
