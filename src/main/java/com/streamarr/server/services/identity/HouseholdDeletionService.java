package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.SecurityAuditEventRecordView;
import com.streamarr.server.domain.auth.SourceHouseholdAccess;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.exceptions.AccountRemovalConflictException;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.DeviceRegistrationLifecycle;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import com.streamarr.server.services.pagination.ServerScope;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Household deletion, the security audit, and Profile activity (ADR 0024 §Household deletion,
 * §Audit). Deletion is the one operation allowed to dispose of a Household's final Account: the
 * caller chooses one explicit atomic action, every other Account must already be gone, and deletion
 * revokes registrations, invalidates every pending credential into the Household, ends hosted
 * visits, deletes the resident Profiles, and deletes the Household — one transaction, judged by the
 * deferred invariants.
 */
@Service
@RequiredArgsConstructor
public class HouseholdDeletionService {

  private static final String CHK_SERVER_ADMIN_REMAINS = "chk_enabled_server_admin_remains";
  private static final String CHK_NAMES_UNIQUE = "chk_household_profile_names_unique";
  private static final String HOUSEHOLD_DELETED_REASON = "Household deleted";

  private final AuthorizationService authorizationService;
  private final AccountRemoval accountRemoval;
  private final HouseholdRepository householdRepository;
  private final UserAccountRepository userAccountRepository;
  private final ProfileRepository profileRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final AuthSessionRepository authSessionRepository;
  private final DeviceRegistrationLifecycle registrationLifecycle;
  private final AccountInvitationRepository accountInvitationRepository;
  private final SecurityAuditEventRepository securityAuditEventRepository;
  private final SecurityAuditList securityAuditList;
  private final ProfileActivityList profileActivityList;
  private final MutationTransactions mutationTransactions;
  private final Clock clock;

  public Outcome<UUID, HouseholdDeletionRejections.Delete> deleteEmptyHousehold(
      AuthenticatedIdentity identity, DeleteEmptyHouseholdCommand command) {
    return deleteHousehold(
        identity,
        HouseholdDeletionRequest.builder()
            .householdId(command.householdId())
            .reason(command.reason())
            .operation("deleteEmptyHousehold")
            .build());
  }

  public Outcome<UUID, HouseholdDeletionRejections.Delete> transferLastAccountAndDeleteHousehold(
      AuthenticatedIdentity identity, TransferLastAccountAndDeleteHouseholdCommand command) {
    return deleteHousehold(
        identity,
        HouseholdDeletionRequest.builder()
            .householdId(command.householdId())
            .reason(command.reason())
            .operation("transferLastAccountAndDeleteHousehold")
            .finalAccount(
                FinalAccountDisposition.builder()
                    .choice(FinalAccountChoice.TRANSFER)
                    .destinationHouseholdId(command.destinationHouseholdId())
                    .build())
            .build());
  }

  public Outcome<UUID, HouseholdDeletionRejections.Delete> deleteLastAccountAndHousehold(
      AuthenticatedIdentity identity, DeleteLastAccountAndHouseholdCommand command) {
    return deleteHousehold(
        identity,
        HouseholdDeletionRequest.builder()
            .householdId(command.householdId())
            .reason(command.reason())
            .operation("deleteLastAccountAndHousehold")
            .finalAccount(
                FinalAccountDisposition.builder().choice(FinalAccountChoice.DELETE).build())
            .build());
  }

  public Outcome<UUID, HouseholdDeletionRejections.Delete>
      deleteLastAccountAndHouseholdPreservingPersonalProfile(
          AuthenticatedIdentity identity,
          DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand command) {
    return deleteHousehold(
        identity,
        HouseholdDeletionRequest.builder()
            .householdId(command.householdId())
            .reason(command.reason())
            .operation("deleteLastAccountAndHouseholdPreservingPersonalProfile")
            .finalAccount(
                FinalAccountDisposition.builder()
                    .choice(FinalAccountChoice.DELETE_KEEPING_PROFILE)
                    .destinationHouseholdId(command.destinationHouseholdId())
                    .replacementManagerAccountId(command.replacementManagerAccountId())
                    .build())
            .build());
  }

  private Outcome<UUID, HouseholdDeletionRejections.Delete> deleteHousehold(
      AuthenticatedIdentity identity, HouseholdDeletionRequest request) {
    if (request.reason() == null || request.reason().isBlank()) {
      return Outcome.rejected(new HouseholdDeletionRejections.ReasonRequired());
    }

    var refusal = deletionRefusal(identity, request.householdId());
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    if (householdRepository.findById(request.householdId()).isEmpty()) {
      return Outcome.rejected(new HouseholdDeletionRejections.HouseholdNotFound());
    }

    var dispositionRefusal = dispositionRefusal(request);
    if (dispositionRefusal.isPresent()) {
      return Outcome.rejected(dispositionRefusal.get());
    }

    var now = clock.instant();
    try {
      return mutationTransactions.write(
          () -> deleteHouseholdWithinTransaction(identity, request, now),
          constraint ->
              switch (constraint) {
                case CHK_SERVER_ADMIN_REMAINS ->
                    Optional.of(new HouseholdDeletionRejections.LastServerAdmin());
                case CHK_NAMES_UNIQUE ->
                    Optional.of(new HouseholdDeletionRejections.NameConflict());
                default -> Optional.empty();
              });
    } catch (AccountRemovalConflictException _) {
      return Outcome.rejected(new HouseholdDeletionRejections.LastAccountNotFound());
    }
  }

  private UUID deleteHouseholdWithinTransaction(
      AuthenticatedIdentity identity, HouseholdDeletionRequest request, Instant now) {
    lockHouseholds(request);
    lockReplacementManager(request.finalAccount());

    var residents = userAccountRepository.findByHouseholdId(request.householdId());
    if (residents.size() > 1) {
      throw new MutationRejection(new HouseholdDeletionRejections.AccountsRemain());
    }

    if (residents.size() == 1 && request.finalAccount() == null) {
      throw new MutationRejection(new HouseholdDeletionRejections.AccountsRemain());
    }

    if (residents.isEmpty() && request.finalAccount() != null) {
      throw new MutationRejection(new HouseholdDeletionRejections.LastAccountNotFound());
    }

    residents.stream()
        .findFirst()
        .ifPresent(resident -> dispose(resident, request.finalAccount(), now));

    // The TVs and every pending way into the Household fall before the rows do.
    registrationLifecycle.revokeAllByHousehold(
        request.householdId(), HOUSEHOLD_DELETED_REASON, now);
    accountInvitationRepository.invalidatePendingByHouseholdId(
        request.householdId(), HOUSEHOLD_DELETED_REASON, now);
    endHostedVisits(request.householdId(), now);
    deleteResidentProfiles(request.householdId(), now);
    householdRepository.deleteById(request.householdId());
    householdRepository.flush();
    securityAuditEventRepository.append(
        SecurityAuditEntry.builder()
            .operation(request.operation())
            .actorAccountId(identity.accountId())
            .reason(request.reason())
            .resource("householdId", request.householdId())
            .build());
    return request.householdId();
  }

  private void lockHouseholds(HouseholdDeletionRequest request) {
    var disposition = request.finalAccount();
    var householdIds =
        disposition == null || disposition.destinationHouseholdId() == null
            ? Set.of(request.householdId())
            : Set.of(request.householdId(), disposition.destinationHouseholdId());
    var locked = householdRepository.lockByIds(householdIds);
    if (!locked.contains(request.householdId())) {
      throw new MutationRejection(new HouseholdDeletionRejections.HouseholdNotFound());
    }

    if (!locked.containsAll(householdIds)) {
      throw new MutationRejection(new HouseholdDeletionRejections.DestinationNotFound());
    }
  }

  private void lockReplacementManager(FinalAccountDisposition disposition) {
    if (disposition == null || disposition.choice() != FinalAccountChoice.DELETE_KEEPING_PROFILE) {
      return;
    }

    var replacement =
        userAccountRepository
            .findShareFacts(disposition.replacementManagerAccountId())
            .orElseThrow(
                () ->
                    new MutationRejection(
                        new HouseholdDeletionRejections.ReplacementManagerNotFound()));
    if (!replacement.householdId().equals(disposition.destinationHouseholdId())
        || !profileRepository.lockIfUnrestricted(replacement.personalProfileId())) {
      throw new MutationRejection(new HouseholdDeletionRejections.ReplacementManagerNotEligible());
    }
  }

  /** What deletion will do, for whoever may view the Household's administration. */
  public Optional<HouseholdDeletionPreflightDetails> deletionPreflight(
      AuthenticatedIdentity identity, UUID householdId) {
    if (!mayViewHousehold(identity, householdId)
        || householdRepository.findById(householdId).isEmpty()) {
      return Optional.empty();
    }

    var residents = userAccountRepository.findByHouseholdId(householdId);
    var linkedProfileIds = residents.stream().map(UserAccount::getPersonalProfileId).toList();
    var doomedProfiles =
        profileRepository.findByHouseholdId(householdId).stream()
            .filter(profile -> !linkedProfileIds.contains(profile.getId()))
            .map(profile -> new DoomedProfileDetails(profile.getId(), profile.getName()))
            .toList();
    var hostedVisits =
        shareRepository.findByHouseholdIdAndStatus(householdId, ProfileShareStatus.ACTIVE).stream()
            .filter(share -> !share.isStructural())
            .count();
    return Optional.of(
        new HouseholdDeletionPreflightDetails(
            residents.size(), doomedProfiles, (int) hostedVisits));
  }

  /** A page of the security audit, newest first; only ServerAdmin reads it (whole-surface gate). */
  public ListPage<SecurityAuditEventRecordView> securityAuditEvents(
      AuthenticatedIdentity identity, PageRequest request) {
    authorizationService.requireAllowed(identity, new Intent.ViewSecurityAudit());
    return Pager.page(securityAuditList, ServerScope.SERVER, request);
  }

  /** A page of a managed Profile's viewing activity, most recent first; hidden reads as empty. */
  public ListPage<SessionProgress> profileActivity(
      AuthenticatedIdentity identity, UUID profileId, PageRequest request) {
    return switch (authorizationService.decide(
        identity, new Intent.ViewProfileActivity(profileId))) {
      case Decision.Allowed<AuthorizationUnit> _ ->
          Pager.page(profileActivityList, profileId, request);
      case Decision.Denied<AuthorizationUnit> _ -> new ListPage<>(List.of(), false, false);
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
    };
  }

  private void dispose(UserAccount resident, FinalAccountDisposition disposition, Instant now) {
    switch (disposition.choice()) {
      case TRANSFER ->
          accountRemoval.move(
              AccountRemoval.Transfer.builder()
                  .accountId(resident.getId())
                  .sourceHouseholdId(resident.getHouseholdId())
                  .profileId(resident.getPersonalProfileId())
                  .destinationHouseholdId(disposition.destinationHouseholdId())
                  .sourceHouseholdAccess(SourceHouseholdAccess.END)
                  .now(now)
                  .build());
      case DELETE ->
          accountRemoval.erase(
              AccountRemoval.Deletion.builder()
                  .account(resident)
                  .profileDisposition(new AccountRemoval.ErasePersonalProfile())
                  .now(now)
                  .build());
      case DELETE_KEEPING_PROFILE ->
          accountRemoval.erase(
              AccountRemoval.Deletion.builder()
                  .account(resident)
                  .profileDisposition(
                      new AccountRemoval.PreservePersonalProfile(
                          disposition.replacementManagerAccountId(),
                          disposition.destinationHouseholdId()))
                  .now(now)
                  .build());
    }
  }

  private void endHostedVisits(UUID householdId, Instant now) {
    shareRepository
        .findByHouseholdIdAndStatus(householdId, ProfileShareStatus.ACTIVE)
        .forEach(
            share -> {
              shareRepository.tryEndActive(share.getId(), now);
              authSessionRepository.clearProfileSelectionFromLiveSessions(
                  share.getProfileId(), householdId);
              userAccountRepository
                  .findByPersonalProfileId(share.getProfileId())
                  .ifPresent(
                      visitor -> {
                        authSessionRepository.clearHouseholdContextFromAccountSessions(
                            visitor.getId(), householdId);
                        registrationLifecycle.revokeAllByAccountAndHousehold(
                            visitor.getId(), householdId, HOUSEHOLD_DELETED_REASON, now);
                      });
            });
  }

  private void deleteResidentProfiles(UUID householdId, Instant now) {
    profileRepository
        .findByHouseholdId(householdId)
        .forEach(profile -> accountRemoval.deleteProfile(profile.getId(), now));
  }

  private Optional<HouseholdDeletionRejections.Delete> dispositionRefusal(
      HouseholdDeletionRequest request) {
    var disposition = request.finalAccount();
    if (disposition == null) {
      return Optional.empty();
    }

    if (disposition.choice() == FinalAccountChoice.DELETE) {
      return Optional.empty();
    }

    var destinationRefusal = destinationRefusal(request.householdId(), disposition);
    if (destinationRefusal.isPresent()) {
      return destinationRefusal;
    }

    if (disposition.choice() == FinalAccountChoice.DELETE_KEEPING_PROFILE) {
      return replacementRefusal(disposition);
    }

    return Optional.empty();
  }

  private Optional<HouseholdDeletionRejections.Delete> destinationRefusal(
      UUID sourceHouseholdId, FinalAccountDisposition disposition) {
    if (disposition.destinationHouseholdId().equals(sourceHouseholdId)
        || householdRepository.findById(disposition.destinationHouseholdId()).isEmpty()) {
      return Optional.of(new HouseholdDeletionRejections.DestinationNotFound());
    }

    return Optional.empty();
  }

  private Optional<HouseholdDeletionRejections.Delete> replacementRefusal(
      FinalAccountDisposition disposition) {
    var replacement = userAccountRepository.findById(disposition.replacementManagerAccountId());
    if (replacement.isEmpty()) {
      return Optional.of(new HouseholdDeletionRejections.ReplacementManagerNotFound());
    }

    var anchored =
        replacement
            .filter(anchor -> anchor.getHouseholdId().equals(disposition.destinationHouseholdId()))
            .filter(this::isEligible)
            .isPresent();
    if (!anchored) {
      return Optional.of(new HouseholdDeletionRejections.ReplacementManagerNotEligible());
    }

    return Optional.empty();
  }

  private boolean isEligible(UserAccount account) {
    return profileRepository
        .findById(account.getPersonalProfileId())
        .filter(profile -> !profile.isRestricted())
        .isPresent();
  }

  private Optional<HouseholdDeletionRejections.Delete> deletionRefusal(
      AuthenticatedIdentity identity, UUID householdId) {
    return switch (authorizationService.decide(identity, new Intent.DeleteHousehold(householdId))) {
      case Decision.Allowed<AuthorizationUnit> _ -> Optional.empty();
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
      case Decision.Denied<AuthorizationUnit>(var reason) ->
          switch (reason) {
            case REAUTHENTICATION_REQUIRED ->
                Optional.of(new HouseholdDeletionRejections.ReauthenticationRequired());
            case POLICY -> {
              if (mayViewHousehold(identity, householdId)) {
                throw new AccessDeniedException("Not allowed.");
              }

              yield Optional.of(new HouseholdDeletionRejections.HouseholdNotFound());
            }
          };
    };
  }

  private boolean mayViewHousehold(AuthenticatedIdentity identity, UUID householdId) {
    return switch (authorizationService.decide(
        identity, new Intent.ViewHouseholdAdministration(householdId))) {
      case Decision.Allowed<AuthorizationUnit> _ -> true;
      case Decision.Denied<AuthorizationUnit> _ -> false;
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
    };
  }

  /** How the final Account leaves before its Household does. */
  private enum FinalAccountChoice {
    TRANSFER,
    DELETE,
    DELETE_KEEPING_PROFILE
  }

  @Builder
  private record FinalAccountDisposition(
      @NonNull FinalAccountChoice choice,
      UUID destinationHouseholdId,
      UUID replacementManagerAccountId) {}

  @Builder
  public record DeleteEmptyHouseholdCommand(@NonNull UUID householdId, String reason) {}

  @Builder
  public record TransferLastAccountAndDeleteHouseholdCommand(
      @NonNull UUID householdId, @NonNull UUID destinationHouseholdId, String reason) {}

  @Builder
  public record DeleteLastAccountAndHouseholdCommand(@NonNull UUID householdId, String reason) {}

  @Builder
  public record DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand(
      @NonNull UUID householdId,
      @NonNull UUID destinationHouseholdId,
      @NonNull UUID replacementManagerAccountId,
      String reason) {}

  @Builder
  private record HouseholdDeletionRequest(
      @NonNull UUID householdId,
      String reason,
      @NonNull String operation,
      FinalAccountDisposition finalAccount) {}

  public record DoomedProfileDetails(UUID id, String name) {}

  public record HouseholdDeletionPreflightDetails(
      int accountCount, List<DoomedProfileDetails> unlinkedProfiles, int hostedVisitCount) {}
}
