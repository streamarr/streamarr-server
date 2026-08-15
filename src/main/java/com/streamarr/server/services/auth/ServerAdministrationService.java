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

  public PreparedForceProfileDeletion prepare(ForceProfileDeletionCommand command) {
    requireReason(command.reason());
    return new PreparedForceProfileDeletion(
        command.actingAccountId(),
        command.profileId(),
        command.reason(),
        prepareAuthority(command.actingAccountId(), command.password(), command.profileId()));
  }

  @Transactional
  public void forceDeleteProfile(PreparedForceProfileDeletion command) {
    serverAdminAuthorizer.requireFreshAuthority(command.authority());
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

  public PreparedForceProfileUnshare prepare(ForceProfileUnshareCommand command) {
    requireReason(command.reason());
    return new PreparedForceProfileUnshare(
        command.actingAccountId(),
        command.shareId(),
        command.reason(),
        prepareAuthority(command.actingAccountId(), command.password(), null));
  }

  @Transactional
  public void forceUnshareProfile(PreparedForceProfileUnshare command) {
    serverAdminAuthorizer.requireFreshAuthority(command.authority());
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

  public PreparedProfileManagerOverride prepare(ProfileManagerOverrideCommand command) {
    requireReason(command.reason());
    return new PreparedProfileManagerOverride(
        command.actingAccountId(),
        command.targetAccountId(),
        command.profileId(),
        command.action(),
        command.reason(),
        prepareAuthority(command.actingAccountId(), command.password(), command.profileId()));
  }

  @Transactional
  public void overrideProfileManager(PreparedProfileManagerOverride command) {
    serverAdminAuthorizer.requireFreshAuthority(command.authority());
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

  private void grantManagement(PreparedProfileManagerOverride command) {
    managerRepository.insertIfAbsent(command.targetAccountId(), command.profileId());
  }

  private PasswordReauthentication prepareAuthority(
      UUID accountId, String password, UUID targetProfileId) {
    try {
      return serverAdminAuthorizer.prepare(accountId, password);
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

  private void removeManagement(PreparedProfileManagerOverride command) {
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

  record PreparedForceProfileDeletion(
      UUID actingAccountId, UUID profileId, String reason, PasswordReauthentication authority) {}

  record PreparedForceProfileUnshare(
      UUID actingAccountId, UUID shareId, String reason, PasswordReauthentication authority) {}

  record PreparedProfileManagerOverride(
      UUID actingAccountId,
      UUID targetAccountId,
      UUID profileId,
      ProfileManagerOverrideAction action,
      String reason,
      PasswordReauthentication authority) {}
}
