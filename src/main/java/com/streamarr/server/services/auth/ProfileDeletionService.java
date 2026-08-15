package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.ProfileDeletionAuthorization;
import com.streamarr.server.domain.auth.ProfileDeletionMode;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.SecurityAuditOperation;
import com.streamarr.server.exceptions.InvalidCredentialsException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.exceptions.ProfileDeletionBlockedException;
import com.streamarr.server.exceptions.ProfileManagementDeniedException;
import com.streamarr.server.repositories.auth.ProfileDeletionAuthorizationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProfileDeletionService {

  private final ProfileRepository profileRepository;
  private final ProfileManagerRepository managerRepository;
  private final ProfileManagerInvitationRepository invitationRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final UserAccountRepository accountRepository;
  private final ProfileDeletionAuthorizationRepository deletionAuthorizationRepository;
  private final PasswordEncoder passwordEncoder;
  private final SecurityAuditService auditService;

  @Transactional
  public void delete(DeleteProfileCommand command) {
    requireValidPassword(command);
    if (!managerRepository.existsByAccountIdAndProfileId(
        command.actingAccountId(), command.profileId())) {
      throw new ProfileManagementDeniedException();
    }
    if (shareRepository.countByProfileId(command.profileId()) > 0) {
      throw new ProfileDeletionBlockedException("End all profile shares before deletion.");
    }
    if (invitationRepository.countByProfileIdAndStatus(
            command.profileId(), ProfileManagerInvitationStatus.PENDING)
        > 0) {
      throw new ProfileDeletionBlockedException(
          "Resolve all pending manager invitations before deletion.");
    }
    if (managerRepository.countByProfileId(command.profileId()) != 1) {
      throw new ProfileDeletionBlockedException(
          "Exactly one profile manager must remain before deletion.");
    }

    var profile =
        profileRepository
            .findById(command.profileId())
            .orElseThrow(ProfileAccessDeniedException::new);
    auditService.recordEvent(
        SecurityAuditRecord.builder()
            .actingAccountId(command.actingAccountId())
            .targetProfileId(command.profileId())
            .operation(SecurityAuditOperation.PROFILE_DELETED)
            .build());
    deletionAuthorizationRepository.saveAndFlush(
        ProfileDeletionAuthorization.builder()
            .profileId(command.profileId())
            .actingAccountId(command.actingAccountId())
            .mode(ProfileDeletionMode.ORDINARY)
            .build());
    profileRepository.delete(profile);
  }

  private void requireValidPassword(DeleteProfileCommand command) {
    var account =
        accountRepository
            .findById(command.actingAccountId())
            .orElseThrow(ProfileAccessDeniedException::new);
    if (!passwordEncoder.matches(command.password(), account.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }
  }
}
