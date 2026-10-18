package com.streamarr.server.repositories.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
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
@DisplayName("Probe Results Before Version 4 Migration Integration Tests")
class ProbeResultsBeforeVersion4MigrationIT extends AbstractIntegrationTest {

  private static final UUID PROBED_AT_VERSION_1 = UUID.randomUUID();
  private static final UUID PROBED_AT_VERSION_3 = UUID.randomUUID();
  private static final UUID PROBED_AT_VERSION_4 = UUID.randomUUID();
  private static final UUID REQUESTED_AT_VERSION_4 = UUID.randomUUID();

  private final String schema =
      "probe_version_4_migration_" + UUID.randomUUID().toString().replace("-", "");

  @Autowired private DataSource dataSource;
  @Autowired private DSLContext dsl;
  @Autowired private Flyway flyway;

  // The stream table cascades from its result as V070 declares it, and the request table keeps
  // the columns V072 declares.
  @BeforeEach
  void createProbeResultsBeforeMigration() throws SQLException {
    dsl.createSchema(schema).execute();
    execute(
        """
        CREATE TABLE media_file_container_info (
          media_file_id UUID PRIMARY KEY,
          source_size BIGINT NOT NULL,
          source_modified_epoch_second BIGINT NOT NULL,
          source_modified_nanos INTEGER NOT NULL,
          probe_version INTEGER NOT NULL CHECK (probe_version > 0))
        """,
        """
        CREATE TABLE media_file_probe_task_request (
          media_file_id UUID PRIMARY KEY,
          source_size BIGINT NOT NULL,
          source_modified_epoch_second BIGINT NOT NULL,
          source_modified_nanos INTEGER NOT NULL,
          probe_version INTEGER NOT NULL CHECK (probe_version > 0))
        """,
        """
        CREATE TABLE media_file_stream_info (
          media_file_id UUID NOT NULL
            REFERENCES media_file_container_info (media_file_id) ON DELETE CASCADE,
          stream_index INTEGER NOT NULL,
          PRIMARY KEY (media_file_id, stream_index))
        """);
    storeResult(PROBED_AT_VERSION_1, 1);
    storeResult(PROBED_AT_VERSION_3, 3);
    storeResult(PROBED_AT_VERSION_4, 4);
    storeResult(REQUESTED_AT_VERSION_4, 3);
    execute(
        "INSERT INTO media_file_probe_task_request VALUES ('"
            + REQUESTED_AT_VERSION_4
            + "', 8192, 1792152000, 0, 4)");
  }

  @AfterEach
  void dropMigrationSchema() {
    dsl.dropSchema(schema).cascade().execute();
  }

  @Test
  @DisplayName("Should delete the version 1 and version 3 results when the migration runs")
  void shouldDeleteTheVersion1AndVersion3ResultsWhenTheMigrationRuns() throws SQLException {
    migrate();

    assertThat(storedIds("media_file_container_info")).containsExactly(PROBED_AT_VERSION_4);
  }

  // The startup re-scan finds a file by its request or its result. A source that went missing
  // withdrew its request and kept its result, so the deleted result's inputs become its request.
  @Test
  @DisplayName(
      "Should keep each deleted result's inputs as its probe request when the file has no request")
  void shouldKeepEachDeletedResultsInputsAsItsProbeRequestWhenTheFileHasNoRequest()
      throws SQLException {
    migrate();

    assertThat(storedRequests())
        .containsExactlyInAnyOrder(
            List.of(PROBED_AT_VERSION_1.toString(), "4096", "1792065600", "500", "1"),
            List.of(PROBED_AT_VERSION_3.toString(), "4096", "1792065600", "500", "3"),
            List.of(REQUESTED_AT_VERSION_4.toString(), "8192", "1792152000", "0", "4"));
  }

  @Test
  @DisplayName("Should delete the streams of each deleted result when the migration runs")
  void shouldDeleteTheStreamsOfEachDeletedResultWhenTheMigrationRuns() throws SQLException {
    migrate();

    assertThat(storedIds("media_file_stream_info")).containsExactly(PROBED_AT_VERSION_4);
  }

  private void migrate() {
    migrations().load().migrate();
  }

  // Baselining at V096 applies only this change's migration to the hand-built tables.
  private FluentConfiguration migrations() {
    return Flyway.configure()
        .configuration(flyway.getConfiguration())
        .schemas(schema)
        .defaultSchema(schema)
        .baselineVersion("96")
        .baselineOnMigrate(true)
        .target("97");
  }

  private void storeResult(UUID mediaFileId, int probeVersion) throws SQLException {
    execute(
        "INSERT INTO media_file_container_info VALUES ('"
            + mediaFileId
            + "', 4096, 1792065600, 500, "
            + probeVersion
            + ")",
        "INSERT INTO media_file_stream_info (media_file_id, stream_index) VALUES ('"
            + mediaFileId
            + "', 0)");
  }

  private List<UUID> storedIds(String table) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT media_file_id FROM " + schema + "." + table)) {
      var ids = new ArrayList<UUID>();
      while (rows.next()) {
        ids.add(rows.getObject("media_file_id", UUID.class));
      }

      return ids;
    }
  }

  private List<List<String>> storedRequests() throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT media_file_id, source_size, source_modified_epoch_second,"
                    + " source_modified_nanos, probe_version FROM "
                    + schema
                    + ".media_file_probe_task_request")) {
      var requests = new ArrayList<List<String>>();
      while (rows.next()) {
        requests.add(
            List.of(
                rows.getString(1),
                rows.getString(2),
                rows.getString(3),
                rows.getString(4),
                rows.getString(5)));
      }

      return requests;
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
