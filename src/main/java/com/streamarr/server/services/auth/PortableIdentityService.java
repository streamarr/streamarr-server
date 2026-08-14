package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class PortableIdentityService {

  private static final int MAX_ATTEMPTS = 3;
  private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40001", "40P01");

  private final TransactionTemplate transactionTemplate;
  private final ProfileSharingService sharingService;
  private final ProfileManagementService managementService;
  private final ProfilePolicyService policyService;
  private final ProfileDeletionService deletionService;
  private final ServerAdministrationService serverAdministrationService;
  private final HouseholdAdministrationService householdAdministrationService;

  @Builder
  public PortableIdentityService(
      TransactionTemplate transactionTemplate,
      ProfileSharingService sharingService,
      ProfileManagementService managementService,
      ProfilePolicyService policyService,
      ProfileDeletionService deletionService,
      ServerAdministrationService serverAdministrationService,
      HouseholdAdministrationService householdAdministrationService) {
    this.transactionTemplate = transactionTemplate;
    this.sharingService = sharingService;
    this.managementService = managementService;
    this.policyService = policyService;
    this.deletionService = deletionService;
    this.serverAdministrationService = serverAdministrationService;
    this.householdAdministrationService = householdAdministrationService;
  }

  public Profile createPortableProfile(CreatePortableProfileCommand command) {
    return execute(() -> managementService.create(command));
  }

  public void renamePortableProfile(RenamePortableProfileCommand command) {
    execute(() -> managementService.rename(command));
  }

  public ProfileHouseholdShare offerProfileShare(ProfileShareOffer command) {
    return execute(() -> sharingService.offer(command));
  }

  public ProfileHouseholdShare acceptProfileShare(ProfileShareAcceptance command) {
    return execute(() -> sharingService.accept(command));
  }

  public void rejectProfileShare(ProfileShareRejection command) {
    execute(() -> sharingService.reject(command));
  }

  public void cancelProfileShare(ProfileShareCancellation command) {
    execute(() -> sharingService.cancel(command));
  }

  public void removeProfileFromCurrentHousehold(HouseholdProfileRemoval command) {
    execute(() -> sharingService.removeFromHousehold(command));
  }

  public void leaveCurrentHome(ProfileHomeDeparture command) {
    execute(() -> sharingService.leaveCurrentHome(command));
  }

  public ProfileManagerInvitation inviteProfileManager(ProfileManagerInvite command) {
    return execute(() -> managementService.invite(command));
  }

  public ProfileManager acceptProfileManagerInvitation(ProfileManagerInvitationAcceptance command) {
    return execute(() -> managementService.accept(command));
  }

  public void rejectProfileManagerInvitation(ProfileManagerInvitationRejection command) {
    execute(() -> managementService.reject(command));
  }

  public void cancelProfileManagerInvitation(ProfileManagerInvitationCancellation command) {
    execute(() -> managementService.cancel(command));
  }

  public void relinquishProfileManagement(ProfileManagementRelinquishment command) {
    execute(() -> managementService.relinquish(command));
  }

  public void setProfileKind(SetProfileKindCommand command) {
    execute(() -> policyService.setKind(command));
  }

  public void setProfileContentCeiling(SetProfileContentCeilingCommand command) {
    execute(() -> policyService.setContentCeiling(command));
  }

  public void removeProfileContentCeiling(RemoveProfileContentCeilingCommand command) {
    execute(() -> policyService.removeContentCeiling(command));
  }

  public void resetProfilePin(ResetProfilePinCommand command) {
    execute(() -> policyService.resetPin(command));
  }

  public void deleteProfile(DeleteProfileCommand command) {
    execute(() -> deletionService.delete(command));
  }

  public void forceDeleteProfile(ForceProfileDeletionCommand command) {
    execute(() -> serverAdministrationService.forceDeleteProfile(command));
  }

  public void forceUnshareProfile(ForceProfileUnshareCommand command) {
    execute(() -> serverAdministrationService.forceUnshareProfile(command));
  }

  public void overrideProfileManager(ProfileManagerOverrideCommand command) {
    execute(() -> serverAdministrationService.overrideProfileManager(command));
  }

  public void transferAccountHousehold(AccountHouseholdTransferCommand command) {
    execute(() -> householdAdministrationService.transferAccount(command));
  }

  public void transferHouseholdOwnership(HouseholdOwnershipTransferCommand command) {
    execute(() -> householdAdministrationService.transferOwnership(command));
  }

  private <T> T execute(Supplier<T> operation) {
    for (var attempt = 1; ; attempt++) {
      try {
        return transactionTemplate.execute(_ -> operation.get());
      } catch (RuntimeException exception) {
        var retryableSqlState = retryableSqlState(exception);
        if (attempt == MAX_ATTEMPTS || retryableSqlState.isEmpty()) {
          throw exception;
        }
        var backoffMillis = ThreadLocalRandom.current().nextLong(5, 21) * attempt;
        log.warn(
            "Retrying portable identity transaction after SQLSTATE {} with {} ms backoff (attempt"
                + " {}/{}).",
            retryableSqlState.orElseThrow(),
            backoffMillis,
            attempt,
            MAX_ATTEMPTS);
        try {
          TimeUnit.MILLISECONDS.sleep(backoffMillis);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw exception;
        }
      }
    }
  }

  private void execute(Runnable operation) {
    execute(
        () -> {
          operation.run();
          return null;
        });
  }

  private Optional<String> retryableSqlState(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (!(cause instanceof SQLException sqlException)) {
        continue;
      }

      for (var candidate = sqlException;
          candidate != null;
          candidate = candidate.getNextException()) {
        var sqlState = candidate.getSQLState();
        if (sqlState != null && RETRYABLE_SQL_STATES.contains(sqlState)) {
          return Optional.of(sqlState);
        }
      }
    }
    return Optional.empty();
  }
}
