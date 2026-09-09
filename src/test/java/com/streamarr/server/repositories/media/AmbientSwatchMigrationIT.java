package com.streamarr.server.repositories.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("Ambient Swatch Migration Integration Tests")
class AmbientSwatchMigrationIT extends AbstractIntegrationTest {

  private final String schema =
      "ambient_migration_" + UUID.randomUUID().toString().replace("-", "");

  @Autowired private DataSource dataSource;
  @Autowired private DSLContext dsl;
  @Autowired private Flyway flyway;

  @BeforeEach
  void createExistingImageTable() throws SQLException {
    dsl.createSchema(schema).execute();
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      connection.setAutoCommit(false);
      statement.execute("SET LOCAL search_path = " + schema);
      statement.execute("CREATE TABLE image (id INTEGER PRIMARY KEY, ambient_primary TEXT)");
      statement.execute("INSERT INTO image (id) VALUES (1)");
      connection.commit();
    }
  }

  @AfterEach
  void dropMigrationSchema() {
    dsl.dropSchema(schema).cascade().execute();
  }

  @Test
  @DisplayName("Should allow image reads and writes when ambient swatches are validated")
  void shouldAllowImageReadsAndWritesWhenAmbientSwatchesAreValidated() {
    var probe = new ValidationAccessProbe(dataSource, schema);

    Flyway.configure()
        .configuration(flyway.getConfiguration())
        .schemas(schema)
        .defaultSchema(schema)
        .baselineVersion("66")
        .baselineOnMigrate(true)
        .target("68?")
        .callbacks(probe)
        .load()
        .migrate();

    assertThat(probe.checked)
        .as("Constraint validation must run before the upgrade finishes")
        .isTrue();
  }

  @RequiredArgsConstructor
  private static final class ValidationAccessProbe extends BaseCallback {

    private final DataSource dataSource;
    private final String schema;
    private boolean checked;

    @Override
    public boolean supports(Event event, Context context) {
      return event == Event.AFTER_EACH_MIGRATE;
    }

    @Override
    public void handle(Event event, Context context) {
      try {
        if (!constraintIsValidated(context.getConnection())) {
          return;
        }

        // PostgreSQL retains the validation statement's locks until this transaction commits.
        assertThat(context.getConnection().getAutoCommit()).isFalse();
        assertImageAccess();
        checked = true;
      } catch (SQLException exception) {
        throw new AssertionError("Image access must remain available during validation", exception);
      }
    }

    private boolean constraintIsValidated(Connection connection) throws SQLException {
      try (var statement = connection.createStatement();
          var result =
              statement.executeQuery(
                  """
                  SELECT convalidated FROM pg_constraint
                  WHERE conrelid = 'image'::regclass
                    AND conname = 'chk_image_ambient_swatches_require_primary'
                  """)) {
        assertThat(result.next()).as("The ambient swatch constraint must exist").isTrue();
        return result.getBoolean("convalidated");
      }
    }

    private void assertImageAccess() throws SQLException {
      try (var connection = dataSource.getConnection();
          var statement = connection.createStatement()) {
        connection.setAutoCommit(false);
        statement.execute("SET LOCAL search_path = " + schema);
        statement.execute("SET LOCAL lock_timeout = '1s'");
        try (var result = statement.executeQuery("SELECT id FROM image")) {
          assertThat(result.next()).isTrue();
          assertThat(result.getInt("id")).isEqualTo(1);
        }

        assertThat(
                statement.executeUpdate(
                    "UPDATE image SET ambient_primary = '#000000' WHERE id = 1"))
            .isEqualTo(1);
        connection.rollback();
      }
    }
  }
}
