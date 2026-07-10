package com.streamarr.server.services.auth.invalidation;

interface CounterNotificationBackoff {

  void sleep(long backoffMs);
}
