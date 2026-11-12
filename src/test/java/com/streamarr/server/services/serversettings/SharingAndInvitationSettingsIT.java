package com.streamarr.server.services.serversettings;

import static com.streamarr.server.jooq.generated.tables.ServerSettings.SERVER_SETTINGS;
import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static com.streamarr.server.support.ServerSettingsTestSupport.resetServerSettings;
import static com.streamarr.server.support.ServerSettingsTestSupport.storeSetting;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.SystemActor;
import com.streamarr.server.jooq.generated.tables.records.ServerSettingsRecord;
import com.streamarr.server.support.AuthTestSupport;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.jooq.DSLContext;
import org.jooq.TableField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The settings of the Server settings section "Sharing and invitations" (ADR 0024 §Share codes,
 * §Invitations), each read and changed only by a ServerAdmin, as the server's other settings are.
 */
@Tag("IntegrationTest")
@DisplayName("Sharing And Invitation Settings Integration Tests")
class SharingAndInvitationSettingsIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity serverAdmin;
  private AuthTestSupport.TestIdentity resident;

  static Stream<Setting> settings() {
    return Stream.of(
        Setting.builder()
            .name("ShareCodeLifetime")
            .value("hours")
            .defaultValue(168)
            .minimum(1)
            .maximum(720)
            .changedTo(24)
            .outOfRangeError("LifetimeOutOfRangeError")
            .outOfRangeMessage("Choose from 1 to 720 hours.")
            .column(SERVER_SETTINGS.SHARE_CODE_LIFETIME_HOURS)
            .constraint("chk_server_settings_share_code_lifetime_hours")
            .build(),
        Setting.builder()
            .name("InvitationLifetime")
            .value("hours")
            .defaultValue(168)
            .minimum(1)
            .maximum(720)
            .changedTo(48)
            .outOfRangeError("LifetimeOutOfRangeError")
            .outOfRangeMessage("Choose from 1 to 720 hours.")
            .column(SERVER_SETTINGS.INVITATION_LIFETIME_HOURS)
            .constraint("chk_server_settings_invitation_lifetime_hours")
            .build(),
        Setting.builder()
            .name("ShareCodeLimit")
            .value("codes")
            .defaultValue(10)
            .minimum(1)
            .maximum(100)
            .changedTo(3)
            .outOfRangeError("ShareCodeLimitOutOfRangeError")
            .outOfRangeMessage("Choose from 1 to 100 codes.")
            .column(SERVER_SETTINGS.SHARE_CODE_LIMIT)
            .constraint("chk_server_settings_share_code_limit")
            .build());
  }

  static Stream<Arguments> endsOfEachRange() {
    return settings()
        .flatMap(
            setting ->
                Stream.of(
                    Arguments.of(setting, setting.minimum()),
                    Arguments.of(setting, setting.maximum())));
  }

  static Stream<Arguments> valuesJustOutsideEachRange() {
    return settings()
        .flatMap(
            setting ->
                Stream.of(
                    Arguments.of(setting, setting.minimum() - 1),
                    Arguments.of(setting, setting.maximum() + 1)));
  }

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

  @ParameterizedTest
  @MethodSource("settings")
  @DisplayName(
      "Should save the setting, read it back under the same id, and record the ServerAdmin when a"
          + " ServerAdmin changes it")
  void shouldSaveTheSettingReadItBackUnderTheSameIdAndRecordTheServerAdminWhenAServerAdminChangesIt(
      Setting setting) throws Exception {
    var bearer = authTestSupport.accountBearer(serverAdmin);
    var settingId = dsl.selectFrom(SERVER_SETTINGS).fetchSingle().getId().toString();

    mockMvc
        .perform(graphqlRequest(bearer, setting.change(setting.changedTo())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath(setting.changed(setting.value())).value(setting.changedTo()))
        .andExpect(jsonPath(setting.changed("id")).value(settingId))
        .andExpect(jsonPath(setting.changeErrors()).isEmpty());

    mockMvc
        .perform(graphqlRequest(bearer, setting.read()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath(setting.readField("id")).value(settingId))
        .andExpect(jsonPath(setting.readField(setting.value())).value(setting.changedTo()))
        .andExpect(jsonPath(setting.readField(setting.bound("minimum"))).value(setting.minimum()))
        .andExpect(jsonPath(setting.readField(setting.bound("maximum"))).value(setting.maximum()));

    var stored = dsl.selectFrom(SERVER_SETTINGS).fetchSingle();
    assertThat(stored.get(setting.column())).isEqualTo(setting.changedTo());
    assertThat(stored.getLastModifiedBy()).isEqualTo(serverAdmin.account().getId());
  }

  @ParameterizedTest
  @MethodSource("endsOfEachRange")
  @DisplayName("Should save the setting when a ServerAdmin changes it to either end of its range")
  void shouldSaveTheSettingWhenAServerAdminChangesItToEitherEndOfItsRange(Setting setting, int end)
      throws Exception {
    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(serverAdmin), setting.change(end)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath(setting.changed(setting.value())).value(end))
        .andExpect(jsonPath(setting.changeErrors()).isEmpty());

    assertThat(storedValueOf(setting)).isEqualTo(end);
  }

  @ParameterizedTest
  @MethodSource("valuesJustOutsideEachRange")
  @DisplayName(
      "Should refuse a value outside the range and keep the default when it is stored past the"
          + " service")
  void shouldRefuseValueOutsideTheRangeAndKeepTheDefaultWhenItIsStoredPastTheService(
      Setting setting, int value) {
    assertThatThrownBy(() -> storeSetting(dsl, setting.column(), value))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining(setting.constraint());

    assertThat(storedValueOf(setting)).isEqualTo(setting.defaultValue());
  }

  @ParameterizedTest
  @MethodSource("settings")
  @DisplayName("Should reject the change and keep the default when the value is out of range")
  void shouldRejectTheChangeAndKeepTheDefaultWhenTheValueIsOutOfRange(Setting setting)
      throws Exception {
    mockMvc
        .perform(
            graphqlRequest(
                authTestSupport.accountBearer(serverAdmin), setting.change(setting.maximum() + 1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath(setting.changed("id")).doesNotExist())
        .andExpect(
            jsonPath(setting.changeErrors() + "[0].__typename").value(setting.outOfRangeError()))
        .andExpect(
            jsonPath(setting.changeErrors() + "[0].message").value(setting.outOfRangeMessage()))
        .andExpect(jsonPath(setting.changeErrors() + "[0].inputPath[0]").value(setting.value()));

    assertThat(storedValueOf(setting)).isEqualTo(setting.defaultValue());
  }

  @ParameterizedTest
  @MethodSource("settings")
  @DisplayName(
      "Should refuse the read and the change and keep the default when the caller is not a"
          + " ServerAdmin")
  void shouldRefuseTheReadAndTheChangeAndKeepTheDefaultWhenTheCallerIsNotAServerAdmin(
      Setting setting) throws Exception {
    var bearer = authTestSupport.accountBearer(resident);

    mockMvc
        .perform(graphqlRequest(bearer, setting.change(setting.changedTo())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
    mockMvc
        .perform(graphqlRequest(bearer, setting.read()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

    var stored = dsl.selectFrom(SERVER_SETTINGS).fetchSingle();
    assertThat(stored.get(setting.column())).isEqualTo(setting.defaultValue());
    assertThat(stored.getLastModifiedBy()).isEqualTo(SystemActor.ID);
  }

  private int storedValueOf(Setting setting) {
    return dsl.selectFrom(SERVER_SETTINGS).fetchSingle().get(setting.column());
  }

  /**
   * One setting as the schema names it: {@code <name>Setting} is read, {@code change<name>} changes
   * it, and its value field has a minimum and a maximum field beside it. It is stored in {@code
   * column}, whose range {@code constraint} holds.
   */
  @Builder
  record Setting(
      @NonNull String name,
      @NonNull String value,
      int defaultValue,
      int minimum,
      int maximum,
      int changedTo,
      @NonNull String outOfRangeError,
      @NonNull String outOfRangeMessage,
      @NonNull TableField<ServerSettingsRecord, Integer> column,
      @NonNull String constraint) {

    String read() {
      return "query { %s { id %s %s %s } }"
          .formatted(settingField(), value, bound("minimum"), bound("maximum"));
    }

    String change(int to) {
      return """
          mutation { change%s(input: {%s: %d}) {
            %s { id %s }
            userErrors { __typename ... on InputMutationError { message inputPath } } } }
          """
          .formatted(name, value, to, settingField(), value);
    }

    String readField(String field) {
      return "$.data.%s.%s".formatted(settingField(), field);
    }

    String changed(String field) {
      return "$.data.change%s.%s.%s".formatted(name, settingField(), field);
    }

    String changeErrors() {
      return "$.data.change%s.userErrors".formatted(name);
    }

    String bound(String which) {
      return which + Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String settingField() {
      return Character.toLowerCase(name.charAt(0)) + name.substring(1) + "Setting";
    }

    @Override
    public String toString() {
      return name;
    }
  }
}
