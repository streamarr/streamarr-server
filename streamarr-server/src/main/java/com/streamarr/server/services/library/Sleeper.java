package com.streamarr.server.services.library;

import java.time.Duration;

@FunctionalInterface
public interface Sleeper {

  void sleep(Duration duration) throws InterruptedException;
}
