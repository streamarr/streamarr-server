package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SystemActor;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.assertj.core.groups.Tuple;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
class TitleSortMigrationIT extends AbstractIntegrationTest {
  @Autowired private DataSource dataSource;
  @Autowired private DSLContext dsl;
  @Autowired private Flyway flyway;

  private String schema;

  @BeforeEach
  void createSchema() throws SQLException {
    schema = "title_sort_" + UUID.randomUUID().toString().replace("-", "");
    dsl.createSchema(schema).execute();
    seedStoredTitles();
    Flyway.configure()
        .configuration(flyway.getConfiguration())
        .schemas(schema)
        .defaultSchema(schema)
        .baselineVersion("99")
        .baselineOnMigrate(true)
        .target("latest")
        .load()
        .migrate();
  }

  @AfterEach
  void dropSchema() {
    dsl.dropSchema(schema).cascade().execute();
  }

  @Test
  @DisplayName("Should encode stored sort titles as the System actor when the schema is upgraded")
  void shouldEncodeStoredSortTitlesAsTheSystemActorWhenTheSchemaIsUpgraded() throws SQLException {
    assertThat(
            query(
                "SELECT id, title_sort, last_modified_by FROM %s.base_collectable ORDER BY id", 3))
        .containsExactly(
            tuple("1", "Elite Harbor", SystemActor.ID.toString()),
            tuple("2", "Tilde Tales", SystemActor.ID.toString()),
            tuple("3", "Okura Nights, The", SystemActor.ID.toString()),
            tuple("4", "Ωmega Point", null),
            tuple("5", "Alpha", null),
            tuple("6", null, null),
            tuple("7", null, null));
  }

  @Test
  @DisplayName("Should rebuild letter counts from encoded sort titles when the schema is upgraded")
  void shouldRebuildLetterCountsFromEncodedSortTitlesWhenTheSchemaIsUpgraded() throws SQLException {
    assertThat(
            query(
                "SELECT library_id, letter, item_count FROM %s.library_metadata"
                    + " ORDER BY library_id, letter",
                3))
        .containsExactly(
            tuple("1", "E", "1"),
            tuple("1", "O", "1"),
            tuple("1", "T", "1"),
            tuple("1", "HASH", "2"),
            tuple("2", "A", "1"));
  }

  private void seedStoredTitles() throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("SET search_path = " + schema + ", public");
      statement.execute(
          """
          CREATE TABLE base_collectable (
            id INT PRIMARY KEY, library_id INT, title TEXT, title_sort TEXT,
            created_by UUID, created_on TIMESTAMPTZ,
            last_modified_by UUID, last_modified_on TIMESTAMPTZ);
          CREATE TABLE movie (id INT PRIMARY KEY);
          CREATE TABLE series (id INT PRIMARY KEY);
          CREATE TABLE library_metadata (
            id UUID DEFAULT gen_random_uuid(), library_id INT, letter public.alphabet_letter,
            item_count INT, UNIQUE(library_id, letter));
          INSERT INTO base_collectable (id, library_id, title, title_sort) VALUES
            (1, 1, 'Élite Harbor', 'Élite Harbor'),
            (2, 1, '~Tilde Tales', '~Tilde Tales'),
            (3, 1, 'The Ōkura Nights', 'Ōkura Nights, The'),
            (4, 1, 'Ωmega Point', 'Ωmega Point'),
            (5, 2, 'Alpha', 'Alpha'),
            (6, 1, 'Season 1', NULL),
            (7, 1, 'Unnamed', NULL);
          INSERT INTO movie VALUES (1), (2), (4), (5), (7);
          INSERT INTO series VALUES (3);
          INSERT INTO library_metadata (library_id, letter, item_count)
            VALUES (1, 'HASH', 4), (1, 'Z', 3), (3, 'B', 1);
          CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON base_collectable
            FOR EACH ROW EXECUTE FUNCTION public.fill_audit_columns();
          RESET search_path;
          """);
    }
  }

  private List<Tuple> query(String sql, int columns) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      try (var rows = statement.executeQuery(sql.formatted(schema))) {
        var results = new ArrayList<Tuple>();
        while (rows.next()) {
          var values = new Object[columns];
          for (int column = 0; column < columns; column++) {
            values[column] = rows.getString(column + 1);
          }

          results.add(tuple(values));
        }

        return results;
      }
    }
  }
}
