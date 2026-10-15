package com.streamarr.server.support;

import java.sql.Connection;
import java.sql.SQLException;

/** The transaction-local setting from which the audit trigger fills the audit columns. */
public final class AuditActorSetting {

  public static final String NAME = "streamarr.actor_id";
  public static final String REQUEST_WITHOUT_ACCOUNT = "request-without-account";

  private AuditActorSetting() {}

  /** Names {@code actor} in the transaction open on {@code connection}. */
  public static void nameActorIn(Connection connection, String actor) throws SQLException {
    try (var statement = connection.prepareStatement("SELECT set_config(?, ?, true)")) {
      statement.setString(1, NAME);
      statement.setString(2, actor);
      statement.execute();
    }
  }
}
