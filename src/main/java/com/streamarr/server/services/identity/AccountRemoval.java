package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SessionRevocationReason;
import com.streamarr.server.domain.auth.SourceHouseholdAccess;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AccountRemovalConflictException;
import com.streamarr.server.exceptions.ProfileRehomeFailedException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.DeviceRegistrationLifecycle;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The mechanics of removing an Account from a Household — by move or by deletion — shared by the
 * lifecycle mutations and by Household deletion, which disposes of the final Account the guarded
 * mutations refuse. Callers own the guards, the authorization, and the audit record; every step
 * here runs inside the caller's transaction and the deferred invariants judge the result.
 */
@Component
@RequiredArgsConstructor
class AccountRemoval {

  private static final String PROFILE_DELETED = "Profile deleted";

  private final UserAccountRepository userAccountRepository;
  private final ProfileRepository profileRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileManagerInvitationRepository managerInvitationRepository;
  private final AccountInvitationRepository accountInvitationRepository;
  private final PasswordResetCodeRepository passwordResetCodeRepository;
  private final AuthSessionRepository authSessionRepository;
  private final DeviceRegistrationLifecycle registrationLifecycle;

  /**
   * Moves the Account and its Personal Profile inside the caller's transaction; throws if either
   * required row change fails so the caller rolls back the entire operation.
   */
  void move(Transfer transfer) {
    var sourceHouseholdId = transfer.sourceHouseholdId();
    var profileId = transfer.profileId();
    var destinationHouseholdId = transfer.destinationHouseholdId();
    var now = transfer.now();
    var destinationEmpty =
        userAccountRepository.findByHouseholdId(destinationHouseholdId).isEmpty();
    if (!userAccountRepository.tryTransfer(
        transfer.accountId(),
        sourceHouseholdId,
        destinationHouseholdId,
        destinationEmpty ? HouseholdRole.ADMIN : HouseholdRole.MEMBER)) {
      throw new AccountRemovalConflictException();
    }

    rehomeProfile(profileId, sourceHouseholdId, destinationHouseholdId);

    if (transfer.sourceHouseholdAccess() == SourceHouseholdAccess.KEEP_AS_VISITOR) {
      shareRepository.convertMembershipShareToVisitorShare(profileId, sourceHouseholdId, now);
      authSessionRepository.clearProfileSelectionFromLiveSessions(
          profileId, sourceHouseholdId, now);
    } else {
      endSourceHouseholdAccess(transfer);
    }

    shareRepository.ensureActiveMembershipShare(profileId, destinationHouseholdId, now);
  }

  /**
   * Deletes the Account and completes its chosen Personal Profile disposition inside the caller's
   * transaction; throws if a required row change fails. No final-Account guard.
   */
  void erase(Deletion deletion) {
    var account = deletion.account();
    var now = deletion.now();
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

    switch (deletion.profileDisposition()) {
      case ErasePersonalProfile() -> {
        deleteAccountRow(account);
        deleteProfile(account.getPersonalProfileId(), now);
      }
      case PreservePersonalProfile(var managerId, var destinationHouseholdId) ->
          preserveProfile(deletion, managerId, destinationHouseholdId);
    }
  }

  private void preserveProfile(
      Deletion deletion, UUID replacementManagerAccountId, UUID destinationHouseholdId) {
    var account = deletion.account();
    var profileId = account.getPersonalProfileId();
    var sourceHouseholdId = account.getHouseholdId();
    var now = deletion.now();
    // The preserved Profile needs its replacement anchor before the person leaves it behind.
    profileManagerRepository.tryGrantDirectManagement(replacementManagerAccountId, profileId);
    shareRepository.convertMembershipShareToVisitorShare(profileId, sourceHouseholdId, now);
    deleteAccountRow(account);
    if (sourceHouseholdId.equals(destinationHouseholdId)) {
      return;
    }

    rehomeProfile(profileId, sourceHouseholdId, destinationHouseholdId);
    shareRepository
        .findByProfileIdAndHouseholdIdAndStatus(
            profileId, sourceHouseholdId, ProfileShareStatus.ACTIVE)
        .ifPresent(share -> shareRepository.tryEndActive(share.getId(), now));
    shareRepository.ensureActiveMembershipShare(profileId, destinationHouseholdId, now);
    shareRepository.convertMembershipShareToVisitorShare(profileId, destinationHouseholdId, now);
  }

  private void rehomeProfile(UUID profileId, UUID sourceHouseholdId, UUID destinationHouseholdId) {
    if (!profileRepository.tryRehome(profileId, sourceHouseholdId, destinationHouseholdId)) {
      throw new ProfileRehomeFailedException();
    }
  }

  /** Deletes an unlinked Profile with its selections and pending Profile-bound artifacts. */
  void deleteProfile(UUID profileId, Instant now) {
    accountInvitationRepository.invalidatePendingByProfileId(profileId, PROFILE_DELETED, now);
    managerInvitationRepository.invalidatePendingByProfileId(profileId, PROFILE_DELETED, now);
    shareRepository.invalidatePendingByProfileId(profileId, PROFILE_DELETED, now);
    shareRepository
        .findByProfileIdAndStatus(profileId, ProfileShareStatus.ACTIVE)
        .forEach(
            share ->
                authSessionRepository.clearProfileSelectionFromLiveSessions(
                    profileId, share.getHouseholdId(), now));
    profileRepository.deleteById(profileId);
    profileRepository.flush();
  }

  private void endSourceHouseholdAccess(Transfer transfer) {
    var accountId = transfer.accountId();
    var profileId = transfer.profileId();
    var sourceHouseholdId = transfer.sourceHouseholdId();
    var now = transfer.now();
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
      throw new AccountRemovalConflictException();
    }
  }

  @Builder
  record Transfer(
      @NonNull UUID accountId,
      @NonNull UUID sourceHouseholdId,
      @NonNull UUID profileId,
      @NonNull UUID destinationHouseholdId,
      @NonNull SourceHouseholdAccess sourceHouseholdAccess,
      @NonNull Instant now) {}

  @Builder
  record Deletion(
      @NonNull UserAccount account,
      @NonNull ProfileDisposition profileDisposition,
      @NonNull Instant now) {}

  sealed interface ProfileDisposition permits ErasePersonalProfile, PreservePersonalProfile {}

  record ErasePersonalProfile() implements ProfileDisposition {}

  record PreservePersonalProfile(
      @NonNull UUID replacementManagerAccountId, @NonNull UUID destinationHouseholdId)
      implements ProfileDisposition {}
}
