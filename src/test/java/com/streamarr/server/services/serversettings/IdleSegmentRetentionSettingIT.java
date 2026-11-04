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
@DisplayName("Idle Segment Retention Setting Integration Tests")
class IdleSegmentRetentionSettingIT extends AbstractIntegrationTest {

  private static final String CHANGE_TO_THIRTY_MINUTES =
      """
      mutation { changeIdleSegmentRetention(input: {minutes: 30}) {
        idleSegmentRetentionSetting { minutes } userErrors { __typename } } }
      """;

  private static final String READ =
      "query { idleSegmentRetentionSetting { minutes minimumMinutes maximumMinutes } }";

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
  @DisplayName("Should save the retention, read it back, and record the ServerAdmin who changed it")
  void shouldSaveRetentionReadItBackAndRecordServerAdminWhoChangedIt() throws Exception {
    var bearer = authTestSupport.accountBearer(serverAdmin);

    mockMvc
        .perform(graphqlRequest(bearer, CHANGE_TO_THIRTY_MINUTES))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.changeIdleSegmentRetention.idleSegmentRetentionSetting.minutes")
                .value(30))
        .andExpect(jsonPath("$.data.changeIdleSegmentRetention.userErrors").isEmpty());

    mockMvc
        .perform(graphqlRequest(bearer, READ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.idleSegmentRetentionSetting.minutes").value(30))
        .andExpect(jsonPath("$.data.idleSegmentRetentionSetting.minimumMinutes").value(5))
        .andExpect(jsonPath("$.data.idleSegmentRetentionSetting.maximumMinutes").value(10_080));

    var stored = dsl.selectFrom(SERVER_SETTINGS).fetchSingle();
    assertThat(stored.getIdleSegmentRetentionMinutes()).isEqualTo(30);
    assertThat(stored.getLastModifiedBy()).isEqualTo(serverAdmin.account().getId());
    assertThat(serverSettingsService.idleSegmentRetention()).isEqualTo(Duration.ofMinutes(30));
  }

  @Test
  @DisplayName("Should reject the change and keep one hour when the retention is out of range")
  void shouldRejectChangeAndKeepOneHourWhenRetentionIsOutOfRange() throws Exception {
    mockMvc
        .perform(
            graphqlRequest(
                authTestSupport.accountBearer(serverAdmin),
                """
                mutation { changeIdleSegmentRetention(input: {minutes: 4}) {
                  idleSegmentRetentionSetting { minutes }
                  userErrors { __typename ... on InputMutationError { inputPath } } } }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.changeIdleSegmentRetention.idleSegmentRetentionSetting")
                .doesNotExist())
        .andExpect(
            jsonPath("$.data.changeIdleSegmentRetention.userErrors[0].__typename")
                .value("IdleSegmentRetentionOutOfRangeError"))
        .andExpect(
            jsonPath("$.data.changeIdleSegmentRetention.userErrors[0].inputPath[0]")
                .value("minutes"));

    assertThat(dsl.selectFrom(SERVER_SETTINGS).fetchSingle().getIdleSegmentRetentionMinutes())
        .isEqualTo(60);
  }

  @Test
  @DisplayName(
      "Should refuse the read and the change and keep one hour when the caller is not a"
          + " ServerAdmin")
  void shouldRefuseReadAndChangeAndKeepOneHourWhenCallerIsNotServerAdmin() throws Exception {
    var bearer = authTestSupport.accountBearer(resident);

    mockMvc
        .perform(graphqlRequest(bearer, CHANGE_TO_THIRTY_MINUTES))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
    mockMvc
        .perform(graphqlRequest(bearer, READ))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

    var stored = dsl.selectFrom(SERVER_SETTINGS).fetchSingle();
    assertThat(stored.getIdleSegmentRetentionMinutes()).isEqualTo(60);
    assertThat(stored.getLastModifiedBy()).isEqualTo(SystemActor.ID);
  }
}
