package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SystemActor;
import java.sql.SQLException;
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
@DisplayName("Ultrafast Encoder Preset Migration Integration Tests")
class UltrafastEncoderPresetMigrationIT extends AbstractIntegrationTest {

  private static final UUID SERVER_ADMIN_ID =
      UUID.fromString("5f0c2a8e-7d41-4b6a-9c3e-1e2d3f4a5b6c");

  private final String schema =
      "ultrafast_migration_" + UUID.randomUUID().toString().replace("-", "");

  @Autowired private DataSource dataSource;
  @Autowired private DSLContext dsl;
  @Autowired private Flyway flyway;

  @BeforeEach
  void createServerSettingsBeforeMigration() throws SQLException {
    dsl.createSchema(schema).execute();
    execute(
        """
        CREATE TABLE server_settings (
          id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
          created_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
          created_by UUID,
          last_modified_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
          last_modified_by UUID,
          encoder_preset TEXT NOT NULL DEFAULT 'AUTOMATIC',
          CONSTRAINT chk_server_settings_encoder_preset CHECK (encoder_preset IN (
            'AUTOMATIC', 'ULTRAFAST', 'SUPERFAST', 'VERYFAST', 'FASTER', 'FAST', 'MEDIUM',
            'SLOW', 'SLOWER', 'VERYSLOW')))
        """,
        """
        CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON server_settings
          FOR EACH ROW EXECUTE FUNCTION public.fill_audit_columns()
        """);
  }

  @AfterEach
  void dropMigrationSchema() {
    dsl.dropSchema(schema).cascade().execute();
  }

  @Test
  @DisplayName("Should move a stored Ultrafast setting to Superfast when the migration runs")
  void shouldMoveStoredUltrafastSettingToSuperfastWhenMigrationRuns() throws SQLException {
    storePreset("ULTRAFAST");

    migrate();

    assertThat(storedPreset()).isEqualTo("SUPERFAST");
    assertThat(storedLastModifiedBy()).isEqualTo(SystemActor.ID.toString());
  }

  @Test
  @DisplayName("Should keep another stored preset and its modifier when the migration runs")
  void shouldKeepAnotherStoredPresetAndItsModifierWhenMigrationRuns() throws SQLException {
    storePreset("MEDIUM");

    migrate();

    assertThat(storedPreset()).isEqualTo("MEDIUM");
    assertThat(storedLastModifiedBy()).isEqualTo(SERVER_ADMIN_ID.toString());
  }

  @Test
  @DisplayName("Should reject a stored Ultrafast setting when the migration has run")
  void shouldRejectStoredUltrafastSettingWhenMigrationHasRun() throws SQLException {
    storePreset("MEDIUM");
    migrate();

    assertThatThrownBy(() -> storePresetInExistingRow("ULTRAFAST"))
        .isInstanceOfSatisfying(
            SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"))
        .hasMessageContaining("chk_server_settings_encoder_preset");
    assertThat(storedPreset()).isEqualTo("MEDIUM");
  }

  private void migrate() {
    migrations().load().migrate();
  }

  // Baselining at V092 applies only this change's migration to the hand-built table.
  private FluentConfiguration migrations() {
    return Flyway.configure()
        .configuration(flyway.getConfiguration())
        .schemas(schema)
        .defaultSchema(schema)
        .baselineVersion("92")
        .baselineOnMigrate(true)
        .target("93");
  }

  private void storePreset(String preset) throws SQLException {
    execute(
        actorIs(SERVER_ADMIN_ID),
        "INSERT INTO server_settings (encoder_preset) VALUES ('" + preset + "')");
  }

  private void storePresetInExistingRow(String preset) throws SQLException {
    execute(
        actorIs(SERVER_ADMIN_ID), "UPDATE server_settings SET encoder_preset = '" + preset + "'");
  }

  private static String actorIs(UUID actor) {
    return "SELECT set_config('streamarr.actor_id', '" + actor + "', true)";
  }

  private String storedPreset() throws SQLException {
    return storedText("encoder_preset");
  }

  private String storedLastModifiedBy() throws SQLException {
    return storedText("last_modified_by");
  }

  private String storedText(String column) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var row =
            statement.executeQuery("SELECT " + column + " FROM " + schema + ".server_settings")) {
      assertThat(row.next()).isTrue();
      return row.getString(column);
    }
  }

  private void execute(String... statements) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("SET LOCAL search_path = " + schema);
      for (var sql : statements) {
        statement.execute(sql);
      }

      connection.commit();
    }
  }
}
