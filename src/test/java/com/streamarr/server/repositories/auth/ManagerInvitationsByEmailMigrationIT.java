package com.streamarr.server.repositories.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SystemActor;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Manager Invitations By Email Migration Integration Tests")
class ManagerInvitationsByEmailMigrationIT extends AbstractIntegrationTest {

  private static final UUID INVITER_ID = UUID.fromString("5f0c2a8e-7d41-4b6a-9c3e-1e2d3f4a5b6c");
  private static final UUID ALEX_ID = UUID.fromString("0b7e4c1d-2a3f-4e5d-8c9b-1a2b3c4d5e6f");

  private final String schema =
      "manager_email_migration_" + UUID.randomUUID().toString().replace("-", "");

  @Autowired private DataSource dataSource;
  @Autowired private DSLContext dsl;
  @Autowired private Flyway flyway;

  @BeforeEach
  void createInvitationsBeforeMigration() throws SQLException {
    dsl.createSchema(schema).execute();
    execute(
        """
        CREATE TABLE profile_manager_invitation (
          id UUID PRIMARY KEY,
          created_on TIMESTAMP WITH TIME ZONE NOT NULL,
          created_by UUID,
          last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
          last_modified_by UUID,
          profile_id UUID,
          inviter_account_id UUID,
          recipient_account_id UUID,
          recipient_email TEXT NOT NULL,
          status public.profile_manager_invitation_status NOT NULL DEFAULT 'PENDING',
          expires_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW() + INTERVAL '7 days',
          decided_at TIMESTAMP WITH TIME ZONE,
          invalidation_reason TEXT,
          CONSTRAINT chk_pm_invitation_pending_has_parties CHECK (
            status <> 'PENDING'
              OR (profile_id IS NOT NULL
                  AND inviter_account_id IS NOT NULL
                  AND recipient_account_id IS NOT NULL)))
        """,
        """
        CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON profile_manager_invitation
          FOR EACH ROW EXECUTE FUNCTION public.fill_audit_columns()
        """);
  }

  @AfterEach
  void dropMigrationSchema() {
    dsl.dropSchema(schema).cascade().execute();
  }

  @Test
  @DisplayName(
      "Should keep only the newest pending invitation when two wait for one Profile under one"
          + " address in different cases")
  void
      shouldKeepOnlyTheNewestPendingInvitationWhenTwoWaitForOneProfileUnderOneAddressInDifferentCases()
          throws SQLException {
    var older = storePending("2026-11-01T12:00:00Z", "mara@example.com");
    var newer = storePending("2026-11-02T12:00:00Z", "Mara@Example.com");

    migrate();

    assertThat(statusOf(older)).isEqualTo("INVALIDATED");
    assertThat(statusOf(newer)).isEqualTo("PENDING");
    assertThat(modifierOf(older)).isEqualTo(SystemActor.ID.toString());
  }

  @Test
  @DisplayName("Should let a pending invitation name no Account when the migration has run")
  void shouldLetAPendingInvitationNameNoAccountWhenTheMigrationHasRun() throws SQLException {
    migrate();

    var unanswerable = UUID.randomUUID();
    execute(
        actorIs(INVITER_ID),
        """
        INSERT INTO profile_manager_invitation
          (id, created_on, profile_id, inviter_account_id, recipient_email)
        VALUES ('%s', NOW(), '%s', '%s', 'nobody@example.com')
        """
            .formatted(unanswerable, ALEX_ID, INVITER_ID));

    assertThat(statusOf(unanswerable)).isEqualTo("PENDING");
  }

  private UUID storePending(String createdOn, String email) throws SQLException {
    var id = UUID.randomUUID();
    execute(
        actorIs(INVITER_ID),
        """
        INSERT INTO profile_manager_invitation
          (id, created_on, profile_id, inviter_account_id, recipient_account_id, recipient_email)
        VALUES ('%s', '%s', '%s', '%s', '%s', '%s')
        """
            .formatted(id, createdOn, ALEX_ID, INVITER_ID, UUID.randomUUID(), email));
    return id;
  }

  private void migrate() {
    migrations().load().migrate();
  }

  // Baselining at V134 applies only this change's migration to the hand-built table.
  private FluentConfiguration migrations() {
    return Flyway.configure()
        .configuration(flyway.getConfiguration())
        .schemas(schema)
        .defaultSchema(schema)
        .baselineVersion("134")
        .baselineOnMigrate(true)
        .target("135");
  }

  private static String actorIs(UUID actor) {
    return "SELECT set_config('streamarr.actor_id', '" + actor + "', true)";
  }

  private String statusOf(UUID invitationId) throws SQLException {
    return storedText(invitationId, "status");
  }

  private String modifierOf(UUID invitationId) throws SQLException {
    return storedText(invitationId, "last_modified_by");
  }

  private String storedText(UUID invitationId, String column) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var row =
            statement.executeQuery(
                "SELECT %s FROM %s.profile_manager_invitation WHERE id = '%s'"
                    .formatted(column, schema, invitationId))) {
      assertThat(row.next()).isTrue();
      return row.getString(column);
    }
  }

  private void execute(String... statements) throws SQLException {
    var all = new ArrayList<>(List.of("SET LOCAL search_path = " + schema));
    all.addAll(List.of(statements));
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      for (var sql : all) {
        statement.execute(sql);
      }

      connection.commit();
    }
  }
}
