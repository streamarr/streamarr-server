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
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfileSafetyRule;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
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
 * race exactly one winner; T3, T7, and T8 judge the final state at commit and roll back into typed
 * errors. Unsharing clears any selection of that Profile there, and ending a Personal Profile's
 * share drops the visitor's sessions back to their membership Household.
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
  private final Clock clock;

  public Outcome<ProfileHouseholdShare, ShareRejections.Offer> offerProfileShare(
      AuthenticatedIdentity identity, UUID profileId, UUID householdId) {
    var refusal =
        refusalOf(
            identity,
            new Intent.OfferProfileShare(profileId),
            () -> mayViewProfile(identity, profileId),
            ShareRejections.ProfileNotFound::new,
            null);
    if (refusal.isPresent()) {
      return Outcome.rejected((ShareRejections.Offer) refusal.get());
    }
    if (householdRepository.findById(householdId).isEmpty()) {
      return Outcome.rejected(new ShareRejections.HouseholdNotFound());
    }
    return mutationTransactions.write(
        () ->
            shareRepository.saveAndFlush(
                ProfileHouseholdShare.builder()
                    .profileId(profileId)
                    .householdId(householdId)
                    .status(ProfileShareStatus.PENDING)
                    .offeredByAccountId(identity.accountId())
                    .expiresAt(clock.instant().plus(credentialCodeProperties.invitationTtl()))
                    .build()),
        constraint ->
            UQ_LIVE_SHARE.equals(constraint)
                ? Optional.of(new ShareRejections.AlreadyShared())
                : Optional.empty());
  }

  public Outcome<ProfileHouseholdShare, ShareRejections.Decide> acceptProfileShare(
      AuthenticatedIdentity identity, UUID shareId) {
    var refusal = decideRefusal(identity, new Intent.AcceptProfileShare(shareId), shareId);
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }
    return mutationTransactions.write(
        () -> {
          if (!shareRepository.tryActivate(shareId, clock.instant())) {
            throw new MutationRejection(new ShareRejections.ShareNotPending());
          }
          return shareRepository.findById(shareId).orElseThrow();
        },
        constraint ->
            switch (constraint) {
              case CHK_HOSTING_ADMIN -> Optional.of(new ShareRejections.NoEligibleAdmin());
              case CHK_NAMES_UNIQUE -> Optional.of(new ShareRejections.NameConflict());
              default -> Optional.empty();
            });
  }

  public Outcome<ProfileHouseholdShare, ShareRejections.Decide> rejectProfileShare(
      AuthenticatedIdentity identity, UUID shareId) {
    return declinePending(
        identity, new Intent.RejectProfileShare(shareId), shareId, ProfileShareStatus.REJECTED);
  }

  public Outcome<ProfileHouseholdShare, ShareRejections.Decide> cancelProfileShare(
      AuthenticatedIdentity identity, UUID shareId) {
    return declinePending(
        identity, new Intent.CancelProfileShare(shareId), shareId, ProfileShareStatus.CANCELED);
  }

  public Outcome<ProfileHouseholdShare, ShareRejections.End> endProfileShare(
      AuthenticatedIdentity identity, UUID shareId) {
    var refusal =
        refusalOf(
            identity,
            new Intent.EndProfileShare(shareId),
            () -> mayViewShare(identity, shareId),
            ShareRejections.ShareNotFound::new,
            null);
    if (refusal.isPresent()) {
      return Outcome.rejected((ShareRejections.End) refusal.get());
    }
    return endInTransaction(shareId);
  }

  public Outcome<ProfileHouseholdShare, ShareRejections.End> forceEndProfileShare(
      AuthenticatedIdentity identity, UUID shareId, String reason) {
    if (reason == null || reason.isBlank()) {
      return Outcome.rejected(new ShareRejections.ReasonRequired());
    }
    var refusal =
        refusalOf(
            identity,
            new Intent.ForceEndProfileShare(shareId),
            () -> mayViewShare(identity, shareId),
            ShareRejections.ShareNotFound::new,
            ShareRejections.ReauthenticationRequired::new);
    if (refusal.isPresent()) {
      return Outcome.rejected((ShareRejections.End) refusal.get());
    }
    return endInTransaction(
        shareId,
        share ->
            securityAuditEventRepository.append(
                SecurityAuditEntry.builder()
                    .operation("forceEndProfileShare")
                    .actorAccountId(identity.accountId())
                    .reason(reason)
                    .resource("profileId", share.getProfileId())
                    .resource("householdId", share.getHouseholdId())
                    .build()));
  }

  /**
   * ADR 0024 §PIN safety: the offerer learns only whether their Profile would lock in the target
   * Household and which name conflict exists — nothing else about that Household.
   */
  public Optional<SharePreflight> sharePreflight(
      AuthenticatedIdentity identity, UUID profileId, UUID householdId) {
    var decision = authorizationService.decide(identity, new Intent.OfferProfileShare(profileId));
    if (decision instanceof Decision.Failed<?>) {
      throw new AuthorizationUnavailableException();
    }
    if (!(decision instanceof Decision.Allowed<?>)) {
      return Optional.empty();
    }
    var profile = profileRepository.findById(profileId);
    if (profile.isEmpty() || householdRepository.findById(householdId).isEmpty()) {
      return Optional.empty();
    }
    var available = new ArrayList<>(profileRepository.findAvailableInHousehold(householdId));
    var nameConflict =
        available.stream()
            .anyMatch(other -> other.getName().equalsIgnoreCase(profile.get().getName()));
    available.removeIf(other -> other.getId().equals(profileId));
    available.add(profile.get());
    var wouldLock = ProfileSafetyRule.lockedProfiles(available).contains(profileId);
    return Optional.of(new SharePreflight(wouldLock, nameConflict));
  }

  private Outcome<ProfileHouseholdShare, ShareRejections.Decide> declinePending(
      AuthenticatedIdentity identity, Intent<?> intent, UUID shareId, ProfileShareStatus target) {
    var refusal =
        refusalOf(
            identity,
            intent,
            () -> mayViewShare(identity, shareId),
            ShareRejections.ShareNotFound::new,
            null);
    if (refusal.isPresent()) {
      return Outcome.rejected((ShareRejections.Decide) refusal.get());
    }
    return mutationTransactions.write(
        () -> {
          if (!shareRepository.tryDecline(shareId, target, clock.instant())) {
            throw new MutationRejection(new ShareRejections.ShareNotPending());
          }
          return shareRepository.findById(shareId).orElseThrow();
        },
        _ -> Optional.empty());
  }

  private Outcome<ProfileHouseholdShare, ShareRejections.End> endInTransaction(UUID shareId) {
    return endInTransaction(shareId, _ -> {});
  }

  private Outcome<ProfileHouseholdShare, ShareRejections.End> endInTransaction(
      UUID shareId, Consumer<ProfileHouseholdShare> afterEnd) {
    return mutationTransactions.write(
        () -> {
          var now = clock.instant();
          if (!shareRepository.tryEnd(shareId, now)) {
            throw new MutationRejection(new ShareRejections.ShareNotActive());
          }
          var share = shareRepository.findById(shareId).orElseThrow();
          // Unsharing returns affected sessions to the picker; a visitor whose Personal
          // Profile's share ended also loses the Household context itself.
          authSessionRepository.clearSelections(share.getProfileId(), share.getHouseholdId(), now);
          userAccountRepository
              .findByPersonalProfileId(share.getProfileId())
              .ifPresent(
                  visitor ->
                      authSessionRepository.resetContextForAccount(
                          visitor.getId(), share.getHouseholdId(), now));
          afterEnd.accept(share);
          return share;
        },
        constraint ->
            CHK_STRUCTURAL.equals(constraint)
                ? Optional.of(new ShareRejections.StructuralShareCannotEnd())
                : Optional.empty());
  }

  private Optional<Object> refusalOf(
      AuthenticatedIdentity identity,
      Intent<?> intent,
      BooleanSupplier mayView,
      Supplier<Object> denied,
      Supplier<Object> reauthenticationRequired) {
    return switch (authorizationService.decide(identity, intent)) {
      case Decision.Allowed<?> _ -> Optional.empty();
      case Decision.Failed<?> _ -> throw new AuthorizationUnavailableException();
      case Decision.Denied<?>(var reason) ->
          switch (reason) {
            case REAUTHENTICATION_REQUIRED -> Optional.of(reauthenticationRequired.get());
            case POLICY -> {
              if (mayView.getAsBoolean()) {
                throw new AccessDeniedException("Not allowed.");
              }
              yield Optional.of(denied.get());
            }
          };
    };
  }

  private Optional<ShareRejections.Decide> decideRefusal(
      AuthenticatedIdentity identity, Intent<?> intent, UUID shareId) {
    return refusalOf(
            identity,
            intent,
            () -> mayViewShare(identity, shareId),
            ShareRejections.ShareNotFound::new,
            null)
        .map(ShareRejections.Decide.class::cast);
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
    return authorizationService.decide(identity, new Intent.ViewProfileAdministration(profileId))
        instanceof Decision.Allowed<?>;
  }

  private boolean mayViewHousehold(AuthenticatedIdentity identity, UUID householdId) {
    return authorizationService.decide(
            identity, new Intent.ViewHouseholdAdministration(householdId))
        instanceof Decision.Allowed<?>;
  }

  /** Pending offers into one Household, for its admins; empty when the caller may not view. */
  public List<ProfileHouseholdShare> pendingShareOffers(
      AuthenticatedIdentity identity, UUID householdId) {
    if (!mayViewHousehold(identity, householdId)) {
      return List.of();
    }
    return shareRepository
        .findByHouseholdIdAndStatus(householdId, ProfileShareStatus.PENDING)
        .stream()
        .sorted(Comparator.comparing(ProfileHouseholdShare::getId))
        .toList();
  }

  /** Every share of one Profile, for its managers; empty when the caller may not view. */
  public List<ProfileHouseholdShare> profileShares(AuthenticatedIdentity identity, UUID profileId) {
    if (!mayViewProfile(identity, profileId)) {
      return List.of();
    }
    return shareRepository.findByProfileId(profileId).stream()
        .sorted(Comparator.comparing(ProfileHouseholdShare::getId))
        .toList();
  }

  /** The offerer's whole preflight: nothing else about the target Household leaks. */
  @Builder
  public record SharePreflight(boolean wouldLock, boolean nameConflict) {}
}
