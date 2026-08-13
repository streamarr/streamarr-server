package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ProfileDeletionAuthorization;
import com.streamarr.server.domain.auth.ProfileDeletionMode;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.ProfileManagerInvariantException;
import com.streamarr.server.repositories.auth.ProfileDeletionAuthorizationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
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

  @Transactional
  public void forceDeleteProfile(ForceProfileDeletionCommand command) {
    requireReason(command.reason());
    serverAdminAuthorizer.requireFreshAuthority(command.actingAccountId(), command.password());
    var profile =
        profileRepository
            .findById(command.profileId())
            .orElseThrow(ProfileAccessDeniedException::new);

    var shares = shareRepository.findByProfileId(command.profileId());
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
    auditService.record(
        SecurityAuditRecord.builder()
            .actingAccountId(command.actingAccountId())
            .targetProfileId(command.profileId())
            .operation(SecurityAuditOperation.PROFILE_FORCE_DELETED)
            .reason(command.reason())
            .build());
    profileRepository.delete(profile);
  }

  @Transactional
  public void forceUnshareProfile(ForceProfileUnshareCommand command) {
    requireReason(command.reason());
    serverAdminAuthorizer.requireFreshAuthority(command.actingAccountId(), command.password());
    var share =
        shareRepository.findById(command.shareId()).orElseThrow(ProfileAccessDeniedException::new);

    shareRepository.delete(share);
    selectionCleaner.clear(share.getProfileId(), share.getHouseholdId());
    auditService.record(
        SecurityAuditRecord.builder()
            .actingAccountId(command.actingAccountId())
            .targetHouseholdId(share.getHouseholdId())
            .targetProfileId(share.getProfileId())
            .operation(SecurityAuditOperation.PROFILE_FORCE_UNSHARED)
            .reason(command.reason())
            .build());
  }

  @Transactional
  public void overrideProfileManager(ProfileManagerOverrideCommand command) {
    requireReason(command.reason());
    serverAdminAuthorizer.requireFreshAuthority(command.actingAccountId(), command.password());
    profileRepository.findById(command.profileId()).orElseThrow(ProfileAccessDeniedException::new);
    accountRepository
        .findById(command.targetAccountId())
        .orElseThrow(ProfileAccessDeniedException::new);

    if (command.action() == ProfileManagerOverrideAction.GRANT) {
      grantManagement(command);
    } else {
      removeManagement(command);
    }

    auditService.record(
        SecurityAuditRecord.builder()
            .actingAccountId(command.actingAccountId())
            .targetAccountId(command.targetAccountId())
            .targetProfileId(command.profileId())
            .operation(SecurityAuditOperation.PROFILE_MANAGER_OVERRIDDEN)
            .reason(command.reason())
            .build());
  }

  private void grantManagement(ProfileManagerOverrideCommand command) {
    if (managerRepository.existsByAccountIdAndProfileId(
        command.targetAccountId(), command.profileId())) {
      return;
    }

    managerRepository.save(
        ProfileManager.builder()
            .accountId(command.targetAccountId())
            .profileId(command.profileId())
            .build());
  }

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

  private void requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("An override reason is required.");
    }
  }
}
