package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ProfileDeletionAuthorization;
import com.streamarr.server.domain.auth.ProfileDeletionMode;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.ProfileManagerInvariantException;
import com.streamarr.server.repositories.auth.ProfileDeletionAuthorizationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import java.util.Comparator;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ServerAdministrationService {

  private final UserAccountRepository accountRepository;
  private final ProfileRepository profileRepository;
  private final ProfileManagerRepository managerRepository;
  private final ProfileManagerInvitationRepository invitationRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileDeletionAuthorizationRepository deletionAuthorizationRepository;
  private final ProfileSelectionCleaner selectionCleaner;
  private final ServerAdminAuthorizer serverAdminAuthorizer;
  private final KidProfileManagerPolicy kidManagerPolicy;
  private final SecurityAuditService auditService;

  /**
   * Permanently deletes a profile through an authorized force-deletion operation.
   *
   * <p>Removes associated household shares, selections, invitations, and manager records,
   * then records the deletion authorization and security audit event.
   *
   * @param command the force-deletion request containing the profile, acting account,
   *                credentials, and reason
   * @throws ProfileAccessDeniedException if the profile cannot be found
   */
  @Transactional
  public void forceDeleteProfile(ForceProfileDeletionCommand command) {
    requireReason(command.reason());
    requireFreshAuthority(command.actingAccountId(), command.password(), command.profileId());
    var profile =
        profileRepository
            .findById(command.profileId())
            .orElseThrow(ProfileAccessDeniedException::new);

    var shares =
        shareRepository.findByProfileId(command.profileId()).stream()
            .sorted(Comparator.comparing(ProfileHouseholdShare::getHouseholdId))
            .toList();
    shareRepository.deleteAll(shares);
    shares.forEach(share -> selectionCleaner.clear(share.getProfileId(), share.getHouseholdId()));
    invitationRepository.deleteAll(invitationRepository.findByProfileId(command.profileId()));
    managerRepository.deleteAll(managerRepository.findByProfileId(command.profileId()));
    deletionAuthorizationRepository.saveAndFlush(
        ProfileDeletionAuthorization.builder()
            .profileId(command.profileId())
            .actingAccountId(command.actingAccountId())
            .mode(ProfileDeletionMode.FORCE)
            .build());
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(command.actingAccountId())
            .targetProfileId(command.profileId())
            .operation(SecurityAuditOperation.PROFILE_FORCE_DELETED)
            .reason(command.reason())
            .build());
    profileRepository.delete(profile);
  }

  /**
   * Forcefully removes a profile's share with a household.
   *
   * @param command the authorization, share, and reason details for the unshare operation
   */
  @Transactional
  public void forceUnshareProfile(ForceProfileUnshareCommand command) {
    requireReason(command.reason());
    requireFreshAuthority(command.actingAccountId(), command.password(), null);
    var share =
        shareRepository.findById(command.shareId()).orElseThrow(ProfileAccessDeniedException::new);

    shareRepository.delete(share);
    selectionCleaner.clear(share.getProfileId(), share.getHouseholdId());
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(command.actingAccountId())
            .targetHouseholdId(share.getHouseholdId())
            .targetProfileId(share.getProfileId())
            .operation(SecurityAuditOperation.PROFILE_FORCE_UNSHARED)
            .reason(command.reason())
            .build());
  }

  /**
   * Overrides management access for an account on a profile.
   *
   * @param command the authorization, target, action, and reason for the override
   */
  @Transactional
  public void overrideProfileManager(ProfileManagerOverrideCommand command) {
    requireReason(command.reason());
    requireFreshAuthority(command.actingAccountId(), command.password(), command.profileId());
    profileRepository.findById(command.profileId()).orElseThrow(ProfileAccessDeniedException::new);
    accountRepository
        .findById(command.targetAccountId())
        .orElseThrow(ProfileAccessDeniedException::new);

    if (command.action() == ProfileManagerOverrideAction.GRANT) {
      grantManagement(command);
    } else {
      removeManagement(command);
    }

    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(command.actingAccountId())
            .targetAccountId(command.targetAccountId())
            .targetProfileId(command.profileId())
            .operation(SecurityAuditOperation.PROFILE_MANAGER_OVERRIDDEN)
            .reason(command.reason())
            .build());
  }

  /**
   * Grants the target account management access to the profile.
   *
   * @param command the manager override request containing the target account and profile identifiers
   */
  private void grantManagement(ProfileManagerOverrideCommand command) {
    managerRepository.insertIfAbsent(command.targetAccountId(), command.profileId());
  }

  /**
   * Verifies fresh server administrator authority and records failed authentication attempts.
   *
   * @param accountId       the account requesting administrator authority
   * @param password        the password used for verification
   * @param targetProfileId the profile targeted by the administrative operation
   * @throws InvalidCredentialsException if password verification fails
   */
  private void requireFreshAuthority(UUID accountId, String password, UUID targetProfileId) {
    try {
      serverAdminAuthorizer.requireFreshAuthority(accountId, password);
    } catch (InvalidCredentialsException exception) {
      auditService.recordFailure(
          SecurityAuditRecord.builder()
              .actingAccountId(accountId)
              .targetProfileId(targetProfileId)
              .operation(SecurityAuditOperation.SERVER_ADMIN_REAUTHENTICATION_DENIED)
              .reason("Server administrator password verification failed")
              .build());
      throw exception;
    }
  }

  /**
   * Removes the target account's management relationship from a profile.
   *
   * @param command the profile manager override request identifying the profile and account
   * @throws ProfileAccessDeniedException if the account is not a manager of the profile
   * @throws ProfileManagerInvariantException if removing the manager would leave the profile without a manager
   */
  private void removeManagement(ProfileManagerOverrideCommand command) {
    var manager =
        managerRepository
            .findByAccountIdAndProfileId(command.targetAccountId(), command.profileId())
            .orElseThrow(ProfileAccessDeniedException::new);
    if (managerRepository.countByProfileId(command.profileId()) == 1) {
      throw new ProfileManagerInvariantException("A profile must retain at least one manager.");
    }
    kidManagerPolicy.validateManagerRemoval(command.profileId(), command.targetAccountId());
    managerRepository.delete(manager);
  }

  /**
   * Ensures that an administrative override reason is provided.
   *
   * @param reason the reason supplied for the administrative action
   * @throws IllegalArgumentException if the reason is null or blank
   */
  private void requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("An override reason is required.");
    }
  }
}
