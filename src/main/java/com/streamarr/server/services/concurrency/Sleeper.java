package com.streamarr.server.services.concurrency;

import java.time.Duration;

@FunctionalInterface
public interface Sleeper {

  void sleep(Duration duration) throws InterruptedException;
}
