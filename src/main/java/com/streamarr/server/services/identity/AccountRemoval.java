package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.DeviceRegistrationLifecycle;
import com.streamarr.server.services.identity.AccountLifecycleService.ProfileCleanup;
import com.streamarr.server.services.identity.AccountLifecycleService.SourceHouseholdAccess;
import com.streamarr.server.services.mutation.MutationRejection;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The mechanics of removing an Account from a Household — by move or by deletion — shared by the
 * lifecycle mutations and by Household teardown, which disposes of the final Account the guarded
 * mutations refuse. Callers own the guards, the authorization, and the audit record; every step
 * here runs inside the caller's transaction and the deferred invariants judge the result.
 */
@Component
@RequiredArgsConstructor
class AccountRemoval {

  private final UserAccountRepository userAccountRepository;
  private final ProfileRepository profileRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileManagerInvitationRepository managerInvitationRepository;
  private final AccountInvitationRepository accountInvitationRepository;
  private final PasswordResetCodeRepository passwordResetCodeRepository;
  private final AuthSessionRepository authSessionRepository;
  private final DeviceRegistrationLifecycle registrationLifecycle;

  /** Moves the Account and its Personal Profile; false when the row already moved on. */
  boolean move(
      UUID accountId,
      UUID sourceHouseholdId,
      UUID profileId,
      UUID destinationHouseholdId,
      boolean destinationEmpty,
      SourceHouseholdAccess sourceHouseholdAccess,
      Instant now) {
    if (!userAccountRepository.tryTransfer(
        accountId,
        sourceHouseholdId,
        destinationHouseholdId,
        destinationEmpty ? HouseholdRole.ADMIN : HouseholdRole.MEMBER)) {
      return false;
    }
    if (!profileRepository.tryRehome(profileId, sourceHouseholdId, destinationHouseholdId)) {
      return false;
    }
    if (sourceHouseholdAccess == SourceHouseholdAccess.KEEP_AS_VISITOR) {
      shareRepository.convertMembershipShareToVisitorShare(profileId, sourceHouseholdId, now);
      authSessionRepository.clearProfileSelectionFromLiveSessions(
          profileId, sourceHouseholdId, now);
    } else {
      endSourceHouseholdAccess(accountId, profileId, sourceHouseholdId, now);
    }

    shareRepository.ensureActiveMembershipShare(profileId, destinationHouseholdId, now);
    return true;
  }

  /** Deletes the Account, disposing of its Personal Profile as chosen. No final-Account guard. */
  void erase(
      UserAccount account,
      ProfileCleanup profileCleanup,
      UUID replacementManagerAccountId,
      Instant now) {
    registrationLifecycle.revokeAllByAccount(account.getId(), "Account deleted", now);
    authSessionRepository.revokeAllForAccount(
        account.getId(), SessionRevocationReason.ADMIN_REVOCATION, now);
    accountInvitationRepository.invalidatePendingInvitationsIssuedBy(
        account.getId(), "issuer deleted", now);
    passwordResetCodeRepository.invalidatePendingPasswordResetCodesIssuedBy(
        account.getId(), "issuer deleted", now);
    managerInvitationRepository.invalidatePendingByRecipientAccountId(
        account.getId(), "recipient deleted", now);
    managerInvitationRepository.invalidatePendingInvitedBy(
        account.getId(), "inviting manager deleted", now);
    shareRepository.invalidatePendingOfferedBy(account.getId(), "offering manager deleted", now);

    var profileId = account.getPersonalProfileId();
    if (profileCleanup == ProfileCleanup.PRESERVE_PROFILE) {
      // The preserved Profile needs its replacement anchor before the person leaves it behind.
      profileManagerRepository.tryGrantDirectManagement(replacementManagerAccountId, profileId);
      shareRepository.convertMembershipShareToVisitorShare(
          profileId, account.getHouseholdId(), now);
      deleteAccountRow(account);
      return;
    }
    deleteAccountRow(account);
    deleteProfile(profileId, now);
  }

  /** Deletes an unlinked Profile with its selections and pending Profile-bound artifacts. */
  void deleteProfile(UUID profileId, Instant now) {
    accountInvitationRepository.invalidatePendingByProfileId(profileId, "Profile deleted", now);
    managerInvitationRepository.invalidatePendingByProfileId(profileId, "Profile deleted", now);
    shareRepository.invalidatePendingByProfileId(profileId, "Profile deleted", now);
    shareRepository
        .findByProfileIdAndStatus(profileId, ProfileShareStatus.ACTIVE)
        .forEach(
            share ->
                authSessionRepository.clearProfileSelectionFromLiveSessions(
                    profileId, share.getHouseholdId(), now));
    profileRepository.deleteById(profileId);
    profileRepository.flush();
  }

  private void endSourceHouseholdAccess(
      UUID accountId, UUID profileId, UUID sourceHouseholdId, Instant now) {
    shareRepository
        .findByProfileIdAndHouseholdIdAndStatus(
            profileId, sourceHouseholdId, ProfileShareStatus.ACTIVE)
        .ifPresent(share -> shareRepository.tryEndActive(share.getId(), now));
    authSessionRepository.clearProfileSelectionFromLiveSessions(profileId, sourceHouseholdId, now);
    authSessionRepository.clearHouseholdContextFromAccountSessions(
        accountId, sourceHouseholdId, now);
    registrationLifecycle.revokeAllByAccountAndHousehold(
        accountId, sourceHouseholdId, "old Household access ended", now);
  }

  private void deleteAccountRow(UserAccount account) {
    if (!userAccountRepository.tryDelete(account.getId(), account.getHouseholdId())) {
      throw new MutationRejection(new TransferRejections.AccountNotFound());
    }
  }
}
