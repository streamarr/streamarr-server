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

  private ServerSettings storedSettings() {
    return serverSettingsRepository.findAll().stream()
        .findFirst()
        .orElseThrow(ServerSettingsMissingException::new);
  }
}
