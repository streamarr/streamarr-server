package com.streamarr.server.services.auth.invalidation;

import java.sql.SQLException;

interface CounterNotificationConnectionSource {

  CounterNotificationConnection open() throws SQLException;
}
