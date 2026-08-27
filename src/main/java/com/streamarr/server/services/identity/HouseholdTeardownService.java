package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.SecurityAuditEventRecordView;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.streaming.SessionProgressRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.DeviceRegistrationLifecycle;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.identity.AccountLifecycleService.ProfileDisposition;
import com.streamarr.server.services.identity.AccountLifecycleService.SourceAccess;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationService;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Household teardown, the security audit, and Profile activity (ADR 0024 §Teardown, §Audit).
 * Teardown is the one operation allowed to dispose of a Household's final Account: the caller
 * chooses its atomic disposition, every other Account must already be gone, and the teardown then
 * revokes registrations, invalidates every pending credential into the Household, ends hosted
 * visits, deletes the resident Profiles, and deletes the Household — one transaction, judged by the
 * deferred invariants.
 */
@Service
@RequiredArgsConstructor
public class HouseholdTeardownService {

  private static final String CHK_SERVER_ADMIN_REMAINS = "chk_enabled_server_admin_remains";
  private static final String TORN_DOWN_REASON = "Household torn down";

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
  private final SessionProgressRepository sessionProgressRepository;
  private final MutationTransactions mutationTransactions;
  private final PaginationService paginationService;
  private final Clock clock;

  public Outcome<UUID, TeardownRejections.TearDown> tearDownHousehold(
      AuthenticatedIdentity identity, TearDownHouseholdCommand command) {
    if (command.reason() == null || command.reason().isBlank()) {
      return Outcome.rejected(new TeardownRejections.ReasonRequired());
    }

    var refusal = teardownRefusal(identity, command.householdId());
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    if (householdRepository.findById(command.householdId()).isEmpty()) {
      return Outcome.rejected(new TeardownRejections.HouseholdNotFound());
    }

    var dispositionRefusal = dispositionRefusal(command);
    if (dispositionRefusal.isPresent()) {
      return Outcome.rejected(dispositionRefusal.get());
    }

    var now = clock.instant();
    return mutationTransactions.write(
        () -> {
          if (!householdRepository.lockById(command.householdId())) {
            throw new MutationRejection(new TeardownRejections.HouseholdNotFound());
          }

          var residents = userAccountRepository.findByHouseholdId(command.householdId());
          if (residents.size() > 1) {
            throw new MutationRejection(new TeardownRejections.AccountsRemain());
          }

          if (residents.size() == 1 && command.finalAccount() == null) {
            throw new MutationRejection(new TeardownRejections.FinalAccountRequired());
          }

          if (residents.isEmpty() && command.finalAccount() != null) {
            throw new MutationRejection(new TeardownRejections.FinalAccountUnexpected());
          }

          residents.stream()
              .findFirst()
              .ifPresent(resident -> dispose(resident, command.finalAccount(), now));

          // The TVs and every pending way into the Household fall before the rows do.
          registrationLifecycle.revokeAllByHousehold(command.householdId(), TORN_DOWN_REASON, now);
          accountInvitationRepository.invalidatePendingForHousehold(
              command.householdId(), TORN_DOWN_REASON, now);
          endHostedVisits(command.householdId(), now);
          deleteResidentProfiles(command.householdId(), now);
          householdRepository.deleteById(command.householdId());
          householdRepository.flush();
          securityAuditEventRepository.append(
              SecurityAuditEntry.builder()
                  .operation("tearDownHousehold")
                  .actorAccountId(identity.accountId())
                  .reason(command.reason())
                  .resource("householdId", command.householdId())
                  .build());
          return command.householdId();
        },
        constraint ->
            CHK_SERVER_ADMIN_REMAINS.equals(constraint)
                ? Optional.of(new TeardownRejections.LastServerAdmin())
                : Optional.empty());
  }

  /** What teardown will do, for whoever may view the Household's administration. */
  public Optional<TeardownPreflightDetails> teardownPreflight(
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
        new TeardownPreflightDetails(residents.size(), doomedProfiles, (int) hostedVisits));
  }

  /** The security audit, newest first; only ServerAdmin reads it (whole-surface gate). */
  public MediaPage<SecurityAuditEventRecordView> securityAuditEvents(
      AuthenticatedIdentity identity, SecurityAuditPageRequest request) {
    authorizationService.requireAllowed(identity, new Intent.ViewSecurityAudit());
    var fetchLimit = request.limit() + 1;
    var fetched =
        switch (request.direction()) {
          case FORWARD ->
              securityAuditEventRepository.pageNewestFirst(
                  request.cursorOccurredAt(), request.cursorId(), fetchLimit);
          case REVERSE ->
              securityAuditEventRepository.pageOldestFirst(
                  request.cursorOccurredAt(), request.cursorId(), fetchLimit);
        };
    return auditPage(fetched, request);
  }

  /** A managed Profile's viewing activity; hidden Profiles read as empty. */
  public MediaPage<SessionProgress> profileActivity(
      AuthenticatedIdentity identity, UUID profileId, KeysetPaginationOptions options) {
    return switch (authorizationService.decide(
        identity, new Intent.ViewProfileActivity(profileId))) {
      case Decision.Allowed<AuthorizationUnit> _ -> profileActivityPage(profileId, options);
      case Decision.Denied<AuthorizationUnit> _ -> profileActivityPage(List.of(), options);
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
    };
  }

  private MediaPage<SecurityAuditEventRecordView> auditPage(
      List<SecurityAuditEventRecordView> fetched, SecurityAuditPageRequest request) {
    var hasLookahead = fetched.size() > request.limit();
    var selected = fetched.subList(0, Math.min(fetched.size(), request.limit()));
    if (request.direction() == PaginationDirection.REVERSE) {
      selected = selected.reversed();
    }

    var items = selected.stream().map(row -> new PageItem<>(row, row.occurredAt())).toList();
    var hasCursor = request.cursorId() != null;
    return request.direction() == PaginationDirection.REVERSE
        ? new MediaPage<>(items, hasCursor, hasLookahead)
        : new MediaPage<>(items, hasLookahead, hasCursor);
  }

  private MediaPage<SessionProgress> profileActivityPage(
      UUID profileId, KeysetPaginationOptions options) {
    return profileActivityPage(
        sessionProgressRepository.findByProfileIdOrderByLastModifiedOnDesc(profileId), options);
  }

  private MediaPage<SessionProgress> profileActivityPage(
      List<SessionProgress> activity, KeysetPaginationOptions options) {
    var items =
        activity.stream()
            .sorted(
                Comparator.comparing(SessionProgress::getLastModifiedOn)
                    .reversed()
                    .thenComparing(SessionProgress::getId))
            .map(progress -> new PageItem<>(progress, progress.getLastModifiedOn()))
            .toList();
    return paginationService.buildKeysetPage(items, options, SessionProgress::getId);
  }

  private void dispose(UserAccount resident, FinalAccountDisposition disposition, Instant now) {
    switch (disposition.choice()) {
      case TRANSFER -> {
        var destinationEmpty =
            userAccountRepository.findByHouseholdId(disposition.destinationHouseholdId()).isEmpty();
        accountRemoval.move(
            resident.getId(),
            resident.getHouseholdId(),
            resident.getPersonalProfileId(),
            disposition.destinationHouseholdId(),
            destinationEmpty,
            SourceAccess.END,
            now);
      }

      case DELETE -> accountRemoval.erase(resident, ProfileDisposition.ERASE, null, now);
      case DELETE_KEEPING_PROFILE -> {
        accountRemoval.erase(
            resident, ProfileDisposition.KEEP, disposition.replacementManagerAccountId(), now);
        // The preserved Profile cannot stay in a Household about to vanish: it moves behind
        // its named anchor.
        profileRepository.tryRehome(
            resident.getPersonalProfileId(),
            resident.getHouseholdId(),
            disposition.destinationHouseholdId());
        shareRepository
            .findByProfileIdAndHouseholdIdAndStatus(
                resident.getPersonalProfileId(),
                resident.getHouseholdId(),
                ProfileShareStatus.ACTIVE)
            .ifPresent(share -> shareRepository.tryEndActive(share.getId(), now));
        shareRepository.upsertStructural(
            resident.getPersonalProfileId(), disposition.destinationHouseholdId(), now);
        shareRepository.tryDemoteStructural(
            resident.getPersonalProfileId(), disposition.destinationHouseholdId(), now);
      }
    }
  }

  private void endHostedVisits(UUID householdId, Instant now) {
    shareRepository
        .findByHouseholdIdAndStatus(householdId, ProfileShareStatus.ACTIVE)
        .forEach(
            share -> {
              shareRepository.tryEndActive(share.getId(), now);
              authSessionRepository.clearProfileSelectionFromLiveSessions(
                  share.getProfileId(), householdId, now);
              userAccountRepository
                  .findByPersonalProfileId(share.getProfileId())
                  .ifPresent(
                      visitor -> {
                        authSessionRepository.clearHouseholdContextFromAccountSessions(
                            visitor.getId(), householdId, now);
                        registrationLifecycle.revokeAllByAccountAndHousehold(
                            visitor.getId(), householdId, TORN_DOWN_REASON, now);
                      });
            });
  }

  private void deleteResidentProfiles(UUID householdId, Instant now) {
    profileRepository
        .findByHouseholdId(householdId)
        .forEach(profile -> accountRemoval.deleteProfile(profile.getId(), now));
  }

  private Optional<TeardownRejections.TearDown> dispositionRefusal(
      TearDownHouseholdCommand command) {
    var disposition = command.finalAccount();
    if (disposition == null) {
      return Optional.empty();
    }

    if (disposition.choice() == FinalAccountChoice.DELETE) {
      return Optional.empty();
    }

    var destinationRefusal = destinationRefusal(command.householdId(), disposition);
    if (destinationRefusal.isPresent()) {
      return destinationRefusal;
    }

    if (disposition.choice() == FinalAccountChoice.DELETE_KEEPING_PROFILE) {
      return replacementRefusal(disposition);
    }

    return Optional.empty();
  }

  private Optional<TeardownRejections.TearDown> destinationRefusal(
      UUID sourceHouseholdId, FinalAccountDisposition disposition) {
    if (disposition.destinationHouseholdId() == null) {
      return Optional.of(new TeardownRejections.DestinationRequired());
    }

    if (disposition.destinationHouseholdId().equals(sourceHouseholdId)
        || householdRepository.findById(disposition.destinationHouseholdId()).isEmpty()) {
      return Optional.of(new TeardownRejections.DestinationNotFound());
    }

    return Optional.empty();
  }

  private Optional<TeardownRejections.TearDown> replacementRefusal(
      FinalAccountDisposition disposition) {
    if (disposition.replacementManagerAccountId() == null) {
      return Optional.of(new TeardownRejections.ReplacementManagerRequired());
    }

    var replacement = userAccountRepository.findById(disposition.replacementManagerAccountId());
    if (replacement.isEmpty()) {
      return Optional.of(new TeardownRejections.ReplacementManagerNotFound());
    }

    var anchored =
        replacement
            .filter(anchor -> anchor.getHouseholdId().equals(disposition.destinationHouseholdId()))
            .filter(this::isEligible)
            .isPresent();
    if (!anchored) {
      return Optional.of(new TeardownRejections.ReplacementManagerNotEligible());
    }

    return Optional.empty();
  }

  private boolean isEligible(UserAccount account) {
    return profileRepository
        .findById(account.getPersonalProfileId())
        .filter(profile -> !profile.isRestricted())
        .isPresent();
  }

  private Optional<TeardownRejections.TearDown> teardownRefusal(
      AuthenticatedIdentity identity, UUID householdId) {
    return switch (authorizationService.decide(
        identity, new Intent.TearDownHousehold(householdId))) {
      case Decision.Allowed<AuthorizationUnit> _ -> Optional.empty();
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
      case Decision.Denied<AuthorizationUnit>(var reason) ->
          switch (reason) {
            case REAUTHENTICATION_REQUIRED ->
                Optional.of(new TeardownRejections.ReauthenticationRequired());
            case POLICY -> {
              if (mayViewHousehold(identity, householdId)) {
                throw new AccessDeniedException("Not allowed.");
              }

              yield Optional.of(new TeardownRejections.HouseholdNotFound());
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
  public enum FinalAccountChoice {
    TRANSFER,
    DELETE,
    DELETE_KEEPING_PROFILE
  }

  @Builder
  public record FinalAccountDisposition(
      FinalAccountChoice choice, UUID destinationHouseholdId, UUID replacementManagerAccountId) {}

  @Builder
  public record TearDownHouseholdCommand(
      UUID householdId, String reason, FinalAccountDisposition finalAccount) {}

  @Builder
  public record SecurityAuditPageRequest(
      PaginationDirection direction, Instant cursorOccurredAt, UUID cursorId, int limit) {}

  public record DoomedProfileDetails(UUID id, String name) {}

  public record TeardownPreflightDetails(
      int accountCount, List<DoomedProfileDetails> unlinkedProfiles, int hostedVisitCount) {}
}
