package com.streamarr.server.services.serversettings;

import static com.streamarr.server.jooq.generated.tables.ServerSettings.SERVER_SETTINGS;
import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static com.streamarr.server.support.ServerSettingsTestSupport.resetServerSettings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.support.AuthTestSupport;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@Tag("IntegrationTest")
@DisplayName("Encoder Preset Setting Integration Tests")
class EncoderPresetSettingIT extends AbstractIntegrationTest {

  private static final String CHANGE_TO_MEDIUM =
      """
      mutation { changeEncoderPreset(input: {preset: "MEDIUM"}) {
        encoderPresetSetting { preset } userErrors { __typename } } }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity serverAdmin;
  private AuthTestSupport.TestIdentity resident;

  @BeforeEach
  void setUp() {
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
  @DisplayName("Should save the preset, read it back, and record the ServerAdmin who changed it")
  void shouldSavePresetReadItBackAndRecordServerAdminWhoChangedIt() throws Exception {
    var bearer = authTestSupport.accountBearer(serverAdmin);

    mockMvc
        .perform(graphqlRequest(bearer, CHANGE_TO_MEDIUM))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.changeEncoderPreset.encoderPresetSetting.preset").value("MEDIUM"))
        .andExpect(jsonPath("$.data.changeEncoderPreset.userErrors").isEmpty());

    mockMvc
        .perform(
            graphqlRequest(
                bearer, "query { encoderPresetSetting { preset automatic { h264 hevc av1 } } }"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.encoderPresetSetting.preset").value("MEDIUM"))
        .andExpect(jsonPath("$.data.encoderPresetSetting.automatic.h264").value("FASTER"))
        .andExpect(jsonPath("$.data.encoderPresetSetting.automatic.hevc").value("VERYFAST"))
        .andExpect(jsonPath("$.data.encoderPresetSetting.automatic.av1").value("FASTER"));

    var stored = dsl.selectFrom(SERVER_SETTINGS).fetchSingle();
    assertThat(stored.getEncoderPreset()).isEqualTo("MEDIUM");
    assertThat(stored.getLastModifiedBy()).isEqualTo(serverAdmin.account().getId());
  }

  @Test
  @DisplayName("Should refuse the change and keep Automatic when the caller is not a ServerAdmin")
  void shouldRefuseChangeAndKeepAutomaticWhenCallerIsNotServerAdmin() throws Exception {
    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(resident), CHANGE_TO_MEDIUM))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

    var stored = dsl.selectFrom(SERVER_SETTINGS).fetchSingle();
    assertThat(stored.getEncoderPreset()).isEqualTo("AUTOMATIC");
    assertThat(stored.getLastModifiedBy()).isNull();
  }
}
