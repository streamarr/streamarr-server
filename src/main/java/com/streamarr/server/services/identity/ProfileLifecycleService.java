package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Unlinked-Profile transfers and force-deletion (ADR 0024 §Transfers, §Permanent Profile deletion):
 * ServerAdmin recovery work. A transfer establishes the destination anchor before commit and never
 * silently reactivates anything — pending Profile-bound artifacts are invalidated, visits elsewhere
 * stay untouched. A linked Profile moves or dies only with its Account.
 */
@Service
@RequiredArgsConstructor
public class ProfileLifecycleService {

  private static final String CHK_HOME_ANCHOR = "chk_profile_home_anchor";
  private static final String CHK_NAMES_UNIQUE = "chk_household_profile_names_unique";
  private static final String CHK_HOSTING_ADMIN = "chk_hosting_household_retains_eligible_admin";
  private static final String PROFILE_TRANSFERRED = "Profile transferred";
  private static final String PROFILE_DELETED = "Profile deleted";

  private final AuthorizationService authorizationService;
  private final ProfileRepository profileRepository;
  private final UserAccountRepository userAccountRepository;
  private final HouseholdRepository householdRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileManagerInvitationRepository managerInvitationRepository;
  private final AccountInvitationRepository accountInvitationRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final AuthSessionRepository authSessionRepository;
  private final SecurityAuditEventRepository securityAuditEventRepository;
  private final MutationTransactions mutationTransactions;
  private final Clock clock;

  public Outcome<Profile, TransferRejections.TransferProfile> transferProfile(
      AuthenticatedIdentity identity, TransferProfileCommand command) {
    var refusal =
        refusalOf(
            identity,
            new Intent.TransferProfile(command.profileId()),
            () -> mayViewProfile(identity, command.profileId()),
            TransferRejections.ProfileNotFound::new,
            null);
    if (refusal.isPresent()) {
      return Outcome.rejected((TransferRejections.TransferProfile) refusal.get());
    }
    var profile = profileRepository.findById(command.profileId());
    if (profile.isEmpty()) {
      return Outcome.rejected(new TransferRejections.ProfileNotFound());
    }
    if (householdRepository.findById(command.destinationHouseholdId()).isEmpty()) {
      return Outcome.rejected(new TransferRejections.HouseholdNotFound());
    }
    if (command.destinationHouseholdId().equals(profile.get().getHouseholdId())) {
      return Outcome.rejected(new TransferRejections.SameHousehold());
    }
    if (userAccountRepository.findByPersonalProfileId(command.profileId()).isPresent()) {
      // A linked Profile transfers only with its Account.
      return Outcome.rejected(new TransferRejections.ProfileLinked());
    }
    var managerRefusal = localManagerRefusal(command);
    if (managerRefusal.isPresent()) {
      return Outcome.rejected(managerRefusal.get());
    }
    var sourceHouseholdId = profile.get().getHouseholdId();
    var now = clock.instant();
    return mutationTransactions.write(
        () -> {
          // The destination anchor exists before commit; T6 judges the whole move.
          profileManagerRepository.tryGrant(command.localManagerAccountId(), command.profileId());
          if (!profileRepository.tryRehome(
              command.profileId(), sourceHouseholdId, command.destinationHouseholdId())) {
            throw new MutationRejection(new TransferRejections.ProfileNotFound());
          }
          endHomeAvailability(command.profileId(), sourceHouseholdId, now);
          invalidateProfileBoundArtifacts(command.profileId(), PROFILE_TRANSFERRED, now);
          makeAvailableAtHome(command.profileId(), command.destinationHouseholdId(), now);
          audit(identity, "transferProfile", command.profileId(), command.reason());
          return profileRepository.findRefreshedById(command.profileId()).orElseThrow();
        },
        constraint ->
            switch (constraint) {
              case CHK_NAMES_UNIQUE -> Optional.of(new TransferRejections.NameConflict());
              case CHK_HOSTING_ADMIN -> Optional.of(new TransferRejections.NoEligibleAdmin());
              case CHK_HOME_ANCHOR ->
                  Optional.of(new TransferRejections.ReplacementManagerNotEligible());
              default -> Optional.empty();
            });
  }

  public Outcome<UUID, TransferRejections.ForceDeleteProfile> forceDeleteProfile(
      AuthenticatedIdentity identity, UUID profileId, String reason) {
    if (isBlank(reason)) {
      return Outcome.rejected(new TransferRejections.ReasonRequired());
    }
    var refusal =
        refusalOf(
            identity,
            new Intent.ForceDeleteProfile(profileId),
            () -> mayViewProfile(identity, profileId),
            TransferRejections.ProfileNotFound::new,
            TransferRejections.ReauthenticationRequired::new);
    if (refusal.isPresent()) {
      return Outcome.rejected((TransferRejections.ForceDeleteProfile) refusal.get());
    }
    if (userAccountRepository.findByPersonalProfileId(profileId).isPresent()) {
      // A linked Profile is deleted only with its Account.
      return Outcome.rejected(new TransferRejections.ProfileLinked());
    }
    return mutationTransactions.write(
        () -> {
          if (profileRepository.findById(profileId).isEmpty()) {
            throw new MutationRejection(new TransferRejections.ProfileNotFound());
          }
          var now = clock.instant();
          shareRepository
              .findByProfileIdAndStatus(profileId, ProfileShareStatus.ACTIVE)
              .forEach(
                  share ->
                      authSessionRepository.clearSelections(
                          profileId, share.getHouseholdId(), now));
          invalidateProfileBoundArtifacts(profileId, PROFILE_DELETED, now);
          if (!profileRepository.tryDeleteUnlinked(profileId)) {
            if (userAccountRepository.findByPersonalProfileId(profileId).isPresent()) {
              throw new MutationRejection(new TransferRejections.ProfileLinked());
            }
            throw new MutationRejection(new TransferRejections.ProfileNotFound());
          }
          audit(identity, "forceDeleteProfile", profileId, reason);
          return profileId;
        },
        _ -> Optional.empty());
  }

  private void endHomeAvailability(UUID profileId, UUID sourceHouseholdId, Instant now) {
    shareRepository
        .findByProfileIdAndHouseholdIdAndStatus(
            profileId, sourceHouseholdId, ProfileShareStatus.ACTIVE)
        .ifPresent(share -> shareRepository.tryEnd(share.getId(), now));
    authSessionRepository.clearSelections(profileId, sourceHouseholdId, now);
  }

  /** Pending offers were invalidated just above, so the availability insert cannot collide. */
  private void makeAvailableAtHome(UUID profileId, UUID destinationHouseholdId, Instant now) {
    var alreadyAvailable =
        shareRepository
            .findByProfileIdAndHouseholdIdAndStatus(
                profileId, destinationHouseholdId, ProfileShareStatus.ACTIVE)
            .isPresent();
    if (alreadyAvailable) {
      return;
    }
    shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(profileId)
            .householdId(destinationHouseholdId)
            .status(ProfileShareStatus.ACTIVE)
            .decidedAt(now)
            .build());
  }

  private void invalidateProfileBoundArtifacts(UUID profileId, String reason, Instant now) {
    accountInvitationRepository.invalidatePendingForProfile(profileId, reason, now);
    managerInvitationRepository.invalidatePendingForProfile(profileId, reason, now);
    shareRepository.invalidatePendingSharesForProfile(profileId, reason, now);
  }

  private Optional<TransferRejections.TransferProfile> localManagerRefusal(
      TransferProfileCommand command) {
    if (command.localManagerAccountId() == null) {
      return Optional.of(new TransferRejections.LocalManagerRequired());
    }
    var manager = userAccountRepository.findById(command.localManagerAccountId());
    if (manager.isEmpty()) {
      return Optional.of(new TransferRejections.LocalManagerNotFound());
    }
    // T6: the anchor lives in the destination Household and is themselves unrestricted.
    var anchored =
        manager
            .filter(anchor -> anchor.getHouseholdId().equals(command.destinationHouseholdId()))
            .filter(this::isEligible)
            .isPresent();
    if (!anchored) {
      return Optional.of(new TransferRejections.ReplacementManagerNotEligible());
    }
    return Optional.empty();
  }

  private boolean isEligible(UserAccount account) {
    return profileRepository
        .findById(account.getPersonalProfileId())
        .filter(profile -> !profile.isRestricted())
        .isPresent();
  }

  private boolean mayViewProfile(AuthenticatedIdentity identity, UUID profileId) {
    return authorizationService.decide(identity, new Intent.ViewProfileAdministration(profileId))
        instanceof Decision.Allowed<?>;
  }

  private void audit(
      AuthenticatedIdentity identity, String operation, UUID profileId, String reason) {
    securityAuditEventRepository.append(
        SecurityAuditEntry.builder()
            .operation(operation)
            .actorAccountId(identity.accountId())
            .reason(reason)
            .resource("profileId", profileId)
            .build());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
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

  @Builder
  public record TransferProfileCommand(
      UUID profileId, UUID destinationHouseholdId, UUID localManagerAccountId, String reason) {}
}
