package com.streamarr.server.support;

import com.streamarr.server.config.persistence.AuditActorSource;
import com.streamarr.server.config.persistence.SecurityAuditActorSource;
import com.streamarr.server.domain.audit.AuditActor;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Names the test fixture actor in a transaction that a test begins outside any request or system
 * work, so fixtures can write audited rows. The id differs from the System actor's and from an
 * empty actor, so tests of either cannot pass by accident. Writes outside a transaction still name
 * no actor and are rejected, as in production.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestFixtureAuditActorConfiguration {

  public static final UUID TEST_FIXTURE_ACTOR_ID =
      UUID.fromString("7e57f1c7-0000-4000-8000-00000000a0d1");

  /**
   * Names the test fixture actor in the transaction open on {@code connection}, for fixtures that
   * write audited rows through their own JDBC connection.
   */
  public static void nameTestFixtureActorIn(Connection connection) throws SQLException {
    try (var statement = connection.prepareStatement("SELECT set_config(?, ?, true)")) {
      statement.setString(1, "streamarr.actor_id");
      statement.setString(2, TEST_FIXTURE_ACTOR_ID.toString());
      statement.execute();
    }
  }

  @Bean
  AuditColumnFixtures auditColumnFixtures(DSLContext dsl, TransactionTemplate transactionTemplate) {
    return new AuditColumnFixtures(dsl, transactionTemplate);
  }

  @Bean
  @Primary
  AuditActorSource testFixtureAuditActorSource() {
    var production = new SecurityAuditActorSource();
    return () ->
        production
            .currentActor()
            .or(() -> Optional.of(new AuditActor.Account(TEST_FIXTURE_ACTOR_ID)));
  }
}
