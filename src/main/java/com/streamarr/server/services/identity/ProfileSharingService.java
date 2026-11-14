package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.ChangedShare;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.ShareAction;
import com.streamarr.server.domain.auth.ShareCode;
import com.streamarr.server.domain.auth.ShareStatusFilter;
import com.streamarr.server.domain.pagination.Row;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.ShareCodeRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.DeviceRegistrationLifecycle;
import com.streamarr.server.services.auth.InvitationLifetime;
import com.streamarr.server.services.auth.ProfileShareLifecycle;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfileSafetyRule;
import com.streamarr.server.services.events.auth.ProfileShareChangedEvent;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profile sharing (ADR 0024 §Profile sharing): a share makes one Profile available to a Household
 * without copying data. Cedar authorizes each operation. Only one concurrent request can complete
 * each status transition. Authorization and database constraints prevent ending shares required by
 * Account membership. At commit, database constraints require an eligible HouseholdAdmin for hosted
 * restricted Profiles and unique available Profile names within each Household. Violations roll
 * back the transaction and return typed errors. Unsharing clears selections of that Profile there.
 * Ending a Personal Profile's share returns the visitor's sessions to their membership Household.
 */
@Service
@RequiredArgsConstructor
public class ProfileSharingService {

  private static final String UQ_LIVE_SHARE = "uq_profile_household_share_live";
  private static final String CHK_HOSTING_ADMIN = "chk_hosting_household_retains_eligible_admin";
  private static final String CHK_NAMES_UNIQUE = "chk_household_profile_names_unique";
  private static final String CHK_STRUCTURAL = "chk_structural_share_persists";

  private final AuthorizationService authorizationService;
  private final ProfileAuthorityExplanation authorityExplanation;
  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileShareLifecycle shareLifecycle;
  private final ProfileRepository profileRepository;
  private final HouseholdRepository householdRepository;
  private final UserAccountRepository userAccountRepository;
  private final AuthSessionRepository authSessionRepository;
  private final DeviceRegistrationLifecycle registrationLifecycle;
  private final SecurityAuditEventRepository securityAuditEventRepository;
  private final MutationTransactions mutationTransactions;
  private final InvitationLifetime invitationLifetime;
  private final HouseholdSharesList householdSharesList;
  private final ProfileSharesList profileSharesList;
  private final ShareCodeService shareCodeService;
  private final ShareCodeRepository shareCodeRepository;
  private final Clock clock;

  /**
   * Offers the Profile to the Household a share code names (ADR 0024 §Share codes). Presenting the
   * code is a credential attempt by the caller; a code that does not work is refused without naming
   * any Household. The offer spends the code in the transaction that creates it, so an offer that
   * is refused leaves the code live, and of two offers that present one code exactly one is made.
   */
  public Outcome<ProfileHouseholdShare, ShareRejections.Offer> offerProfileShare(
      AuthenticatedIdentity identity, UUID profileId, ShareCodeCommand shareCode) {
    var presented = shareCodeService.presentedShareCode(identity, shareCode);
    if (presented.isEmpty()) {
      return Outcome.rejected(new ShareCodeRejections.ShareCodeRefused());
    }

    return mutationTransactions.write(
        () -> offerInTransaction(identity, profileId, presented.get()),
        constraint ->
            UQ_LIVE_SHARE.equals(constraint)
                ? Optional.of(new ShareRejections.AlreadyShared())
                : Optional.empty());
  }

  private ProfileHouseholdShare offerInTransaction(
      AuthenticatedIdentity identity, UUID profileId, ShareCode shareCode) {
    if (!profileRepository.lockById(profileId)) {
      throw new MutationRejection(new ShareRejections.ProfileNotFound());
    }

    Optional<ShareRejections.Offer> refusal =
        refusalOf(
            identity,
            new Intent.OfferProfileShare(profileId),
            AuthorizationRefusal.Response.<ShareRejections.Offer>hiddenUnless(
                    () -> mayViewProfile(identity, profileId), ShareRejections.ProfileNotFound::new)
                .visibleAs(() -> authorityExplanation.managersOnly(identity, profileId)));
    refusal.ifPresent(
        rejection -> {
          throw new MutationRejection(rejection);
        });
    // The time is read once the offer holds the code, so a code that expires while another
    // transaction holds it is refused.
    shareCodeRepository.lockById(shareCode.getId());
    var now = clock.instant();
    var householdId = shareCode.getHouseholdId();
    // The offer holds the HouseholdAdmins it tells from before it spends the code: a change to an
    // issuer's authority holds the issuer's Account and then its codes, and the opposite order
    // would deadlock with it.
    userAccountRepository.lockHouseholdAdminIds(householdId);
    // The code may have been spent, withdrawn or invalidated since it was presented.
    if (!shareCodeRepository.trySpend(shareCode.getId(), now)) {
      throw new MutationRejection(new ShareCodeRejections.ShareCodeRefused());
    }

    shareLifecycle.supersedePending(profileId, householdId, now);
    return shareLifecycle.create(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(householdId)
            .status(ProfileShareStatus.PENDING)
            .offeredByAccountId(identity.accountId())
            .expiresAt(now.plus(invitationLifetime.invitationLifetime()))
            .build(),
        now);
  }

  public Outcome<ProfileHouseholdShare, ShareRejections.Accept> acceptProfileShare(
      AuthenticatedIdentity identity, UUID shareId) {
    var attempt =
        mutationTransactions.<ProfileHouseholdShare, ShareRejections.Accept>write(
            () -> acceptInTransaction(identity, shareId),
            constraint ->
                switch (constraint) {
                  case CHK_HOSTING_ADMIN -> Optional.of(new ShareRejections.NoEligibleAdmin());
                  case CHK_NAMES_UNIQUE -> Optional.of(new ShareRejections.NameConflict());
                  default -> Optional.empty();
                });
    // An offer invalidated by this very acceptance commits its withdrawal and explains it.
    return attempt.fold(
        share ->
            withdrawal(share)
                .<Outcome<ProfileHouseholdShare, ShareRejections.Accept>>map(Outcome::rejected)
                .orElseGet(() -> Outcome.accepted(share)),
        Outcome::rejected);
  }

  public Outcome<ProfileHouseholdShare, ShareRejections.Decline> rejectProfileShare(
      AuthenticatedIdentity identity, UUID shareId) {
    return declinePending(
        identity,
        new Intent.RejectProfileShare(shareId),
        shareId,
        () -> shareLifecycle.tryRejectPending(shareId, identity.accountId(), clock.instant()));
  }

  public Outcome<ProfileHouseholdShare, ShareRejections.Decline> cancelProfileShare(
      AuthenticatedIdentity identity, UUID shareId) {
    return declinePending(
        identity,
        new Intent.CancelProfileShare(shareId),
        shareId,
        () -> shareLifecycle.tryCancelPending(shareId, clock.instant()));
  }

  public Outcome<ProfileHouseholdShare, ShareRejections.End> endProfileShare(
      AuthenticatedIdentity identity, UUID shareId) {
    return endInTransaction(
        identity,
        shareId,
        EndSteps.<ShareRejections.End>builder()
            .authorize(
                () -> {
                  Optional<ShareRejections.End> refusal =
                      refusalOf(
                          identity,
                          new Intent.EndProfileShare(shareId),
                          AuthorizationRefusal.Response.<ShareRejections.End>hiddenUnless(
                                  () -> mayViewShare(identity, shareId),
                                  ShareRejections.ShareNotFound::new)
                              .visibleAs(
                                  () ->
                                      structuralRefusal(
                                          shareId, ShareRejections.StructuralShareCannotEnd::new)));
                  refusal.ifPresent(
                      rejection -> {
                        throw new MutationRejection(rejection);
                      });
                })
            .ending(new ShareAction.EndedBySide(identity.accountId()))
            .afterEnd(_ -> {})
            .notActive(ShareRejections.ShareNotActive::new)
            .structural(ShareRejections.StructuralShareCannotEnd::new)
            .build());
  }

  public Outcome<ProfileHouseholdShare, ShareRejections.AdministrativelyEnd>
      administrativelyEndProfileShare(AuthenticatedIdentity identity, UUID shareId, String reason) {
    return endInTransaction(
        identity,
        shareId,
        EndSteps.<ShareRejections.AdministrativelyEnd>builder()
            .authorize(
                () -> {
                  Optional<ShareRejections.AdministrativelyEnd> refusal =
                      refusalOf(
                          identity,
                          new Intent.AdministrativelyEndProfileShare(shareId),
                          AuthorizationRefusal.Response
                              .<ShareRejections.AdministrativelyEnd>hiddenUnless(
                                  () -> mayViewShare(identity, shareId),
                                  ShareRejections.ShareNotFound::new)
                              .visibleAs(
                                  () ->
                                      structuralRefusal(
                                          shareId, ShareRejections.StructuralShareCannotEnd::new))
                              .reauthenticatedAs(ShareRejections.ReauthenticationRequired::new));
                  refusal.ifPresent(
                      rejection -> {
                        throw new MutationRejection(rejection);
                      });
                  // Authority first, input second: an unauthorized caller learns nothing from the
                  // reason.
                  if (reason == null || reason.isBlank()) {
                    throw new MutationRejection(new ShareRejections.ReasonRequired());
                  }
                })
            .ending(new ShareAction.EndedByServer(identity.accountId()))
            .afterEnd(
                share ->
                    securityAuditEventRepository.append(
                        SecurityAuditEntry.builder()
                            .operation("administrativelyEndProfileShare")
                            .actorAccountId(identity.accountId())
                            .reason(reason)
                            .resource("profileId", share.getProfileId())
                            .resource("householdId", share.getHouseholdId())
                            .build()))
            .notActive(ShareRejections.ShareNotActive::new)
            .structural(ShareRejections.StructuralShareCannotEnd::new)
            .build());
  }

  /**
   * ADR 0024 §Share codes: the preflight names its target Household by a share code, and the
   * offerer learns only whether their Profile would lock there and whether a name conflict exists —
   * nothing else about that Household. A code that does not work, a missing Profile, and a Profile
   * the caller may not offer all answer nothing. The code stays live.
   */
  public Optional<SharePreflight> sharePreflight(
      AuthenticatedIdentity identity, UUID profileId, ShareCodeCommand shareCode) {
    var target = shareCodeService.presentedShareCode(identity, shareCode);
    var profile = profileRepository.findById(profileId);
    if (target.isEmpty() || profile.isEmpty()) {
      return Optional.empty();
    }

    var decision = authorizationService.decide(identity, new Intent.OfferProfileShare(profileId));
    if (decision instanceof Decision.Failed<AuthorizationUnit>) {
      throw new AuthorizationUnavailableException();
    }

    if (decision instanceof Decision.Denied<AuthorizationUnit>) {
      return Optional.empty();
    }

    var available =
        new ArrayList<>(profileRepository.findAvailableInHousehold(target.get().getHouseholdId()));
    available.removeIf(other -> other.getId().equals(profileId));
    var nameConflict =
        available.stream()
            .anyMatch(other -> other.getName().equalsIgnoreCase(profile.get().getName()));
    available.add(profile.get());
    var wouldLock = ProfileSafetyRule.lockedProfiles(available).contains(profileId);
    return Optional.of(new SharePreflight(wouldLock, nameConflict));
  }

  private Outcome<ProfileHouseholdShare, ShareRejections.Decline> declinePending(
      AuthenticatedIdentity identity,
      Intent.UnitIntent intent,
      UUID shareId,
      BooleanSupplier decline) {
    return mutationTransactions.write(
        () -> {
          requireShare(identity, shareId);
          Optional<ShareRejections.Decline> refusal =
              refusalOf(
                  identity,
                  intent,
                  AuthorizationRefusal.Response.hiddenUnless(
                      () -> mayViewShare(identity, shareId), ShareRejections.ShareNotFound::new));
          refusal.ifPresent(
              rejection -> {
                throw new MutationRejection(rejection);
              });
          if (!decline.getAsBoolean()) {
            throw lifecycleRefusal(identity, shareId, ShareRejections.ShareNotPending::new);
          }

          return shareRepository.findByIdAndReloadFromDatabase(shareId).orElseThrow();
        },
        _ -> Optional.empty());
  }

  private ProfileHouseholdShare acceptInTransaction(AuthenticatedIdentity identity, UUID shareId) {
    var offer = requireShare(identity, shareId);
    // A share whose Profile was deleted while this waited is gone, for every caller alike; nobody
    // has been authorized yet.
    if (!profileRepository.lockByShareId(shareId)) {
      throw new MutationRejection(new ShareRejections.ShareNotFound());
    }

    decideRefusal(identity, new Intent.AcceptProfileShare(shareId), shareId)
        .ifPresent(
            rejection -> {
              throw new MutationRejection(rejection);
            });
    var stillAuthorized =
        offer.getOfferedByAccountId() != null
            && switch (authorizationService.decideForAccount(
                offer.getOfferedByAccountId(),
                new Intent.OfferProfileShare(offer.getProfileId()))) {
              case Decision.Allowed<AuthorizationUnit> _ -> true;
              case Decision.Denied<AuthorizationUnit> _ -> false;
              case Decision.Failed<AuthorizationUnit> _ ->
                  throw new AuthorizationUnavailableException();
            };
    if (!stillAuthorized) {
      return invalidateUnauthorizedOffer(identity, shareId);
    }

    if (!shareLifecycle.tryActivatePending(shareId, identity.accountId(), clock.instant())) {
      throw lifecycleRefusal(identity, shareId, () -> notPending(shareId));
    }

    return shareRepository.findByIdAndReloadFromDatabase(shareId).orElseThrow();
  }

  private ProfileHouseholdShare invalidateUnauthorizedOffer(
      AuthenticatedIdentity identity, UUID shareId) {
    if (!shareLifecycle.tryInvalidatePending(
        shareId, ProfileShareLifecycle.OFFERER_NO_LONGER_AUTHORIZED, clock.instant())) {
      throw lifecycleRefusal(identity, shareId, () -> notPending(shareId));
    }

    return shareRepository.findByIdAndReloadFromDatabase(shareId).orElseThrow();
  }

  private ShareRejections.Accept notPending(UUID shareId) {
    return shareRepository
        .findByIdAndReloadFromDatabase(shareId)
        .flatMap(ProfileSharingService::withdrawal)
        .<ShareRejections.Accept>map(withdrawn -> withdrawn)
        .orElseGet(ShareRejections.ShareNotPending::new);
  }

  private static Optional<ShareRejections.OfferInvalidated> withdrawal(
      ProfileHouseholdShare share) {
    if (share.getStatus() != ProfileShareStatus.INVALIDATED) {
      return Optional.empty();
    }

    return Optional.of(new ShareRejections.OfferInvalidated());
  }

  /**
   * What one way of ending a share does around the end itself: its authorization, how the share's
   * sides are told it ended, its work after the end, and the lifecycle refusals it can meet, typed
   * for the verb that asked.
   */
  @Builder
  private record EndSteps<R>(
      Runnable authorize,
      ShareAction.Ending ending,
      Consumer<ProfileHouseholdShare> afterEnd,
      Supplier<? extends R> notActive,
      Supplier<? extends R> structural) {}

  private <R> Outcome<ProfileHouseholdShare, R> endInTransaction(
      AuthenticatedIdentity identity, UUID shareId, EndSteps<R> steps) {
    return mutationTransactions.write(
        () -> {
          requireShare(identity, shareId);
          profileRepository.lockSharedByShareId(shareId);
          steps.authorize().run();
          var now = clock.instant();
          if (!shareLifecycle.tryEndActive(shareId, steps.ending(), now)) {
            throw lifecycleRefusal(identity, shareId, steps.notActive());
          }

          var share = shareRepository.findByIdAndReloadFromDatabase(shareId).orElseThrow();
          // Unsharing returns affected sessions to the picker; a visitor whose Personal
          // Profile's share ended also loses the Household context itself.
          authSessionRepository.clearProfileSelectionFromLiveSessions(
              share.getProfileId(), share.getHouseholdId());
          userAccountRepository
              .findByPersonalProfileId(share.getProfileId())
              .ifPresent(
                  visitor -> {
                    authSessionRepository.clearHouseholdContextFromAccountSessions(
                        visitor.getId(), share.getHouseholdId());
                    registrationLifecycle.revokeAllByAccountAndHousehold(
                        visitor.getId(), share.getHouseholdId(), "share ended", now);
                  });
          steps.afterEnd().accept(share);
          return share;
        },
        constraint -> {
          if (CHK_STRUCTURAL.equals(constraint)) {
            return Optional.of(steps.structural().get());
          }

          return Optional.empty();
        });
  }

  /**
   * A share nobody can find answers the same way for everyone: there is nothing to protect. A share
   * that is over is found only by those who may still read it, the Profile's side and readers of
   * the Household's share history, so its Household's HouseholdAdmins cannot tell it from a share
   * deleted with its Profile.
   */
  private ProfileHouseholdShare requireShare(AuthenticatedIdentity identity, UUID shareId) {
    return shareRepository
        .findById(shareId)
        .filter(share -> share.statusAt(clock.instant()).isLive() || maySeeOver(identity, share))
        .orElseThrow(() -> new MutationRejection(new ShareRejections.ShareNotFound()));
  }

  private boolean maySeeOver(AuthenticatedIdentity identity, ProfileHouseholdShare share) {
    return mayViewHouseholdShareHistory(identity, share.getHouseholdId())
        || mayViewProfileShares(identity, share.getProfileId());
  }

  private <R> Optional<R> refusalOf(
      AuthenticatedIdentity identity,
      Intent.UnitIntent intent,
      AuthorizationRefusal.Response<R> response) {
    return AuthorizationRefusal.from(authorizationService.decide(identity, intent), response);
  }

  /** Explains to an authorized viewer that a share required by Account membership cannot end. */
  private <R> Optional<R> structuralRefusal(UUID shareId, Supplier<? extends R> structural) {
    return shareRepository
        .findById(shareId)
        .filter(ProfileHouseholdShare::isStructural)
        .map(_ -> structural.get());
  }

  private Optional<ShareRejections.Accept> decideRefusal(
      AuthenticatedIdentity identity, Intent.UnitIntent intent, UUID shareId) {
    return refusalOf(
        identity,
        intent,
        AuthorizationRefusal.Response.hiddenUnless(
            () -> mayViewShare(identity, shareId), ShareRejections.ShareNotFound::new));
  }

  // A refusal names a share only to those who may read it as it stands now, as the share queries
  // decide: the Profile's offering side, readers of the Household's share history, and the
  // HouseholdAdmins of the Household it is into while the offer is pending or the share is active.
  // Hosting the Profile elsewhere is not enough. The share is read again, past the copy this
  // request read first, since another request may have ended or deleted it since.
  private boolean mayViewShare(AuthenticatedIdentity identity, UUID shareId) {
    return shareRepository
        .findByIdAndReloadFromDatabase(shareId)
        .map(
            share ->
                maySeeOver(identity, share)
                    || (share.statusAt(clock.instant()).isLive()
                        && mayViewHousehold(identity, share.getHouseholdId())))
        .orElse(false);
  }

  // What became of a share is told only to a caller who may read it, even one Cedar allowed: an
  // offerer may still cancel its pending offer after it stops managing the Profile, and must not
  // learn from the refusal whether the share it offered is active.
  private MutationRejection lifecycleRefusal(
      AuthenticatedIdentity identity, UUID shareId, Supplier<?> refusal) {
    return new MutationRejection(
        mayViewShare(identity, shareId) ? refusal.get() : new ShareRejections.ShareNotFound());
  }

  private boolean mayViewProfile(AuthenticatedIdentity identity, UUID profileId) {
    return isAllowed(
        authorizationService.decide(identity, new Intent.ViewProfileAdministration(profileId)));
  }

  // Every share of a Profile is for its offering side; a HouseholdAdmin who only hosts it reads the
  // share into their own Household from that Household's side. A Profile that no longer exists has
  // no shares to read and no facts to decide on.
  private boolean mayViewProfileShares(AuthenticatedIdentity identity, UUID profileId) {
    return profileRepository.findById(profileId).isPresent()
        && isAllowed(
            authorizationService.decide(
                identity, new Intent.ViewProfileShareHouseholds(profileId)));
  }

  private boolean mayViewHousehold(AuthenticatedIdentity identity, UUID householdId) {
    return isAllowed(
        authorizationService.decide(identity, new Intent.ViewHouseholdAdministration(householdId)));
  }

  private boolean householdSideFollows(
      AuthenticatedIdentity identity, ProfileShareChangedEvent change) {
    var householdId = change.share().householdId();
    return change.live()
        ? mayViewHousehold(identity, householdId)
        : mayViewHouseholdShareHistory(identity, householdId);
  }

  private boolean mayViewHouseholdShareHistory(AuthenticatedIdentity identity, UUID householdId) {
    return isAllowed(
        authorizationService.decide(identity, new Intent.ViewHouseholdShareHistory(householdId)));
  }

  private static boolean isAllowed(Decision<AuthorizationUnit> decision) {
    return switch (decision) {
      case Decision.Allowed<AuthorizationUnit> _ -> true;
      case Decision.Denied<AuthorizationUnit> _ -> false;
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
    };
  }

  /**
   * A page of the shares into one Household that the scope keeps, newest first; hidden reads as
   * empty. Its HouseholdAdmins get only the offers still pending and the active shares; a reader of
   * the Household's share history, a ServerAdmin, gets every status the scope keeps.
   */
  public ListPage<ProfileHouseholdShare> householdShares(
      AuthenticatedIdentity identity, HouseholdSharesScope household, PageRequest request) {
    return readableScope(identity, household)
        .map(readable -> Pager.page(householdSharesList, readable, request))
        .orElseGet(ListPage::empty);
  }

  // The part of the scope that the reader may list: all of it for a reader of the Household's
  // share history, its live shares for the Household's HouseholdAdmins, and nothing for others.
  private Optional<HouseholdSharesScope> readableScope(
      AuthenticatedIdentity identity, HouseholdSharesScope household) {
    if (!mayViewHousehold(identity, household.householdId())) {
      return Optional.empty();
    }

    if (mayViewHouseholdShareHistory(identity, household.householdId())) {
      return Optional.of(household);
    }

    return household.keptToLive();
  }

  /**
   * How many offers into each Household wait for its decision, counted from the offers that {@link
   * #householdShares} lists kept to PENDING; a Household whose shares the reader may not list
   * counts none.
   */
  public Map<UUID, Integer> pendingOfferCounts(
      AuthenticatedIdentity identity, Set<UUID> householdIds) {
    var listed = householdsWhoseSharesReaderLists(identity, householdIds);
    var counts =
        listed.isEmpty()
            ? Map.<UUID, Integer>of()
            : shareRepository.countIntoHouseholds(
                listed, new ShareStatusFilter(Set.of(ProfileShareStatus.PENDING), clock.instant()));
    return householdIds.stream()
        .collect(Collectors.toMap(Function.identity(), id -> counts.getOrDefault(id, 0)));
  }

  // A reader of the server's Households lists the shares into each of them, so one decision
  // covers a page of Households; anyone else is decided Household by Household.
  private Set<UUID> householdsWhoseSharesReaderLists(
      AuthenticatedIdentity identity, Set<UUID> householdIds) {
    if (isAllowed(authorizationService.decide(identity, new Intent.ViewHouseholds()))) {
      return householdIds;
    }

    return householdIds.stream()
        .filter(householdId -> mayViewHousehold(identity, householdId))
        .collect(Collectors.toSet());
  }

  /**
   * A page of one Profile's shares in the scope's statuses, newest first, for the Profile's
   * offering side; hidden reads as empty.
   */
  public ListPage<ProfileHouseholdShare> profileShares(
      AuthenticatedIdentity identity, ShareListScope profile, PageRequest request) {
    if (!mayViewProfileShares(identity, profile.id())) {
      return ListPage.empty();
    }

    return Pager.page(profileSharesList, profile, request);
  }

  /**
   * Where {@link #householdShares} lists the share for the reader, so a share change can say where
   * the share goes; nothing when that list does not hold the share for them.
   */
  public Optional<Row<ProfileHouseholdShare>> householdShareRow(
      AuthenticatedIdentity identity, HouseholdSharesScope household, UUID shareId) {
    return readableScope(identity, household)
        .flatMap(readable -> householdSharesList.rowOf(readable, shareId));
  }

  /**
   * Where {@link #profileShares} lists the share for the reader, so a share change can say where
   * the share goes; nothing when that list does not hold the share for them.
   */
  public Optional<Row<ProfileHouseholdShare>> profileShareRow(
      AuthenticatedIdentity identity, ShareListScope profile, UUID shareId) {
    if (!mayViewProfileShares(identity, profile.id())) {
      return Optional.empty();
    }

    return profileSharesList.rowOf(profile, shareId);
  }

  /**
   * Refuses a session that may not follow share changes, such as a session on a TV, with FORBIDDEN,
   * or with AUTHORIZATION_UNAVAILABLE when no decision could be made.
   */
  public void requireViewProfileShareChanges(AuthenticatedIdentity identity) {
    authorizationService.requireAllowed(identity, new Intent.ViewProfileShareChanges());
  }

  /**
   * One committed share change as the reader may read it, decided as it is delivered. The reader
   * gets it when they may read the shares into its Household or the shares of its Profile, as
   * {@link #householdShares} and {@link #profileShares} decide; it then carries the share as it
   * stands now, or no share once the share was deleted. The Household's HouseholdAdmins get the
   * changes of a live share and none after it ends. The change that ends it reaches them as it was
   * made, with nothing read later, unless they also read the share from the Profile's side or the
   * Household's share history, as a ServerAdmin does, and get every change as it stands. An earlier
   * change delivered once the share is over or deleted does not reach them; the deletion of a live
   * share does, as the share's removal, while its Household still exists. A share deleted with its
   * Profile is decided by its Household alone. Refuses a reader who may no longer follow share
   * changes at all.
   */
  // Every read of one decision sees one snapshot. A Profile deleted after the read that finds it
  // would otherwise be missing when authorization reads its facts, and the failed decision would
  // end the reader's operation.
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Optional<ShareChange> shareChange(
      AuthenticatedIdentity identity, ProfileShareChangedEvent change) {
    requireViewProfileShareChanges(identity);
    var changed = change.share();
    if (householdSideFollows(identity, change)) {
      var shown =
          change.ended() && !followsEveryChange(identity, changed)
              ? ShareChange.endedAs(changed)
              : asItStands(changed);
      return Optional.of(shown).filter(_ -> stillShown(identity, change, shown));
    }

    if (mayViewProfileShares(identity, changed.profileId())) {
      return Optional.of(asItStands(changed));
    }

    return Optional.empty();
  }

  // A change that waited while its share ended or was deleted would show the Household's
  // HouseholdAdmins what came after; then only the change that ended or removed the share does.
  // A deleted Household has no HouseholdAdmins left to tell of a removal. Household deletion ends
  // the visits into it without the visiting Profiles' locks, so a Profile deletion beside it can
  // commit the removal of a visit after the Household deletion committed its end, and only a
  // session still carrying that Household's admin claims would hear it.
  private boolean stillShown(
      AuthenticatedIdentity identity, ProfileShareChangedEvent change, ShareChange shown) {
    return change.ended()
        || (change.removal() && householdRepository.existsById(change.share().householdId()))
        || shown.share().filter(share -> share.statusAt(clock.instant()).isLive()).isPresent()
        || followsEveryChange(identity, change.share());
  }

  private boolean followsEveryChange(AuthenticatedIdentity identity, ChangedShare changed) {
    return mayViewHouseholdShareHistory(identity, changed.householdId())
        || mayViewProfileShares(identity, changed.profileId());
  }

  private ShareChange asItStands(ChangedShare changed) {
    return new ShareChange(changed.shareId(), shareRepository.findById(changed.shareId()));
  }

  /** The offerer's whole preflight: nothing else about the target Household leaks. */
  @Builder
  public record SharePreflight(boolean wouldLock, boolean nameConflict) {}
}
