package com.streamarr.server.services.serversettings;

import com.streamarr.server.domain.ServerSettings;
import com.streamarr.server.domain.streaming.EncoderPreset;
import com.streamarr.server.domain.streaming.EncoderPresetChoice;
import com.streamarr.server.exceptions.ServerSettingsMissingException;
import com.streamarr.server.repositories.ServerSettingsRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Duration;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ServerSettingsService {

  private final AuthorizationService authorizationService;
  private final ServerSettingsRepository serverSettingsRepository;

  public EncoderPresetSetting readEncoderPresetSetting(@NonNull AuthenticatedIdentity identity) {
    authorizationService.requireAllowed(identity, new Intent.ViewEncoderPreset());
    return EncoderPresetSetting.of(storedSettings().getEncoderPreset());
  }

  /** Last write wins; the audit columns record the ServerAdmin who made the change. */
  @Transactional
  public Outcome<EncoderPresetSetting, ChangeEncoderPresetRejection> changeEncoderPreset(
      @NonNull AuthenticatedIdentity identity, @NonNull String preset) {
    authorizationService.requireAllowed(identity, new Intent.ChangeEncoderPreset());
    var choice = EncoderPresetChoice.named(preset);
    if (choice.isEmpty()) {
      return Outcome.rejected(new ChangeEncoderPresetRejection.UnknownPreset());
    }

    var settings = storedSettings();
    settings.setEncoderPreset(choice.get());
    serverSettingsRepository.save(settings);
    return Outcome.accepted(EncoderPresetSetting.of(choice.get()));
  }

  /**
   * The preset a new variant of this video codec family runs. Dispatch reads the setting for the
   * system, not for a caller, so this read is not authorized.
   */
  public EncoderPreset resolveEncoderPreset(@NonNull String videoCodecFamily) {
    return storedSettings().getEncoderPreset().resolveFor(videoCodecFamily);
  }

  public IdleSegmentRetentionSetting readIdleSegmentRetentionSetting(
      @NonNull AuthenticatedIdentity identity) {
    authorizationService.requireAllowed(identity, new Intent.ViewIdleSegmentRetention());
    return storedIdleSegmentRetention();
  }

  /** Last write wins; the audit columns record the ServerAdmin who made the change. */
  @Transactional
  public Outcome<IdleSegmentRetentionSetting, ChangeIdleSegmentRetentionRejection>
      changeIdleSegmentRetention(@NonNull AuthenticatedIdentity identity, int minutes) {
    authorizationService.requireAllowed(identity, new Intent.ChangeIdleSegmentRetention());
    if (!IdleSegmentRetentionSetting.allows(minutes)) {
      return Outcome.rejected(new ChangeIdleSegmentRetentionRejection.OutOfRange());
    }

    var settings = storedSettings();
    settings.setIdleSegmentRetentionMinutes(minutes);
    serverSettingsRepository.save(settings);
    return Outcome.accepted(idleSegmentRetentionOf(settings));
  }

  /**
   * How long the server keeps a stream session, and its segments, after the last request for it.
   * The session reaper and playback tokens read the setting for the system, not for a caller, so
   * this read is not authorized.
   */
  public Duration idleSegmentRetention() {
    return storedIdleSegmentRetention().duration();
  }

  public NotificationRetentionSetting readNotificationRetentionSetting(
      @NonNull AuthenticatedIdentity identity) {
    authorizationService.requireAllowed(identity, new Intent.ViewNotificationRetention());
    return notificationRetentionOf(storedSettings());
  }

  /** Last write wins; the audit columns record the ServerAdmin who made the change. */
  @Transactional
  public Outcome<NotificationRetentionSetting, ChangeNotificationRetentionRejection>
      changeNotificationRetention(@NonNull AuthenticatedIdentity identity, int days) {
    authorizationService.requireAllowed(identity, new Intent.ChangeNotificationRetention());
    if (!NotificationRetentionSetting.allows(days)) {
      return Outcome.rejected(new ChangeNotificationRetentionRejection.OutOfRange());
    }

    var settings = storedSettings();
    settings.setNotificationRetentionDays(days);
    serverSettingsRepository.save(settings);
    return Outcome.accepted(notificationRetentionOf(settings));
  }

  /**
   * How long the server keeps Notification history, the news that its recipient read and the
   * requests that resolved, after its latest event. The retention sweep reads the setting for the
   * system, not for a caller, so this read is not authorized.
   */
  public Duration notificationRetention() {
    return notificationRetentionOf(storedSettings()).duration();
  }

  private static NotificationRetentionSetting notificationRetentionOf(ServerSettings settings) {
    return new NotificationRetentionSetting(
        settings.getId(), settings.getNotificationRetentionDays());
  }

  public LifetimeSetting readShareCodeLifetimeSetting(@NonNull AuthenticatedIdentity identity) {
    authorizationService.requireAllowed(identity, new Intent.ViewShareCodeLifetime());
    return shareCodeLifetimeOf(storedSettings());
  }

  /** Last write wins; the audit columns record the ServerAdmin who made the change. */
  @Transactional
  public Outcome<LifetimeSetting, ChangeLifetimeRejection> changeShareCodeLifetime(
      @NonNull AuthenticatedIdentity identity, int hours) {
    authorizationService.requireAllowed(identity, new Intent.ChangeShareCodeLifetime());
    if (!LifetimeSetting.allows(hours)) {
      return Outcome.rejected(new ChangeLifetimeRejection.OutOfRange());
    }

    var settings = storedSettings();
    settings.setShareCodeLifetimeHours(hours);
    serverSettingsRepository.save(settings);
    return Outcome.accepted(shareCodeLifetimeOf(settings));
  }

  /**
   * How long a share code stays live after it is issued. Issuing reads the setting for the code,
   * not for a caller, so this read is not authorized.
   */
  public Duration shareCodeLifetime() {
    return shareCodeLifetimeOf(storedSettings()).duration();
  }

  private static LifetimeSetting shareCodeLifetimeOf(ServerSettings settings) {
    return new LifetimeSetting(settings.getId(), settings.getShareCodeLifetimeHours());
  }

  private IdleSegmentRetentionSetting storedIdleSegmentRetention() {
    return idleSegmentRetentionOf(storedSettings());
  }

  private static IdleSegmentRetentionSetting idleSegmentRetentionOf(ServerSettings settings) {
    return new IdleSegmentRetentionSetting(
        settings.getId(), settings.getIdleSegmentRetentionMinutes());
  }

  private ServerSettings storedSettings() {
    return serverSettingsRepository.findAll().stream()
        .findFirst()
        .orElseThrow(ServerSettingsMissingException::new);
  }
}
