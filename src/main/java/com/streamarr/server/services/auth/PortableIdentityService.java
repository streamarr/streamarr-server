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

@SuppressWarnings("java:S6539")
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

  /**
   * Creates a service for coordinating portable identity operations.
   */
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

  /**
   * Creates a portable profile from the specified command.
   *
   * @param command the profile creation details
   * @return the created profile
   */
  public Profile createPortableProfile(CreatePortableProfileCommand command) {
    return execute(() -> managementService.create(command));
  }

  /**
   * Renames a portable profile.
   *
   * @param command the command containing the profile and new name
   */
  public void renamePortableProfile(RenamePortableProfileCommand command) {
    execute(() -> managementService.rename(command));
  }

  /**
   * Offers a profile household share.
   *
   * @param command the profile share offer to process
   * @return the created profile household share
   */
  public ProfileHouseholdShare offerProfileShare(ProfileShareOffer command) {
    return execute(() -> sharingService.offer(command));
  }

  /**
   * Accepts a profile household share.
   *
   * @param command the profile share acceptance command
   * @return the accepted profile household share
   */
  public ProfileHouseholdShare acceptProfileShare(ProfileShareAcceptance command) {
    return execute(() -> sharingService.accept(command));
  }

  /**
   * Rejects a pending profile-sharing request.
   *
   * @param command the profile-sharing rejection command
   */
  public void rejectProfileShare(ProfileShareRejection command) {
    execute(() -> sharingService.reject(command));
  }

  /**
   * Cancels a pending profile-sharing request.
   *
   * @param command the profile-share cancellation command
   */
  public void cancelProfileShare(ProfileShareCancellation command) {
    execute(() -> sharingService.cancel(command));
  }

  /**
   * Removes a profile from its current household.
   *
   * @param command the profile removal command
   */
  public void removeProfileFromCurrentHousehold(HouseholdProfileRemoval command) {
    execute(() -> sharingService.removeFromHousehold(command));
  }

  /**
   * Leaves the profile's current household.
   *
   * @param command the command describing the profile's departure
   */
  public void leaveCurrentHome(ProfileHomeDeparture command) {
    execute(() -> sharingService.leaveCurrentHome(command));
  }

  /**
   * Invites a profile manager to manage a profile.
   *
   * @param command the profile manager invitation command
   * @return the created profile manager invitation
   */
  public ProfileManagerInvitation inviteProfileManager(ProfileManagerInvite command) {
    return execute(() -> managementService.invite(command));
  }

  /**
   * Accepts an invitation to manage a profile.
   *
   * @param command the profile manager invitation acceptance command
   * @return the resulting profile manager
   */
  public ProfileManager acceptProfileManagerInvitation(ProfileManagerInvitationAcceptance command) {
    return execute(() -> managementService.accept(command));
  }

  /**
   * Rejects an invitation to manage a profile.
   *
   * @param command the profile manager invitation rejection command
   */
  public void rejectProfileManagerInvitation(ProfileManagerInvitationRejection command) {
    execute(() -> managementService.reject(command));
  }

  /**
   * Cancels an invitation for profile management.
   *
   * @param command the profile manager invitation cancellation command
   */
  public void cancelProfileManagerInvitation(ProfileManagerInvitationCancellation command) {
    execute(() -> managementService.cancel(command));
  }

  /**
   * Relinquishes profile management for the specified command.
   *
   * @param command the profile management relinquishment request
   */
  public void relinquishProfileManagement(ProfileManagementRelinquishment command) {
    execute(() -> managementService.relinquish(command));
  }

  /**
   * Sets the kind of a portable profile.
   *
   * @param command the command containing the profile and kind to set
   */
  public void setProfileKind(SetProfileKindCommand command) {
    execute(() -> policyService.setKind(command));
  }

  /**
   * Sets the maximum content rating allowed for a profile.
   *
   * @param command the command describing the profile and content ceiling
   */
  public void setProfileContentCeiling(SetProfileContentCeilingCommand command) {
    execute(() -> policyService.setContentCeiling(command));
  }

  /**
   * Removes the content ceiling from a profile.
   *
   * @param command the command describing the profile whose content ceiling should be removed
   */
  public void removeProfileContentCeiling(RemoveProfileContentCeilingCommand command) {
    execute(() -> policyService.removeContentCeiling(command));
  }

  /**
   * Resets the PIN for a profile.
   *
   * @param command the command containing the profile and PIN reset details
   */
  public void resetProfilePin(ResetProfilePinCommand command) {
    execute(() -> policyService.resetPin(command));
  }

  /**
   * Deletes the profile specified by the command.
   *
   * @param command the profile deletion command
   */
  public void deleteProfile(DeleteProfileCommand command) {
    execute(() -> deletionService.delete(command));
  }

  /**
   * Permanently deletes a profile through server administration.
   *
   * @param command the command specifying the profile deletion
   */
  public void forceDeleteProfile(ForceProfileDeletionCommand command) {
    execute(() -> serverAdministrationService.forceDeleteProfile(command));
  }

  /**
   * Forcefully removes a profile from its current household share.
   *
   * @param command the command describing the profile unshare operation
   */
  public void forceUnshareProfile(ForceProfileUnshareCommand command) {
    execute(() -> serverAdministrationService.forceUnshareProfile(command));
  }

  /**
   * Overrides the manager assigned to a profile.
   *
   * @param command the command describing the profile manager override
   */
  public void overrideProfileManager(ProfileManagerOverrideCommand command) {
    execute(() -> serverAdministrationService.overrideProfileManager(command));
  }

  /**
   * Transfers an account to another household.
   *
   * @param command the account household transfer details
   */
  public void transferAccountHousehold(AccountHouseholdTransferCommand command) {
    execute(() -> householdAdministrationService.transferAccount(command));
  }

  /**
   * Transfers ownership of a household.
   *
   * @param command the household ownership transfer details
   */
  public void transferHouseholdOwnership(HouseholdOwnershipTransferCommand command) {
    execute(() -> householdAdministrationService.transferOwnership(command));
  }

  /**
   * Executes an operation transactionally and retries eligible serialization or deadlock failures.
   *
   * @param <T>       the operation result type
   * @param operation the operation to execute
   * @return          the operation result
   * @throws RuntimeException if the operation fails with a non-retryable error or remains unsuccessful after the retry limit
   */
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
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
          throw exception;
        }
      }
    }
  }

  /**
   * Executes a void operation within a transaction.
   *
   * @param operation the operation to execute
   */
  private void execute(Runnable operation) {
    execute(
        () -> {
          operation.run();
          return null;
        });
  }

  /**
   * Finds the first retryable SQL state in the failure's cause chain and chained SQL exceptions.
   *
   * @param failure the failure to inspect
   * @return the matching SQL state, or an empty optional if none is retryable
   */
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
