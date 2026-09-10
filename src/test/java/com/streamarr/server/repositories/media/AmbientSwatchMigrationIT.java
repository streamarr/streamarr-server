package com.streamarr.server.repositories.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
      statement.execute(
          """
          CREATE TABLE image (id INTEGER PRIMARY KEY, ambient_primary TEXT,
            ambient_top_left TEXT, ambient_top_right TEXT,
            ambient_bottom_right TEXT, ambient_bottom_left TEXT)
          """);
      statement.execute("INSERT INTO image (id) VALUES (1)");
      statement.execute(
          """
          INSERT INTO image VALUES
            (2, '#00a0a0', '#010101', '#020202', '#030303', '#040404')
          """);
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

    migrations().target("68?").callbacks(probe).load().migrate();

    assertThat(probe.checked)
        .as("Constraint validation must run before the upgrade finishes")
        .isTrue();
  }

  @ParameterizedTest(name = "{0} before validation")
  @ValueSource(
      strings = {
        "ambient_dark_vibrant",
        "ambient_dark_muted",
        "ambient_light_vibrant",
        "ambient_light_muted"
      })
  @DisplayName("Should reject orphan swatches when the addition has committed before validation")
  void shouldRejectOrphanSwatchesWhenAdditionHasCommittedBeforeValidation(String column)
      throws SQLException {
    migrations().target("67?").load().migrate();

    try (var connection = dataSource.getConnection();
        var insert =
            connection.prepareStatement(
                "INSERT INTO " + schema + ".image (id, " + column + ") VALUES (3, '#283830')")) {
      assertThatThrownBy(insert::executeUpdate)
          .isInstanceOfSatisfying(
              SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"))
          .hasMessageContaining("chk_image_ambient_swatches_require_primary");
    }
  }

  @Test
  @DisplayName("Should preserve legacy ambient colors when the swatch migrations complete")
  void shouldPreserveLegacyAmbientColorsWhenSwatchMigrationsComplete() throws SQLException {
    migrations().target("68?").load().migrate();

    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement();
        var row = statement.executeQuery("SELECT * FROM " + schema + ".image WHERE id = 2")) {
      assertThat(row.next()).isTrue();
      assertThat(row.getString("ambient_primary")).isEqualTo("#00a0a0");
      assertThat(row.getString("ambient_top_left")).isEqualTo("#010101");
      assertThat(row.getString("ambient_top_right")).isEqualTo("#020202");
      assertThat(row.getString("ambient_bottom_right")).isEqualTo("#030303");
      assertThat(row.getString("ambient_bottom_left")).isEqualTo("#040404");
      for (var column :
          List.of(
              "ambient_dark_vibrant",
              "ambient_dark_muted",
              "ambient_light_vibrant",
              "ambient_light_muted")) {
        assertThat(row.getString(column)).as(column).isNull();
      }
    }
  }

  private FluentConfiguration migrations() {
    return Flyway.configure()
        .configuration(flyway.getConfiguration())
        .schemas(schema)
        .defaultSchema(schema)
        .baselineVersion("66")
        .baselineOnMigrate(true);
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
        try (var result = statement.executeQuery("SELECT id FROM image WHERE id = 1")) {
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
