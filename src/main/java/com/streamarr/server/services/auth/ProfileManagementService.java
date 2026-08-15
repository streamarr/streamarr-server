package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.exceptions.ProfileManagementDeniedException;
import com.streamarr.server.exceptions.ProfileManagerInvariantException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileManagementService {

  private final ProfileManagerRepository managerRepository;
  private final ProfileManagerInvitationRepository invitationRepository;
  private final KidProfileManagerPolicy kidManagerPolicy;
  private final SecurityAuditService auditService;
  private final UserAccountRepository accountRepository;
  private final ProfileRepository profileRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final HouseholdProfileSafetyService safetyService;

  /**
   * Creates a portable profile and assigns the acting account as its manager.
   *
   * @param command the profile details and acting account identifier
   * @return the created profile
   */
  @Transactional
  public Profile create(CreatePortableProfileCommand command) {
    requireName(command.name());
    var account =
        accountRepository
            .findById(command.actingAccountId())
            .filter(candidate -> candidate.isEnabled())
            .orElseThrow(ProfileManagementDeniedException::new);
    var profile =
        profileRepository.save(
            Profile.builder()
                .name(command.name())
                .kind(command.kind())
                .maximumAllowedRatingAge(command.maximumAllowedRatingAge())
                .pinHash(command.pinHash())
                .build());
    managerRepository.save(
        ProfileManager.builder().accountId(account.getId()).profileId(profile.getId()).build());
    kidManagerPolicy.validateShareActivation(profile.getId(), account.getHomeHouseholdId());
    safetyService.validateActivation(profile, account.getHomeHouseholdId());
    shareRepository.save(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(account.getHomeHouseholdId())
            .status(ProfileShareStatus.ACTIVE)
            .build());
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(account.getId())
            .targetHouseholdId(account.getHomeHouseholdId())
            .targetProfileId(profile.getId())
            .operation(SecurityAuditOperation.PROFILE_CREATED)
            .build());
    return profile;
  }

  /**
   * Renames a portable profile.
   *
   * @param command the rename request containing the profile, new name, and acting account
   */
  @Transactional
  public void rename(RenamePortableProfileCommand command) {
    requireName(command.name());
    requireManager(command.actingAccountId(), command.profileId());
    var profile =
        profileRepository
            .findById(command.profileId())
            .orElseThrow(ProfileManagementDeniedException::new);
    profile.setName(command.name());
    profileRepository.save(profile);
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(command.actingAccountId())
            .targetProfileId(command.profileId())
            .operation(SecurityAuditOperation.PROFILE_RENAMED)
            .build());
  }

  /**
   * Invites an eligible account to manage a profile.
   *
   * @param invite the profile and accounts involved in the invitation
   * @return the pending invitation
   */
  @Transactional
  public ProfileManagerInvitation invite(ProfileManagerInvite invite) {
    requireManager(invite.actingAccountId(), invite.profileId());
    requireEligibleInvitee(invite);

    var result =
        invitationRepository.insertPendingIfAbsent(
            invite.profileId(), invite.actingAccountId(), invite.invitedAccountId());
    if (result.inserted()) {
      auditService.recordEvent(
          SecurityAuditRecord.builder()
              .actingAccountId(invite.actingAccountId())
              .targetAccountId(invite.invitedAccountId())
              .targetProfileId(invite.profileId())
              .operation(SecurityAuditOperation.PROFILE_MANAGER_INVITED)
              .build());
    }
    return result.invitation();
  }

  private void requireEligibleInvitee(ProfileManagerInvite invite) {
    if (invite.actingAccountId().equals(invite.invitedAccountId())) {
      throw new ProfileManagementDeniedException();
    }
    if (managerRepository.existsByAccountIdAndProfileId(
        invite.invitedAccountId(), invite.profileId())) {
      throw new ProfileManagementDeniedException();
    }
    accountRepository
        .findById(invite.invitedAccountId())
        .filter(candidate -> candidate.isEnabled())
        .orElseThrow(ProfileManagementDeniedException::new);
  }

  /**
   * Accepts a pending profile manager invitation for the acting account.
   *
   * @param acceptance the invitation acceptance request
   * @return the created profile manager association
   */
  @Transactional
  public ProfileManager accept(ProfileManagerInvitationAcceptance acceptance) {
    return acceptForProfile(acceptance, null);
  }

  /**
   * Rejects a pending profile manager invitation addressed to the acting account.
   *
   * @param rejection the invitation rejection request
   */
  @Transactional
  public void reject(ProfileManagerInvitationRejection rejection) {
    var invitation =
        invitationRepository
            .findById(rejection.invitationId())
            .filter(candidate -> candidate.getStatus() == ProfileManagerInvitationStatus.PENDING)
            .filter(
                candidate -> candidate.getInvitedAccountId().equals(rejection.actingAccountId()))
            .orElseThrow(ProfileManagementDeniedException::new);
    invitation.setStatus(ProfileManagerInvitationStatus.REJECTED);
    invitationRepository.save(invitation);
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(rejection.actingAccountId())
            .targetProfileId(invitation.getProfileId())
            .operation(SecurityAuditOperation.PROFILE_MANAGER_INVITATION_REJECTED)
            .build());
  }

  /**
   * Cancels a pending profile manager invitation.
   *
   * @param cancellation identifies the invitation and the account requesting cancellation
   */
  @Transactional
  public void cancel(ProfileManagerInvitationCancellation cancellation) {
    var invitation =
        invitationRepository
            .findById(cancellation.invitationId())
            .filter(candidate -> candidate.getStatus() == ProfileManagerInvitationStatus.PENDING)
            .orElseThrow(ProfileManagementDeniedException::new);
    requireManager(cancellation.actingAccountId(), invitation.getProfileId());
    invitation.setStatus(ProfileManagerInvitationStatus.CANCELED);
    invitationRepository.save(invitation);
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(cancellation.actingAccountId())
            .targetAccountId(invitation.getInvitedAccountId())
            .targetProfileId(invitation.getProfileId())
            .operation(SecurityAuditOperation.PROFILE_MANAGER_INVITATION_CANCELED)
            .build());
  }

  /**
   * Accepts a pending invitation for the acting account and creates its profile manager association.
   *
   * @param acceptance       the invitation acceptance details and acting account
   * @param expectedProfileId the profile ID the invitation must target, or {@code null} to allow any profile
   * @return the created profile manager association
   * @throws ProfileManagementDeniedException if the invitation is unavailable, not pending, addressed to another account, or targets a different profile
   */
  ProfileManager acceptForProfile(
      ProfileManagerInvitationAcceptance acceptance, UUID expectedProfileId) {
    var invitation =
        invitationRepository
            .findById(acceptance.invitationId())
            .filter(candidate -> candidate.getStatus() == ProfileManagerInvitationStatus.PENDING)
            .filter(
                candidate -> candidate.getInvitedAccountId().equals(acceptance.actingAccountId()))
            .filter(
                candidate ->
                    expectedProfileId == null || candidate.getProfileId().equals(expectedProfileId))
            .orElseThrow(ProfileManagementDeniedException::new);

    var manager =
        managerRepository.save(
            ProfileManager.builder()
                .accountId(acceptance.actingAccountId())
                .profileId(invitation.getProfileId())
                .build());
    invitation.setStatus(ProfileManagerInvitationStatus.ACCEPTED);
    invitationRepository.save(invitation);
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(acceptance.actingAccountId())
            .targetProfileId(invitation.getProfileId())
            .operation(SecurityAuditOperation.PROFILE_MANAGER_ACCEPTED)
            .build());
    return manager;
  }

  /**
   * Removes the acting account's management association from a profile.
   *
   * @param relinquishment details identifying the acting account and profile
   */
  @Transactional
  public void relinquish(ProfileManagementRelinquishment relinquishment) {
    var manager =
        managerRepository
            .findByAccountIdAndProfileId(
                relinquishment.actingAccountId(), relinquishment.profileId())
            .orElseThrow(ProfileManagementDeniedException::new);
    if (managerRepository.countByProfileId(relinquishment.profileId()) <= 1) {
      throw new ProfileManagerInvariantException("A profile must retain at least one manager.");
    }

    kidManagerPolicy.validateManagerRemoval(
        relinquishment.profileId(), relinquishment.actingAccountId());
    managerRepository.delete(manager);
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(relinquishment.actingAccountId())
            .targetProfileId(relinquishment.profileId())
            .operation(SecurityAuditOperation.PROFILE_MANAGER_RELINQUISHED)
            .build());
  }

  /**
   * Verifies that an account manages a profile.
   *
   * @param accountId the account to verify
   * @param profileId the profile to access
   * @throws ProfileManagementDeniedException if the account does not manage the profile
   */
  private void requireManager(UUID accountId, UUID profileId) {
    if (!managerRepository.existsByAccountIdAndProfileId(accountId, profileId)) {
      throw new ProfileManagementDeniedException();
    }
  }

  /**
   * Validates that a profile name is present and contains non-whitespace characters.
   *
   * @param name the profile name to validate
   * @throws IllegalArgumentException if the name is null or blank
   */
  private void requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("A profile name is required.");
    }
  }
}
