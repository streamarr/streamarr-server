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
class LeadingPunctuationTitleSortMigrationIT extends AbstractIntegrationTest {
  @Autowired private DataSource dataSource;
  @Autowired private DSLContext dsl;
  @Autowired private Flyway flyway;

  private String schema;

  @BeforeEach
  void upgradeSchemaWithTitleSortsStoredBeforeTheChange() throws SQLException {
    schema = "leading_punctuation_" + UUID.randomUUID().toString().replace("-", "");
    dsl.createSchema(schema).execute();
    seedStoredTitles();
    migrateThroughV106();
  }

  @AfterEach
  void dropSchema() {
    dsl.dropSchema(schema).cascade().execute();
  }

  @Test
  @DisplayName(
      "Should strip leading punctuation from stored sort titles when the schema is upgraded")
  void shouldStripLeadingPunctuationFromStoredSortTitlesWhenTheSchemaIsUpgraded()
      throws SQLException {
    assertThat(
            query("SELECT id, title_sort, last_modified_by FROM %s.base_collectable ORDER BY id"))
        .containsExactly(
            tuple("1", "Quiet Harbor”", SystemActor.ID.toString()),
            tuple("2", "Harbor, The", SystemActor.ID.toString()),
            tuple("3", "Alpha", null),
            tuple("4", null, null),
            tuple("5", "Quiet Night!, A", SystemActor.ID.toString()),
            tuple("6", "$ilver Harbor", null));
  }

  @Test
  @DisplayName("Should move upgraded titles out of HASH when the schema is upgraded")
  void shouldMoveUpgradedTitlesOutOfHashWhenTheSchemaIsUpgraded() throws SQLException {
    assertThat(
            query(
                "SELECT library_id, letter, item_count FROM %s.library_metadata"
                    + " ORDER BY library_id, letter"))
        .containsExactly(
            tuple("00000000-0000-0000-0000-00000000000a", "A", "1"),
            tuple("00000000-0000-0000-0000-00000000000a", "H", "1"),
            tuple("00000000-0000-0000-0000-00000000000a", "Q", "1"),
            tuple("00000000-0000-0000-0000-00000000000b", "Q", "1"),
            tuple("00000000-0000-0000-0000-00000000000b", "HASH", "1"));
  }

  private void migrateThroughV106() {
    Flyway.configure()
        .configuration(flyway.getConfiguration())
        .schemas(schema)
        .defaultSchema(schema)
        .baselineVersion("104")
        .baselineOnMigrate(true)
        .target("106")
        .load()
        .migrate();
  }

  private void seedStoredTitles() throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("SET search_path = " + schema + ", public");
      statement.execute(
          """
          CREATE TABLE base_collectable (
            id INT PRIMARY KEY, library_id UUID, title TEXT, title_sort TEXT,
            created_by UUID, created_on TIMESTAMPTZ,
            last_modified_by UUID, last_modified_on TIMESTAMPTZ);
          CREATE TABLE movie (id INT PRIMARY KEY REFERENCES base_collectable ON DELETE CASCADE);
          CREATE TABLE series (id INT PRIMARY KEY REFERENCES base_collectable ON DELETE CASCADE);
          CREATE TABLE library_metadata (
            id UUID DEFAULT gen_random_uuid(), library_id UUID, letter public.alphabet_letter,
            item_count INT, UNIQUE(library_id, letter));
          INSERT INTO base_collectable (id, library_id, title, title_sort) VALUES
            (1, '00000000-0000-0000-0000-00000000000a', '“Quiet Harbor”', '“Quiet Harbor”'),
            (2, '00000000-0000-0000-0000-00000000000a',
                E'\\uFEFFThe Harbor', E'\\uFEFFThe Harbor'),
            (3, '00000000-0000-0000-0000-00000000000a', 'Alpha', 'Alpha'),
            (4, '00000000-0000-0000-0000-00000000000a', '“Season 1”', NULL),
            (5, '00000000-0000-0000-0000-00000000000b', '¡A Quiet Night!', '¡A Quiet Night!'),
            (6, '00000000-0000-0000-0000-00000000000b', '$ilver Harbor', '$ilver Harbor');
          INSERT INTO movie VALUES (1), (3), (5), (6);
          INSERT INTO series VALUES (2);
          CREATE TRIGGER fill_audit_columns BEFORE INSERT OR UPDATE ON base_collectable
            FOR EACH ROW EXECUTE FUNCTION public.fill_audit_columns();
          RESET search_path;
          """);
    }
  }

  private List<Tuple> query(String sql) throws SQLException {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery(sql.formatted(schema))) {
      var columns = rows.getMetaData().getColumnCount();
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
