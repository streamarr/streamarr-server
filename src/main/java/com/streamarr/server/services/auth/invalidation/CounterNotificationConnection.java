package com.streamarr.server.services.auth.invalidation;

import java.util.List;

interface CounterNotificationConnection extends AutoCloseable {

  void listen();

  List<String> notifications(int pollTimeoutMs);

  @Override
  void close();
}
