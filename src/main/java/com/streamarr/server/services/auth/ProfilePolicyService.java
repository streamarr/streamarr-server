package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.ProfileManagementDeniedException;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfilePolicyService {

  private final ProfileRepository profileRepository;
  private final ProfileManagerRepository managerRepository;
  private final HouseholdProfileSafetyService safetyService;
  private final KidProfileManagerPolicy kidManagerPolicy;
  private final SecurityAuditService auditService;

  /**
   * Changes the kind of a managed profile.
   *
   * @param command the requested profile-kind change and acting account
   */
  @Transactional
  public void setKind(SetProfileKindCommand command) {
    var profile = requireManagedProfile(command.actingAccountId(), command.profileId());
    var proposed = profile.toBuilder().kind(command.kind()).build();
    safetyService.validatePolicyChange(proposed);
    if (command.kind() == ProfileKind.KID) {
      kidManagerPolicy.validateKidKind(command.profileId());
    }

    profile.setKind(command.kind());
    saveAndAudit(profile, command.actingAccountId(), SecurityAuditOperation.PROFILE_KIND_CHANGED);
  }

  /**
   * Sets the maximum content-rating age allowed for a managed profile.
   *
   * @param command the account, profile, and content-rating age to apply
   */
  @Transactional
  public void setContentCeiling(SetProfileContentCeilingCommand command) {
    var profile = requireManagedProfile(command.actingAccountId(), command.profileId());
    var proposed =
        profile.toBuilder().maximumAllowedRatingAge(command.maximumAllowedRatingAge()).build();
    safetyService.validatePolicyChange(proposed);

    profile.setMaximumAllowedRatingAge(command.maximumAllowedRatingAge());
    saveAndAudit(
        profile, command.actingAccountId(), SecurityAuditOperation.PROFILE_CONTENT_CEILING_SET);
  }

  /**
   * Removes the maximum content-rating age restriction from a managed profile.
   *
   * @param command the request containing the acting account and profile identifiers
   */
  @Transactional
  public void removeContentCeiling(RemoveProfileContentCeilingCommand command) {
    var profile = requireManagedProfile(command.actingAccountId(), command.profileId());
    var proposed = profile.toBuilder().maximumAllowedRatingAge(null).build();
    safetyService.validatePolicyChange(proposed);

    profile.setMaximumAllowedRatingAge(null);
    saveAndAudit(
        profile, command.actingAccountId(), SecurityAuditOperation.PROFILE_CONTENT_CEILING_REMOVED);
  }

  /**
   * Resets the PIN for a managed profile.
   *
   * @param command the command containing the acting account, profile, and new PIN hash
   */
  @Transactional
  public void resetPin(ResetProfilePinCommand command) {
    var profile = requireManagedProfile(command.actingAccountId(), command.profileId());
    var proposed = profile.toBuilder().pinHash(command.pinHash()).build();
    safetyService.validatePolicyChange(proposed);

    profile.setPinHash(command.pinHash());
    saveAndAudit(profile, command.actingAccountId(), SecurityAuditOperation.PROFILE_PIN_RESET);
  }

  /**
   * Retrieves a profile managed by the specified account.
   *
   * @param actingAccountId the account requesting access
   * @param profileId the profile to retrieve
   * @return the managed profile
   * @throws ProfileManagementDeniedException if the account does not manage the profile
   * @throws ProfileAccessDeniedException if the profile cannot be found
   */
  private Profile requireManagedProfile(UUID actingAccountId, UUID profileId) {
    if (!managerRepository.existsByAccountIdAndProfileId(actingAccountId, profileId)) {
      throw new ProfileManagementDeniedException();
    }
    return profileRepository.findById(profileId).orElseThrow(ProfileAccessDeniedException::new);
  }

  /**
   * Saves the profile and records the corresponding security audit event.
   *
   * @param profile the profile to save
   * @param actingAccountId the account that performed the operation
   * @param operation the audited security operation
   */
  private void saveAndAudit(
      Profile profile, UUID actingAccountId, SecurityAuditOperation operation) {
    profileRepository.save(profile);
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(actingAccountId)
            .targetProfileId(profile.getId())
            .operation(operation)
            .build());
  }
}
