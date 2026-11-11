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
import java.time.Duration;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@Tag("IntegrationTest")
@DisplayName("Notification Retention Setting Integration Tests")
class NotificationRetentionSettingIT extends AbstractIntegrationTest {

  private static final String CHANGE_TO_THIRTY_DAYS =
      """
      mutation { changeNotificationRetention(input: {days: 30}) {
        notificationRetentionSetting { id days } userErrors { __typename } } }
      """;

  private static final String READ =
      "query { notificationRetentionSetting { id days minimumDays maximumDays } }";

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DSLContext dsl;
  @Autowired private ServerSettingsService serverSettingsService;

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
  @DisplayName(
      "Should save the retention, read it back under the same id, and record the ServerAdmin who"
          + " changed it")
  void shouldSaveTheRetentionReadItBackAndRecordTheServerAdminWhoChangedIt() throws Exception {
    var bearer = authTestSupport.accountBearer(serverAdmin);
    var settingId = dsl.selectFrom(SERVER_SETTINGS).fetchSingle().getId().toString();

    mockMvc
        .perform(graphqlRequest(bearer, CHANGE_TO_THIRTY_DAYS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.changeNotificationRetention.notificationRetentionSetting.days")
                .value(30))
        .andExpect(
            jsonPath("$.data.changeNotificationRetention.notificationRetentionSetting.id")
                .value(settingId))
        .andExpect(jsonPath("$.data.changeNotificationRetention.userErrors").isEmpty());

    mockMvc
        .perform(graphqlRequest(bearer, READ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.notificationRetentionSetting.id").value(settingId))
        .andExpect(jsonPath("$.data.notificationRetentionSetting.days").value(30))
        .andExpect(jsonPath("$.data.notificationRetentionSetting.minimumDays").value(1))
        .andExpect(jsonPath("$.data.notificationRetentionSetting.maximumDays").value(365));

    var stored = dsl.selectFrom(SERVER_SETTINGS).fetchSingle();
    assertThat(stored.getNotificationRetentionDays()).isEqualTo(30);
    assertThat(stored.getLastModifiedBy()).isEqualTo(serverAdmin.account().getId());
    assertThat(serverSettingsService.notificationRetention()).isEqualTo(Duration.ofDays(30));
  }

  @Test
  @DisplayName("Should reject the change and keep ninety days when the retention is out of range")
  void shouldRejectTheChangeAndKeepNinetyDaysWhenTheRetentionIsOutOfRange() throws Exception {
    mockMvc
        .perform(
            graphqlRequest(
                authTestSupport.accountBearer(serverAdmin),
                """
                mutation { changeNotificationRetention(input: {days: 366}) {
                  notificationRetentionSetting { days }
                  userErrors { __typename ... on InputMutationError { message inputPath } } } }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.changeNotificationRetention.notificationRetentionSetting")
                .doesNotExist())
        .andExpect(
            jsonPath("$.data.changeNotificationRetention.userErrors[0].__typename")
                .value("NotificationRetentionOutOfRangeError"))
        .andExpect(
            jsonPath("$.data.changeNotificationRetention.userErrors[0].message")
                .value("Choose from 1 to 365 days."))
        .andExpect(
            jsonPath("$.data.changeNotificationRetention.userErrors[0].inputPath[0]")
                .value("days"));

    assertThat(dsl.selectFrom(SERVER_SETTINGS).fetchSingle().getNotificationRetentionDays())
        .isEqualTo(90);
  }

  @Test
  @DisplayName(
      "Should refuse the read and the change and keep ninety days when the caller is not a"
          + " ServerAdmin")
  void shouldRefuseTheReadAndTheChangeWhenTheCallerIsNotAServerAdmin() throws Exception {
    var bearer = authTestSupport.accountBearer(resident);

    mockMvc
        .perform(graphqlRequest(bearer, CHANGE_TO_THIRTY_DAYS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
    mockMvc
        .perform(graphqlRequest(bearer, READ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

    var stored = dsl.selectFrom(SERVER_SETTINGS).fetchSingle();
    assertThat(stored.getNotificationRetentionDays()).isEqualTo(90);
    assertThat(stored.getLastModifiedBy()).isEqualTo(SystemActor.ID);
  }
}
