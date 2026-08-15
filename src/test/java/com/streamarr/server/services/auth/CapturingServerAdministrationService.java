package com.streamarr.server.services.auth;

import java.util.UUID;

public final class CapturingServerAdministrationService extends ServerAdministrationService {

  private ForceProfileDeletionCommand deletion;
  private ForceProfileUnshareCommand unshare;
  private ProfileManagerOverrideCommand override;

  public CapturingServerAdministrationService() {
    super(null, null, null, null, null, null, null, null, null, null);
  }

  @Override
  public PreparedForceProfileDeletion prepare(ForceProfileDeletionCommand command) {
    deletion = command;
    return new PreparedForceProfileDeletion(
        command.actingAccountId(),
        command.profileId(),
        command.reason(),
        authority(command.actingAccountId()));
  }

  @Override
  public void forceDeleteProfile(PreparedForceProfileDeletion command) {}

  @Override
  public PreparedForceProfileUnshare prepare(ForceProfileUnshareCommand command) {
    unshare = command;
    return new PreparedForceProfileUnshare(
        command.actingAccountId(),
        command.shareId(),
        command.reason(),
        authority(command.actingAccountId()));
  }

  @Override
  public void forceUnshareProfile(PreparedForceProfileUnshare command) {}

  @Override
  public PreparedProfileManagerOverride prepare(ProfileManagerOverrideCommand command) {
    override = command;
    return new PreparedProfileManagerOverride(
        command.actingAccountId(),
        command.targetAccountId(),
        command.profileId(),
        command.action(),
        command.reason(),
        authority(command.actingAccountId()));
  }

  @Override
  public void overrideProfileManager(PreparedProfileManagerOverride command) {}

  public ForceProfileDeletionCommand deletion() {
    return deletion;
  }

  public ForceProfileUnshareCommand unshare() {
    return unshare;
  }

  public ProfileManagerOverrideCommand override() {
    return override;
  }

  private PasswordReauthentication authority(UUID accountId) {
    return new PasswordReauthentication(accountId);
  }
}
