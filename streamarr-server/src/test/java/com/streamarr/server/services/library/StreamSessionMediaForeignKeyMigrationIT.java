package com.streamarr.server.services.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Stream Session Media Foreign Key Migration Tests")
class StreamSessionMediaForeignKeyMigrationIT extends AbstractIntegrationTest {

  @Autowired private DataSource dataSource;

  private DataSource migrationDataSource;

  @Test
  @DisplayName("Should preserve an A1 cleanup marker while enabling future media restrictions")
  void shouldPreserveA1CleanupMarkerWhileEnablingFutureMediaRestrictions() throws SQLException {
    var schema = "stream_media_fk_" + UUID.randomUUID().toString().replace("-", "");
    migrationDataSource = isolatedDataSource();

    try {
      migrateTo(schema, MigrationVersion.fromVersion("049"));
      var authority = seedAuthorityGraph(schema);
      var orphanedStreamId = UUID.randomUUID();
      var deletedMediaId = createMediaFile(schema, authority.libraryId());
      createTerminatingStream(schema, authority, orphanedStreamId, deletedMediaId);
      deleteMediaFile(schema, deletedMediaId);

      migrateTo(schema, MigrationVersion.LATEST);

      assertThat(streamExists(schema, orphanedStreamId)).isTrue();
      assertThat(mediaForeignKeyValidated(schema)).isFalse();

      assertThatThrownBy(
              () -> createActiveStream(schema, authority, UUID.randomUUID(), UUID.randomUUID()))
          .isInstanceOf(SQLException.class)
          .extracting(exception -> ((SQLException) exception).getSQLState())
          .isEqualTo("23503");

      var protectedMediaId = createMediaFile(schema, authority.libraryId());
      createActiveStream(schema, authority, UUID.randomUUID(), protectedMediaId);
      assertThatThrownBy(() -> deleteMediaFile(schema, protectedMediaId))
          .isInstanceOf(SQLException.class)
          .extracting(exception -> ((SQLException) exception).getSQLState())
          .isEqualTo("23001");
    } finally {
      dropSchema(schema);
    }
  }

  private void migrateTo(String schema, MigrationVersion target) {
    Flyway.configure()
        .dataSource(migrationDataSource)
        .schemas(schema)
        .defaultSchema(schema)
        .table("schema_history")
        .target(target)
        .load()
        .migrate();
  }

  private AuthorityGraph seedAuthorityGraph(String schema) throws SQLException {
    var accountId = UUID.randomUUID();
    var householdId = UUID.randomUUID();
    var profileId = UUID.randomUUID();
    var authSessionId = UUID.randomUUID();
    var libraryId = UUID.randomUUID();

    try (var connection = connection(schema)) {
      execute(
          connection,
          """
          INSERT INTO user_account (id, email, display_name, password_hash, account_role)
          VALUES (?, ?, 'Migration Test', 'hash', 'USER')
          """,
          accountId,
          accountId + "@example.com");
      execute(
          connection,
          "INSERT INTO household (id, name) VALUES (?, 'Migration Household')",
          householdId);
      execute(
          connection,
          """
          INSERT INTO household_membership (account_id, household_id, household_role)
          VALUES (?, ?, 'OWNER')
          """,
          accountId,
          householdId);
      execute(
          connection,
          "INSERT INTO profile (id, household_id, name) VALUES (?, ?, 'Migration Profile')",
          profileId,
          householdId);
      execute(
          connection,
          """
          INSERT INTO account_profile (account_id, household_id, profile_id)
          VALUES (?, ?, ?)
          """,
          accountId,
          householdId,
          profileId);
      execute(
          connection,
          """
          INSERT INTO auth_session (id, account_id, active_household_id, active_profile_id)
          VALUES (?, ?, ?, ?)
          """,
          authSessionId,
          accountId,
          householdId,
          profileId);
      execute(
          connection,
          """
          INSERT INTO library (id, filepath_uri, name, status, backend, type)
          VALUES (?, ?, 'Migration Library', 'HEALTHY', 'LOCAL', 'MOVIE')
          """,
          libraryId,
          "file:///migration/" + libraryId);
    }

    return new AuthorityGraph(accountId, householdId, profileId, authSessionId, libraryId);
  }

  private UUID createMediaFile(String schema, UUID libraryId) throws SQLException {
    var mediaFileId = UUID.randomUUID();
    try (var connection = connection(schema)) {
      execute(
          connection,
          """
          INSERT INTO media_file (id, filename, filepath_uri, size, library_id, status)
          VALUES (?, ?, ?, 1, ?, 'UNMATCHED')
          """,
          mediaFileId,
          mediaFileId + ".mkv",
          "file:///migration/" + mediaFileId + ".mkv",
          libraryId);
    }
    return mediaFileId;
  }

  private void createTerminatingStream(
      String schema, AuthorityGraph authority, UUID streamId, UUID mediaFileId)
      throws SQLException {
    try (var connection = connection(schema)) {
      execute(
          connection,
          """
          INSERT INTO stream_session (
              id, auth_session_id, account_id, household_id, profile_id, media_file_id,
              status, terminal_at, terminal_reason)
          VALUES (?, ?, ?, ?, ?, ?, 'TERMINATING', statement_timestamp(), 'SOURCE_DELETED')
          """,
          streamId,
          authority.authSessionId(),
          authority.accountId(),
          authority.householdId(),
          authority.profileId(),
          mediaFileId);
    }
  }

  private void createActiveStream(
      String schema, AuthorityGraph authority, UUID streamId, UUID mediaFileId)
      throws SQLException {
    try (var connection = connection(schema)) {
      execute(
          connection,
          """
          INSERT INTO stream_session (
              id, auth_session_id, account_id, household_id, profile_id, media_file_id, status)
          VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
          """,
          streamId,
          authority.authSessionId(),
          authority.accountId(),
          authority.householdId(),
          authority.profileId(),
          mediaFileId);
    }
  }

  private void deleteMediaFile(String schema, UUID mediaFileId) throws SQLException {
    try (var connection = connection(schema)) {
      execute(connection, "DELETE FROM media_file WHERE id = ?", mediaFileId);
    }
  }

  private boolean streamExists(String schema, UUID streamId) throws SQLException {
    try (var connection = connection(schema);
        var statement = connection.prepareStatement("SELECT 1 FROM stream_session WHERE id = ?")) {
      statement.setObject(1, streamId);
      try (var result = statement.executeQuery()) {
        return result.next();
      }
    }
  }

  private boolean mediaForeignKeyValidated(String schema) throws SQLException {
    try (var connection = connection(schema);
        var statement =
            connection.prepareStatement(
                """
                SELECT convalidated
                FROM pg_constraint
                WHERE conname = 'fk_stream_session_media_file'
                  AND conrelid = 'stream_session'::regclass
                """)) {
      try (var result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        return result.getBoolean(1);
      }
    }
  }

  private Connection connection(String schema) throws SQLException {
    var connection = migrationDataSource.getConnection();
    connection.setSchema(schema);
    return connection;
  }

  private DataSource isolatedDataSource() throws SQLException {
    var pooled = dataSource.unwrap(HikariDataSource.class);
    var isolated = new PGSimpleDataSource();
    isolated.setURL(pooled.getJdbcUrl());
    isolated.setUser(pooled.getUsername());
    isolated.setPassword(pooled.getPassword());
    return isolated;
  }

  private void execute(Connection connection, String sql, Object... values) throws SQLException {
    try (var statement = connection.prepareStatement(sql)) {
      for (var index = 0; index < values.length; index++) {
        statement.setObject(index + 1, values[index]);
      }
      statement.executeUpdate();
    }
  }

  private void dropSchema(String schema) throws SQLException {
    try (var connection = migrationDataSource.getConnection()) {
      DSL.using(connection).dropSchemaIfExists(DSL.name(schema)).cascade().execute();
    }
  }

  private record AuthorityGraph(
      UUID accountId, UUID householdId, UUID profileId, UUID authSessionId, UUID libraryId) {}
}
