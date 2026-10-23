package com.streamarr.server.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

@Tag("IntegrationTest")
class LibraryCountsMigrationIT extends AbstractIntegrationTest {
  @Autowired private DataSource dataSource;
  @Autowired private DSLContext dsl;
  @Autowired private Flyway flyway;
  @Autowired private ObjectMapper objectMapper;

  @Test
  @DisplayName("Should seek a late title page with bounded work when the schema is upgraded")
  void shouldSeekALateTitlePageWithBoundedWorkWhenTheSchemaIsUpgraded() throws SQLException {
    var schema = "title_page_" + UUID.randomUUID().toString().replace("-", "");
    dsl.createSchema(schema).execute();
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("SET search_path = " + schema + ", public");
      statement.execute(
          """
          CREATE TABLE base_collectable (id INT PRIMARY KEY, library_id INT, title_sort TEXT);
          CREATE INDEX idx_base_collectable_library_title_seek
            ON base_collectable (
              library_id,
              (title_sort IS NULL),
              COALESCE(LOWER(title_sort COLLATE "C"), '') COLLATE "C",
              id);
          INSERT INTO base_collectable
            SELECT n, 1, 'title' || lpad(n::text, 6, '0') FROM generate_series(1,1000) n;
          INSERT INTO base_collectable VALUES (1001, 1, NULL);
          """);
      Flyway.configure()
          .configuration(flyway.getConfiguration())
          .schemas(schema)
          .defaultSchema(schema)
          .baselineVersion("100")
          .baselineOnMigrate(true)
          .target("101")
          .load()
          .migrate();
      statement.execute("ANALYZE base_collectable");
      // A sequential scan and a sort are ruled out so the planner uses an index whenever one serves
      // the page, whatever the table's size or statistics.
      connection.setAutoCommit(false);
      statement.execute("SET LOCAL enable_seqscan = off");
      statement.execute("SET LOCAL enable_sort = off");

      try (var rows =
          statement.executeQuery(
              """
              EXPLAIN (ANALYZE, FORMAT JSON)
              SELECT id FROM base_collectable
              WHERE library_id = 1
                AND (LOWER(title_sort COLLATE "C") IS NULL, LOWER(title_sort COLLATE "C"), id)
                  >= (false, 'title000500', 500)
              ORDER BY LOWER(title_sort COLLATE "C") IS NULL, LOWER(title_sort COLLATE "C"), id
              LIMIT 50
              """)) {
        assertThat(rows.next()).isTrue();
        var plan = objectMapper.readTree(rows.getString(1));
        assertThat(plan.findValues("Actual Rows"))
            .isNotEmpty()
            .allSatisfy(count -> assertThat(count.asLong()).isLessThanOrEqualTo(100));
        assertThat(plan.findValues("Rows Removed by Filter"))
            .allSatisfy(count -> assertThat(count.asLong()).isLessThanOrEqualTo(100));
      } finally {
        connection.rollback();
        connection.setAutoCommit(true);
      }
    } finally {
      dsl.dropSchema(schema).cascade().execute();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"C", "tr-TR-x-icu"})
  @DisplayName(
      "Should replace legacy child counts and remove stale buckets when the schema is upgraded")
  void shouldReplaceLegacyChildCountsAndRemoveStaleBucketsWhenTheSchemaIsUpgraded(String collation)
      throws SQLException {
    var schema = "library_counts_" + UUID.randomUUID().toString().replace("-", "");
    dsl.createSchema(schema).execute();
    try {
      seedLegacyCounts(schema, collation);
      Flyway.configure()
          .configuration(flyway.getConfiguration())
          .schemas(schema)
          .defaultSchema(schema)
          .baselineVersion("97")
          .baselineOnMigrate(true)
          .target("98")
          .load()
          .migrate();

      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "SELECT library_id, letter, item_count FROM "
                      + schema
                      + ".library_metadata ORDER BY library_id, letter")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt("library_id")).isEqualTo(1);
        assertThat(rows.getString("letter")).isEqualTo("A");
        assertThat(rows.getInt("item_count")).isOne();
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt("library_id")).isEqualTo(2);
        assertThat(rows.getString("letter")).isEqualTo("I");
        assertThat(rows.getInt("item_count")).isOne();
        assertThat(rows.next()).isTrue();
        assertThat(rows.getInt("library_id")).isEqualTo(2);
        assertThat(rows.getString("letter")).isEqualTo("HASH");
        assertThat(rows.getInt("item_count")).isEqualTo(3);
        assertThat(rows.next()).isFalse();
      }
    } finally {
      dsl.dropSchema(schema).cascade().execute();
    }
  }

  @Test
  @DisplayName("Should rebuild the letter index and count later titles when the schema is upgraded")
  void shouldRebuildTheLetterIndexAndCountLaterTitlesWhenTheSchemaIsUpgraded() throws SQLException {
    var schema = "letter_index_" + UUID.randomUUID().toString().replace("-", "");
    dsl.createSchema(schema).execute();
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("SET search_path = " + schema + ", public");
      statement.execute(
          """
          CREATE TABLE base_collectable (id INT PRIMARY KEY, library_id UUID, title_sort TEXT);
          CREATE TABLE movie (id INT PRIMARY KEY REFERENCES base_collectable ON DELETE CASCADE);
          CREATE TABLE series (id INT PRIMARY KEY REFERENCES base_collectable ON DELETE CASCADE);
          CREATE TABLE library_metadata (
            id UUID DEFAULT gen_random_uuid(), library_id UUID, letter public.alphabet_letter,
            item_count INT, UNIQUE(library_id, letter));
          INSERT INTO base_collectable VALUES
            (1, '00000000-0000-0000-0000-00000000000a', 'Alpha'),
            (2, '00000000-0000-0000-0000-00000000000a', 'Season One');
          INSERT INTO movie VALUES (1);
          INSERT INTO library_metadata (library_id, letter, item_count) VALUES
            ('00000000-0000-0000-0000-00000000000a', 'A', 3),
            ('00000000-0000-0000-0000-00000000000a', 'Z', 2);
          """);
      Flyway.configure()
          .configuration(flyway.getConfiguration())
          .schemas(schema)
          .defaultSchema(schema)
          .baselineVersion("104")
          .baselineOnMigrate(true)
          .target("105")
          .load()
          .migrate();
      statement.execute(
          """
          INSERT INTO base_collectable VALUES (3, '00000000-0000-0000-0000-00000000000a', 'Bravo');
          INSERT INTO series VALUES (3);
          """);

      try (var rows =
          statement.executeQuery(
              "SELECT letter, item_count FROM library_metadata ORDER BY letter")) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("letter")).isEqualTo("A");
        assertThat(rows.getInt("item_count")).isOne();
        assertThat(rows.next()).isTrue();
        assertThat(rows.getString("letter")).isEqualTo("B");
        assertThat(rows.getInt("item_count")).isOne();
        assertThat(rows.next()).isFalse();
      }
    } finally {
      dsl.dropSchema(schema).cascade().execute();
    }
  }

  private void seedLegacyCounts(String schema, String collation) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("SET search_path = " + schema + ", public");
      statement.execute(
          "CREATE TABLE base_collectable (id INT PRIMARY KEY, library_id INT, title_sort TEXT COLLATE \""
              + collation
              + "\")");
      statement.execute(
          """
          CREATE INDEX idx_base_collectable_library_titlesort_ci_id
            ON base_collectable (library_id, LOWER(title_sort), id);
          CREATE TABLE movie (id INT PRIMARY KEY);
          CREATE TABLE series (id INT PRIMARY KEY);
          CREATE TABLE library_metadata (
            id UUID DEFAULT gen_random_uuid(), library_id INT, letter public.alphabet_letter,
            item_count INT, UNIQUE(library_id, letter));
          INSERT INTO base_collectable VALUES (1, 1, 'Alpha'), (2, 2, NULL), (3, 2, '123');
          INSERT INTO base_collectable SELECT n, 1, 'Season or Episode' FROM generate_series(4,25) n;
          INSERT INTO base_collectable VALUES (26, 2, 'indigo'), (27, 2, 'Kelvin');
          INSERT INTO series VALUES (1);
          INSERT INTO movie VALUES (2), (3), (26), (27);
          INSERT INTO library_metadata (library_id, letter, item_count)
            VALUES (1, 'A', 1), (1, 'S', 22), (2, 'HASH', 1), (3, 'B', 4);
          """);
    }
  }
}
