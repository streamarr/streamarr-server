package com.streamarr.server.services.serversettings;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.accountScopedBuilder;
import static com.streamarr.server.support.OutcomeTestSupport.accepted;
import static com.streamarr.server.support.OutcomeTestSupport.rejectionOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.EncoderPresetChoice;
import com.streamarr.server.exceptions.ServerSettingsMissingException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeServerSettingsRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.Intent;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Server Settings Service Tests")
class ServerSettingsServiceTest {

  private final AuthenticatedIdentity identity = accountScopedBuilder().build();
  private final FakeAuthorizationService authorizationService =
      new FakeAuthorizationService(identity);
  private final FakeServerSettingsRepository serverSettingsRepository =
      new FakeServerSettingsRepository();
  private final ServerSettingsService serverSettingsService =
      new ServerSettingsService(authorizationService, serverSettingsRepository);

  @Test
  @DisplayName("Should resolve Automatic per codec when no ServerAdmin has changed the setting")
  void shouldResolveAutomaticPerCodecWhenNoServerAdminHasChangedSetting() {
    var setting = serverSettingsService.readEncoderPresetSetting(identity);

    assertThat(setting.preset()).isEqualTo(EncoderPresetChoice.AUTOMATIC);
    assertThat(setting.automatic())
        .isEqualTo(
            new AutomaticEncoderPresets(
                EncoderPreset.FASTER, EncoderPreset.VERYFAST, EncoderPreset.FASTER));
    assertThat(serverSettingsService.resolveEncoderPreset("h264")).isEqualTo(EncoderPreset.FASTER);
    assertThat(serverSettingsService.resolveEncoderPreset("hevc"))
        .isEqualTo(EncoderPreset.VERYFAST);
    assertThat(serverSettingsService.resolveEncoderPreset("av1")).isEqualTo(EncoderPreset.FASTER);
    assertThat(authorizationService.recordedIntents())
        .containsExactly(new Intent.ViewEncoderPreset());
  }

  @Test
  @DisplayName("Should apply one fixed preset to every codec when a ServerAdmin sets it")
  void shouldApplyFixedPresetToEveryCodecWhenServerAdminSetsIt() {
    var setting = accepted(serverSettingsService.changeEncoderPreset(identity, "MEDIUM"));

    assertThat(setting.preset()).isEqualTo(EncoderPresetChoice.MEDIUM);
    assertThat(serverSettingsService.readEncoderPresetSetting(identity).preset())
        .isEqualTo(EncoderPresetChoice.MEDIUM);
    assertThat(serverSettingsService.resolveEncoderPreset("h264")).isEqualTo(EncoderPreset.MEDIUM);
    assertThat(serverSettingsService.resolveEncoderPreset("hevc")).isEqualTo(EncoderPreset.MEDIUM);
    assertThat(serverSettingsService.resolveEncoderPreset("av1")).isEqualTo(EncoderPreset.MEDIUM);
    assertThat(authorizationService.recordedIntents()).startsWith(new Intent.ChangeEncoderPreset());
  }

  @Test
  @DisplayName("Should keep reporting what Automatic uses when a fixed preset is set")
  void shouldKeepReportingAutomaticPresetsWhenFixedPresetIsSet() {
    var setting = accepted(serverSettingsService.changeEncoderPreset(identity, "SLOW"));

    assertThat(setting.automatic())
        .isEqualTo(
            new AutomaticEncoderPresets(
                EncoderPreset.FASTER, EncoderPreset.VERYFAST, EncoderPreset.FASTER));
  }

  @Test
  @DisplayName("Should return to Automatic when a ServerAdmin chooses Automatic again")
  void shouldReturnToAutomaticWhenServerAdminChoosesAutomaticAgain() {
    accepted(serverSettingsService.changeEncoderPreset(identity, "VERYSLOW"));

    var setting = accepted(serverSettingsService.changeEncoderPreset(identity, "AUTOMATIC"));

    assertThat(setting.preset()).isEqualTo(EncoderPresetChoice.AUTOMATIC);
    assertThat(serverSettingsService.resolveEncoderPreset("hevc"))
        .isEqualTo(EncoderPreset.VERYFAST);
  }

  @Test
  @DisplayName("Should refuse the change when the caller is not a ServerAdmin")
  void shouldRefuseChangeWhenCallerIsNotServerAdmin() {
    authorizationService.denyAll();

    assertThatThrownBy(() -> serverSettingsService.changeEncoderPreset(identity, "MEDIUM"))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(serverSettingsService.resolveEncoderPreset("h264")).isEqualTo(EncoderPreset.FASTER);
  }

  @Test
  @DisplayName("Should refuse the read when the caller is not a ServerAdmin")
  void shouldRefuseReadWhenCallerIsNotServerAdmin() {
    authorizationService.denyAll();

    assertThatThrownBy(() -> serverSettingsService.readEncoderPresetSetting(identity))
        .isInstanceOf(AccessDeniedException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "medium", "PLACEBO", "10", "ULTRAFAST"})
  @DisplayName("Should reject the change and keep the setting when the preset is not on the scale")
  void shouldRejectChangeAndKeepSettingWhenPresetIsNotOnScale(String preset) {
    var outcome = serverSettingsService.changeEncoderPreset(identity, preset);

    assertThat(rejectionOf(outcome)).isEqualTo(new ChangeEncoderPresetRejection.UnknownPreset());
    assertThat(serverSettingsService.resolveEncoderPreset("h264")).isEqualTo(EncoderPreset.FASTER);
  }

  @Test
  @DisplayName(
      "Should refuse a caller who is not a ServerAdmin when the preset name is also unknown")
  void shouldRefuseCallerWhoIsNotServerAdminWhenPresetNameIsAlsoUnknown() {
    authorizationService.denyAll();

    assertThatThrownBy(() -> serverSettingsService.changeEncoderPreset(identity, "PLACEBO"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should keep idle stream sessions for one hour when no ServerAdmin has changed it")
  void shouldKeepIdleStreamSessionsForOneHourWhenNoServerAdminHasChangedIt() {
    assertThat(serverSettingsService.idleSegmentRetention()).isEqualTo(Duration.ofHours(1));
  }

  @Test
  @DisplayName(
      "Should report one hour and the allowed range when a ServerAdmin reads the retention")
  void shouldReportOneHourAndAllowedRangeWhenServerAdminReadsRetention() {
    var setting = serverSettingsService.readIdleSegmentRetentionSetting(identity);

    assertThat(setting.minutes()).isEqualTo(60);
    assertThat(setting.minimumMinutes()).isEqualTo(5);
    assertThat(setting.maximumMinutes()).isEqualTo(10_080);
    assertThat(authorizationService.recordedIntents())
        .containsExactly(new Intent.ViewIdleSegmentRetention());
  }

  @ParameterizedTest
  @ValueSource(ints = {5, 30, 10_080})
  @DisplayName("Should keep idle stream sessions for the new time when a ServerAdmin changes it")
  void shouldKeepIdleStreamSessionsForNewTimeWhenServerAdminChangesIt(int minutes) {
    var setting = accepted(serverSettingsService.changeIdleSegmentRetention(identity, minutes));

    assertThat(setting.minutes()).isEqualTo(minutes);
    assertThat(serverSettingsService.readIdleSegmentRetentionSetting(identity).minutes())
        .isEqualTo(minutes);
    assertThat(serverSettingsService.idleSegmentRetention()).isEqualTo(Duration.ofMinutes(minutes));
    assertThat(authorizationService.recordedIntents())
        .startsWith(new Intent.ChangeIdleSegmentRetention());
  }

  @Test
  @DisplayName(
      "Should identify the setting by the stored settings id when a ServerAdmin reads and changes"
          + " the retention")
  void shouldIdentifySettingByStoredSettingsIdWhenServerAdminReadsAndChangesRetention() {
    var settingsId = serverSettingsRepository.findAll().getFirst().getId();

    var read = serverSettingsService.readIdleSegmentRetentionSetting(identity);
    var changed = accepted(serverSettingsService.changeIdleSegmentRetention(identity, 30));

    assertThat(settingsId).isNotNull();
    assertThat(read.id()).isEqualTo(settingsId);
    assertThat(changed.id()).isEqualTo(settingsId);
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -60, 0, 4, 10_081, Integer.MAX_VALUE})
  @DisplayName("Should reject the change and keep one hour when the retention is out of range")
  void shouldRejectChangeAndKeepOneHourWhenRetentionIsOutOfRange(int minutes) {
    var outcome = serverSettingsService.changeIdleSegmentRetention(identity, minutes);

    assertThat(rejectionOf(outcome))
        .isEqualTo(new ChangeIdleSegmentRetentionRejection.OutOfRange());
    assertThat(serverSettingsService.idleSegmentRetention()).isEqualTo(Duration.ofHours(1));
  }

  @ParameterizedTest
  @ValueSource(ints = {30, 4})
  @DisplayName(
      "Should refuse a caller who is not a ServerAdmin and keep one hour when the caller changes"
          + " the retention")
  void shouldRefuseCallerWhoIsNotServerAdminAndKeepOneHourWhenCallerChangesRetention(int minutes) {
    authorizationService.denyAll();

    assertThatThrownBy(() -> serverSettingsService.changeIdleSegmentRetention(identity, minutes))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(serverSettingsService.idleSegmentRetention()).isEqualTo(Duration.ofHours(1));
  }

  @Test
  @DisplayName("Should refuse the retention read when the caller is not a ServerAdmin")
  void shouldRefuseRetentionReadWhenCallerIsNotServerAdmin() {
    authorizationService.denyAll();

    assertThatThrownBy(() -> serverSettingsService.readIdleSegmentRetentionSetting(identity))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName(
      "Should keep Notification history for ninety days when no ServerAdmin has changed it")
  void shouldKeepNotificationHistoryForNinetyDaysWhenNoServerAdminHasChangedIt() {
    assertThat(serverSettingsService.notificationRetention()).isEqualTo(Duration.ofDays(90));
  }

  @Test
  @DisplayName("Should keep Notification history for the stored days when the setting was changed")
  void shouldKeepNotificationHistoryForTheStoredDaysWhenTheSettingWasChanged() {
    var settings = serverSettingsRepository.findAll().getFirst();
    settings.setNotificationRetentionDays(30);
    serverSettingsRepository.save(settings);

    assertThat(serverSettingsService.notificationRetention()).isEqualTo(Duration.ofDays(30));
  }

  @Test
  @DisplayName("Should fail with a missing-settings error when the settings row is absent")
  void shouldFailWithMissingSettingsErrorWhenSettingsRowIsAbsent() {
    serverSettingsRepository.deleteAll();

    assertThatThrownBy(() -> serverSettingsService.resolveEncoderPreset("h264"))
        .isInstanceOf(ServerSettingsMissingException.class);
  }
}
