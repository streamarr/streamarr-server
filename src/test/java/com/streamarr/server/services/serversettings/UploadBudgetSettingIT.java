package com.streamarr.server.services.serversettings;

import static com.streamarr.server.jooq.generated.tables.ServerSettings.SERVER_SETTINGS;
import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static com.streamarr.server.support.ServerSettingsTestSupport.resetServerSettings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.PostgresLockTestSupport;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@Tag("IntegrationTest")
@DisplayName("Upload Budget Setting Integration Tests")
class UploadBudgetSettingIT extends AbstractIntegrationTest {

  private static final String SET_TO_20_MEGABITS =
      """
      mutation { changeUploadBudget(input: {internetUploadMegabitsPerSecond: 20}) {
        uploadBudgetSetting { internetUploadMegabitsPerSecond streamShareMegabitsPerSecond }
        userErrors { __typename } } }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DSLContext dsl;
  @Autowired private DataSource dataSource;

  private AuthTestSupport.TestIdentity serverAdmin;
  private AuthTestSupport.TestIdentity resident;

  @BeforeEach
  void setUp() {
    resetServerSettings(dsl);
    serverAdmin = authTestSupport.createAdminIdentity();
    resident = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    resetServerSettings(dsl);
    authTestSupport.deleteIdentity(resident);
    authTestSupport.deleteIdentity(serverAdmin);
  }

  @Test
  @DisplayName("Should save the budget, read it back, and record the ServerAdmin who changed it")
  void shouldSaveTheBudgetReadItBackAndRecordTheServerAdminWhoChangedIt() throws Exception {
    var bearer = authTestSupport.accountBearer(serverAdmin);

    mockMvc
        .perform(graphqlRequest(bearer, SET_TO_20_MEGABITS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath(
                    "$.data.changeUploadBudget.uploadBudgetSetting.internetUploadMegabitsPerSecond")
                .value(20))
        .andExpect(
            jsonPath("$.data.changeUploadBudget.uploadBudgetSetting.streamShareMegabitsPerSecond")
                .value(16.0))
        .andExpect(jsonPath("$.data.changeUploadBudget.userErrors").isEmpty());

    mockMvc
        .perform(
            graphqlRequest(
                bearer,
                """
                query { uploadBudgetSetting {
                  internetUploadMegabitsPerSecond streamSharePercent streamShareMegabitsPerSecond
                  minimumInternetUploadMegabitsPerSecond maximumInternetUploadMegabitsPerSecond } }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.uploadBudgetSetting.internetUploadMegabitsPerSecond").value(20))
        .andExpect(jsonPath("$.data.uploadBudgetSetting.streamSharePercent").value(80))
        .andExpect(jsonPath("$.data.uploadBudgetSetting.streamShareMegabitsPerSecond").value(16.0))
        .andExpect(
            jsonPath("$.data.uploadBudgetSetting.minimumInternetUploadMegabitsPerSecond").value(1))
        .andExpect(
            jsonPath("$.data.uploadBudgetSetting.maximumInternetUploadMegabitsPerSecond")
                .value(10_000));

    var stored = dsl.selectFrom(SERVER_SETTINGS).fetchSingle();
    assertThat(stored.getInternetUploadMegabitsPerSecond()).isEqualTo(20);
    assertThat(stored.getLastModifiedBy()).isEqualTo(serverAdmin.account().getId());
  }

  @Test
  @DisplayName("Should turn the budget off when a ServerAdmin clears it")
  void shouldTurnTheBudgetOffWhenAServerAdminClearsIt() throws Exception {
    var bearer = authTestSupport.accountBearer(serverAdmin);
    mockMvc.perform(graphqlRequest(bearer, SET_TO_20_MEGABITS)).andExpect(status().isOk());

    mockMvc
        .perform(
            graphqlRequest(
                bearer,
                """
                mutation { changeUploadBudget(input: {internetUploadMegabitsPerSecond: null}) {
                  uploadBudgetSetting {
                    internetUploadMegabitsPerSecond streamShareMegabitsPerSecond }
                  userErrors { __typename } } }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath(
                    "$.data.changeUploadBudget.uploadBudgetSetting.internetUploadMegabitsPerSecond")
                .doesNotExist())
        .andExpect(
            jsonPath("$.data.changeUploadBudget.uploadBudgetSetting.streamShareMegabitsPerSecond")
                .doesNotExist());

    assertThat(dsl.selectFrom(SERVER_SETTINGS).fetchSingle().getInternetUploadMegabitsPerSecond())
        .isNull();
  }

  @Test
  @DisplayName(
      "Should reject an upload speed below the range at internetUploadMegabitsPerSecond and keep"
          + " the budget off")
  void shouldRejectAnUploadSpeedBelowTheRangeAndKeepTheBudgetOff() throws Exception {
    mockMvc
        .perform(
            graphqlRequest(
                authTestSupport.accountBearer(serverAdmin),
                """
                mutation { changeUploadBudget(input: {internetUploadMegabitsPerSecond: 0}) {
                  uploadBudgetSetting { internetUploadMegabitsPerSecond }
                  userErrors { __typename ... on InputMutationError { inputPath } } } }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.changeUploadBudget.uploadBudgetSetting").doesNotExist())
        .andExpect(
            jsonPath("$.data.changeUploadBudget.userErrors[0].__typename")
                .value("UploadBudgetOutOfRangeError"))
        .andExpect(
            jsonPath("$.data.changeUploadBudget.userErrors[0].inputPath[0]")
                .value("internetUploadMegabitsPerSecond"));

    assertThat(dsl.selectFrom(SERVER_SETTINGS).fetchSingle().getInternetUploadMegabitsPerSecond())
        .isNull();
  }

  @Test
  @DisplayName("Should refuse the read and the change when the caller is not a ServerAdmin")
  void shouldRefuseTheReadAndTheChangeWhenTheCallerIsNotAServerAdmin() throws Exception {
    var bearer = authTestSupport.accountBearer(resident);

    mockMvc
        .perform(graphqlRequest(bearer, SET_TO_20_MEGABITS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
    mockMvc
        .perform(
            graphqlRequest(
                bearer, "query { uploadBudgetSetting { internetUploadMegabitsPerSecond } }"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

    var stored = dsl.selectFrom(SERVER_SETTINGS).fetchSingle();
    assertThat(stored.getInternetUploadMegabitsPerSecond()).isNull();
    assertThat(stored.getLastModifiedBy()).isEqualTo(SystemActor.ID);
  }

  @Test
  @DisplayName(
      "Should keep an encoder preset change that commits while a budget change waits to write")
  void shouldKeepAnEncoderPresetChangeThatCommitsWhileABudgetChangeWaitsToWrite() throws Exception {
    var bearer = authTestSupport.accountBearer(serverAdmin);
    try (var holder = dataSource.getConnection();
        var observer = dataSource.getConnection()) {
      holder.setAutoCommit(false);
      lockTheSettingsRowAsTheSystemActor(holder);
      var budgetChange =
          CompletableFuture.runAsync(
              () -> {
                try {
                  mockMvc.perform(graphqlRequest(bearer, SET_TO_20_MEGABITS));
                } catch (Exception failure) {
                  throw new IllegalStateException(failure);
                }
              });
      PostgresLockTestSupport.awaitBlockedBackendPid(
          observer, PostgresLockTestSupport.backendPid(holder), null);

      try (var update =
          holder.prepareStatement("UPDATE server_settings SET encoder_preset = 'MEDIUM'")) {
        update.executeUpdate();
      }

      holder.commit();
      budgetChange.join();
    }

    var stored = dsl.selectFrom(SERVER_SETTINGS).fetchSingle();
    assertThat(stored.getEncoderPreset()).isEqualTo("MEDIUM");
    assertThat(stored.getInternetUploadMegabitsPerSecond()).isEqualTo(20);
  }

  private static void lockTheSettingsRowAsTheSystemActor(Connection holder) throws SQLException {
    try (var actor = holder.prepareStatement("SELECT set_config('streamarr.actor_id', ?, true)")) {
      actor.setString(1, SystemActor.ID.toString());
      actor.execute();
    }

    try (var lock = holder.prepareStatement("SELECT id FROM server_settings FOR UPDATE")) {
      lock.execute();
    }
  }
}
