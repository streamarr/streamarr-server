package com.streamarr.server.services.auth.invalidation;

import java.sql.SQLException;
import java.util.List;

interface CounterNotificationConnection extends AutoCloseable {

  void listen(String channel) throws SQLException;

  List<String> notifications(int pollTimeoutMs) throws SQLException;

  @Override
  void close() throws SQLException;
}
