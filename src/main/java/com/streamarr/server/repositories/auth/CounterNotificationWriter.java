package com.streamarr.server.repositories.auth;

public interface CounterNotificationWriter {

  void write(CounterNotificationPayload payload);
}
