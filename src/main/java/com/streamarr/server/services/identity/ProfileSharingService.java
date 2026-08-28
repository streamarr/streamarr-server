package com.streamarr.server.services.identity;

import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfileSafetyRule;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Profile sharing (ADR 0024 §Profile sharing): a share makes one Profile available to a Household
 * without copying data. Cedar decides every seat; the conditional status transitions give every
 * race exactly one winner; a membership-required share is refused up front with its typed error
 * (Cedar and T3 both refuse it — belt and braces); T7 and T8 judge the final state at commit and
 * roll back into typed errors. Unsharing clears any selection of that Profile there, and ending a
 * Personal Profile's share drops the visitor's sessions back to their membership Household.
 */
@Service
@RequiredArgsConstructor
public class ProfileSharingService {

  private static final String UQ_LIVE_SHARE = "uq_profile_household_share_live";
  private static final String CHK_HOSTING_ADMIN = "chk_hosting_household_retains_eligible_admin";
  private static final String CHK_NAMES_UNIQUE = "chk_household_profile_names_unique";
  private static final String CHK_STRUCTURAL = "chk_structural_share_persists";

  private final AuthorizationService authorizationService;
  private final ProfileHouseholdShareRepository shareRepository;
  private final ProfileRepository profileRepository;
  private final HouseholdRepository householdRepository;
  private final UserAccountRepository userAccountRepository;
  private final AuthSessionRepository authSessionRepository;
  private final SecurityAuditEventRepository securityAuditEventRepository;
  private final MutationTransactions mutationTransactions;
  private final CredentialCodeProperties credentialCodeProperties;
  private final PaginationService paginationService;
  private final Clock clock;

  public Outcome<ProfileHouseholdShare, ShareRejections.Offer> offerProfileShare(
      AuthenticatedIdentity identity, UUID profileId, UUID householdId) {
    return mutationTransactions.write(
        () -> {
          if (!profileRepository.lockById(profileId)) {
            throw new MutationRejection(new ShareRejections.ProfileNotFound());
          }

          Optional<ShareRejections.Offer> refusal =
              refusalOf(
                  identity,
                  new Intent.OfferProfileShare(profileId),
                  () -> mayViewProfile(identity, profileId),
                  Refusals.<ShareRejections.Offer>hiddenAs(ShareRejections.ProfileNotFound::new));
          refusal.ifPresent(
              rejection -> {
                throw new MutationRejection(rejection);
              });
          if (householdRepository.findById(householdId).isEmpty()) {
            throw new MutationRejection(new ShareRejections.HouseholdNotFound());
          }

          var now = clock.instant();
          shareRepository.supersedePending(profileId, householdId, now);
          return shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profileId)
                  .householdId(householdId)
                  .status(ProfileShareStatus.PENDING)
                  .offeredByAccountId(identity.accountId())
                  .expiresAt(now.plus(credentialCodeProperties.invitationTtl()))
                  .build());
        },
        constraint ->
            UQ_LIVE_SHARE.equals(constraint)
                ? Optional.of(new ShareRejections.AlreadyShared())
                : Optional.empty());
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
        identity, new Intent.RejectProfileShare(shareId), shareId, ProfileShareStatus.REJECTED);
  }

  public Outcome<ProfileHouseholdShare, ShareRejections.Decline> cancelProfileShare(
      AuthenticatedIdentity identity, UUID shareId) {
    return declinePending(
        identity, new Intent.CancelProfileShare(shareId), shareId, ProfileShareStatus.CANCELED);
  }

  public Outcome<ProfileHouseholdShare, ShareRejections.End> endProfileShare(
      AuthenticatedIdentity identity, UUID shareId) {
    return endInTransaction(
        shareId,
        () -> {
          Optional<ShareRejections.End> refusal =
              refusalOf(
                  identity,
                  new Intent.EndProfileShare(shareId),
                  () -> mayViewShare(identity, shareId),
                  Refusals.<ShareRejections.End>hiddenAs(ShareRejections.ShareNotFound::new)
                      .visibleAs(
                          () ->
                              structuralRefusal(
                                  shareId, ShareRejections.StructuralShareCannotEnd::new)));
          refusal.ifPresent(
              rejection -> {
                throw new MutationRejection(rejection);
              });
        },
        _ -> {},
        new EndRefusals<>(
            ShareRejections.ShareNotActive::new, ShareRejections.StructuralShareCannotEnd::new));
  }

  public Outcome<ProfileHouseholdShare, ShareRejections.AdministrativelyEnd>
      administrativelyEndProfileShare(AuthenticatedIdentity identity, UUID shareId, String reason) {
    return endInTransaction(
        shareId,
        () -> {
          Optional<ShareRejections.AdministrativelyEnd> refusal =
              refusalOf(
                  identity,
                  new Intent.AdministrativelyEndProfileShare(shareId),
                  () -> mayViewShare(identity, shareId),
                  Refusals.<ShareRejections.AdministrativelyEnd>hiddenAs(
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
          // Authority first, input second: an unauthorized caller learns nothing from the reason.
          if (reason == null || reason.isBlank()) {
            throw new MutationRejection(new ShareRejections.ReasonRequired());
          }
        },
        share ->
            securityAuditEventRepository.append(
                SecurityAuditEntry.builder()
                    .operation("administrativelyEndProfileShare")
                    .actorAccountId(identity.accountId())
                    .reason(reason)
                    .resource("profileId", share.getProfileId())
                    .resource("householdId", share.getHouseholdId())
                    .build()),
        new EndRefusals<>(
            ShareRejections.ShareNotActive::new, ShareRejections.StructuralShareCannotEnd::new));
  }

  /**
   * ADR 0024 §PIN safety: the offerer learns only whether their Profile would lock in the target
   * Household and whether a name conflict exists — nothing else about that Household.
   */
  public Optional<SharePreflight> sharePreflight(
      AuthenticatedIdentity identity, UUID profileId, UUID householdId) {
    var profile = profileRepository.findById(profileId);
    if (profile.isEmpty() || householdRepository.findById(householdId).isEmpty()) {
      return Optional.empty();
    }

    var decision = authorizationService.decide(identity, new Intent.OfferProfileShare(profileId));
    if (decision instanceof Decision.Failed<AuthorizationUnit>) {
      throw new AuthorizationUnavailableException();
    }

    if (decision instanceof Decision.Denied<AuthorizationUnit>) {
      return Optional.empty();
    }

    var available = new ArrayList<>(profileRepository.findAvailableInHousehold(householdId));
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
      ProfileShareStatus target) {
    return mutationTransactions.write(
        () -> {
          requireShare(shareId);
          Optional<ShareRejections.Decline> refusal =
              refusalOf(
                  identity,
                  intent,
                  () -> mayViewShare(identity, shareId),
                  Refusals.<ShareRejections.Decline>hiddenAs(ShareRejections.ShareNotFound::new));
          refusal.ifPresent(
              rejection -> {
                throw new MutationRejection(rejection);
              });
          if (!shareRepository.tryDeclinePending(shareId, target, clock.instant())) {
            throw new MutationRejection(new ShareRejections.ShareNotPending());
          }

          return shareRepository.findRefreshedById(shareId).orElseThrow();
        },
        _ -> Optional.empty());
  }

  private ProfileHouseholdShare acceptInTransaction(AuthenticatedIdentity identity, UUID shareId) {
    var offer = requireShare(shareId);
    if (!profileRepository.lockByShareId(shareId)) {
      throw new MutationRejection(new ShareRejections.ShareNotPending());
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
      return invalidateUnauthorizedOffer(shareId);
    }

    if (!shareRepository.tryActivatePending(shareId, clock.instant())) {
      throw new MutationRejection(notPending(shareId));
    }

    return shareRepository.findRefreshedById(shareId).orElseThrow();
  }

  private ProfileHouseholdShare invalidateUnauthorizedOffer(UUID shareId) {
    if (!shareRepository.tryInvalidatePending(
        shareId, "offerer no longer authorized", clock.instant())) {
      throw new MutationRejection(notPending(shareId));
    }

    return shareRepository.findRefreshedById(shareId).orElseThrow();
  }

  /** An offer that is no longer pending explains a withdrawal; any other state is just decided. */
  private ShareRejections.Accept notPending(UUID shareId) {
    return shareRepository
        .findRefreshedById(shareId)
        .flatMap(ProfileSharingService::withdrawal)
        .<ShareRejections.Accept>map(withdrawn -> withdrawn)
        .orElseGet(ShareRejections.ShareNotPending::new);
  }

  private static Optional<ShareRejections.OfferInvalidated> withdrawal(
      ProfileHouseholdShare share) {
    if (share.getStatus() != ProfileShareStatus.INVALIDATED) {
      return Optional.empty();
    }

    return Optional.of(
        new ShareRejections.OfferInvalidated(share.getInvalidationReason().orElse("withdrawn")));
  }

  /** The lifecycle refusals an end can meet, typed for the verb that asked. */
  private record EndRefusals<R>(
      Supplier<? extends R> notActive, Supplier<? extends R> structural) {}

  private <R> Outcome<ProfileHouseholdShare, R> endInTransaction(
      UUID shareId,
      Runnable authorize,
      Consumer<ProfileHouseholdShare> afterEnd,
      EndRefusals<R> refusals) {
    return mutationTransactions.write(
        () -> {
          requireShare(shareId);
          authorize.run();
          var now = clock.instant();
          if (!shareRepository.tryEndActive(shareId, now)) {
            throw new MutationRejection(refusals.notActive().get());
          }

          var share = shareRepository.findRefreshedById(shareId).orElseThrow();
          // Unsharing returns affected sessions to the picker; a visitor whose Personal
          // Profile's share ended also loses the Household context itself.
          authSessionRepository.clearProfileSelectionFromLiveSessions(
              share.getProfileId(), share.getHouseholdId(), now);
          userAccountRepository
              .findByPersonalProfileId(share.getProfileId())
              .ifPresent(
                  visitor ->
                      authSessionRepository.clearHouseholdContextFromAccountSessions(
                          visitor.getId(), share.getHouseholdId(), now));
          afterEnd.accept(share);
          return share;
        },
        constraint -> {
          if (CHK_STRUCTURAL.equals(constraint)) {
            return Optional.of(refusals.structural().get());
          }

          return Optional.empty();
        });
  }

  /** A share nobody can find answers the same way for everyone: there is nothing to protect. */
  private ProfileHouseholdShare requireShare(UUID shareId) {
    return shareRepository
        .findById(shareId)
        .orElseThrow(() -> new MutationRejection(new ShareRejections.ShareNotFound()));
  }

  /**
   * How a verb answers each way authorization can refuse it (ADR 0026 oracle rule): hidden when the
   * caller may not view the resource, a typed refusal when it may and the resource's own state
   * explains the denial, FORBIDDEN otherwise.
   */
  private record Refusals<R>(
      Supplier<? extends R> hidden,
      Supplier<Optional<R>> visible,
      Optional<Supplier<? extends R>> reauthenticationRequired) {

    static <R> Refusals<R> hiddenAs(Supplier<? extends R> hidden) {
      return new Refusals<>(hidden, Optional::empty, Optional.empty());
    }

    Refusals<R> visibleAs(Supplier<Optional<R>> explained) {
      return new Refusals<>(hidden, explained, reauthenticationRequired);
    }

    Refusals<R> reauthenticatedAs(Supplier<? extends R> stepUp) {
      return new Refusals<>(hidden, visible, Optional.of(stepUp));
    }
  }

  private <R> Optional<R> refusalOf(
      AuthenticatedIdentity identity,
      Intent.UnitIntent intent,
      BooleanSupplier mayView,
      Refusals<R> refusals) {
    return switch (authorizationService.decide(identity, intent)) {
      case Decision.Allowed<AuthorizationUnit> _ -> Optional.empty();
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
      case Decision.Denied<AuthorizationUnit>(var reason) ->
          switch (reason) {
            case REAUTHENTICATION_REQUIRED ->
                Optional.of(
                    refusals
                        .reauthenticationRequired()
                        .orElseThrow(AuthorizationUnavailableException::new)
                        .get());
            case POLICY -> {
              if (!mayView.getAsBoolean()) {
                yield Optional.of(refusals.hidden().get());
              }

              var explained = refusals.visible().get();
              if (explained.isPresent()) {
                yield explained;
              }

              throw new AccessDeniedException("Not allowed.");
            }
          };
    };
  }

  /** T3 explained to a viewer: the share is refused for what it is, not for who asks. */
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
        () -> mayViewShare(identity, shareId),
        Refusals.<ShareRejections.Accept>hiddenAs(ShareRejections.ShareNotFound::new));
  }

  /** A share is visible to whoever may view its Profile's or its target Household's admin view. */
  private boolean mayViewShare(AuthenticatedIdentity identity, UUID shareId) {
    return shareRepository
        .findById(shareId)
        .map(
            share ->
                mayViewProfile(identity, share.getProfileId())
                    || mayViewHousehold(identity, share.getHouseholdId()))
        .orElse(false);
  }

  private boolean mayViewProfile(AuthenticatedIdentity identity, UUID profileId) {
    return isAllowed(
        authorizationService.decide(identity, new Intent.ViewProfileAdministration(profileId)));
  }

  private boolean mayViewHousehold(AuthenticatedIdentity identity, UUID householdId) {
    return isAllowed(
        authorizationService.decide(identity, new Intent.ViewHouseholdAdministration(householdId)));
  }

  private static boolean isAllowed(Decision<AuthorizationUnit> decision) {
    return switch (decision) {
      case Decision.Allowed<AuthorizationUnit> _ -> true;
      case Decision.Denied<AuthorizationUnit> _ -> false;
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
    };
  }

  /** Pending offers into one Household, for its admins; empty when the caller may not view. */
  public MediaPage<ProfileHouseholdShare> pendingShareOffers(
      AuthenticatedIdentity identity, UUID householdId, KeysetPaginationOptions options) {
    if (!mayViewHousehold(identity, householdId)) {
      return page(List.of(), options);
    }

    return page(
        shareRepository.findPendingByHouseholdId(householdId, clock.instant(), options), options);
  }

  /** Every share of one Profile, for its managers; empty when the caller may not view. */
  public MediaPage<ProfileHouseholdShare> profileShares(
      AuthenticatedIdentity identity, UUID profileId, KeysetPaginationOptions options) {
    if (profileRepository.findById(profileId).isEmpty() || !mayViewProfile(identity, profileId)) {
      return page(List.of(), options);
    }

    return page(shareRepository.findByProfileId(profileId, options), options);
  }

  private MediaPage<ProfileHouseholdShare> page(
      List<ProfileHouseholdShare> shares, KeysetPaginationOptions options) {
    var items = shares.stream().map(share -> new PageItem<>(share, null)).toList();
    return paginationService.buildMediaPage(
        items, options.getPaginationOptions(), options.getCursorId());
  }

  /** The offerer's whole preflight: nothing else about the target Household leaks. */
  @Builder
  public record SharePreflight(boolean wouldLock, boolean nameConflict) {}
}
