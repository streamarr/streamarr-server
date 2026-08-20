package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfilePolicyTarget;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.ProfilePinHasher;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.authorization.ProfilePolicyTransition;
import com.streamarr.server.services.authorization.ProfileSafetyRule;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Profile administration (ADR 0024 §ProfileManager, §Profile creation, §PIN safety, §Permanent
 * Profile deletion). Kind and ceiling changes are decided inside the write transaction — the
 * authorization module locks the row and classifies the exact transition, and the mutation writes
 * exactly the normalized target it was handed. PIN hashing runs outside every transaction.
 */
@Service
@RequiredArgsConstructor
public class ProfileAdministrationService {

  private static final String CHK_NAMES_UNIQUE = "chk_household_profile_names_unique";
  private static final String CHK_HOSTING_ADMIN = "chk_hosting_household_retains_eligible_admin";
  private static final String CHK_ELIGIBLE_MANAGER = "chk_profile_home_anchor";
  private static final String CHK_RESTRICTED_AUTHORITY =
      "chk_restricted_account_holds_no_authority";

  private final AuthorizationService authorizationService;
  private final ProfileRepository profileRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final HouseholdRepository householdRepository;
  private final UserAccountRepository userAccountRepository;
  private final AccountInvitationRepository accountInvitationRepository;
  private final ProfileManagerInvitationRepository profileManagerInvitationRepository;
  private final SecurityAuditEventRepository securityAuditEventRepository;
  private final ProfilePinHasher profilePinHasher;
  private final MutationTransactions mutationTransactions;
  private final Clock clock;

  public Outcome<Profile, ProfileRejections.CreateProfile> createProfile(
      AuthenticatedIdentity identity, CreateProfileCommand command) {
    var inputRejection = creationInputRejection(command);
    if (inputRejection.isPresent()) {
      return Outcome.rejected(inputRejection.get());
    }

    var intent = creationIntentFor(command);
    return mutationTransactions.write(
        () -> createProfileInsideTransaction(identity, command, intent),
        this::creationConstraintRejection);
  }

  private Intent.UnitIntent creationIntentFor(CreateProfileCommand command) {
    return command.localManagerAccountId() == null
        ? new Intent.CreateProfile(command.householdId())
        : new Intent.CreateProfileWithLocalManager(command.householdId());
  }

  private Profile createProfileInsideTransaction(
      AuthenticatedIdentity identity, CreateProfileCommand command, Intent.UnitIntent intent) {
    enforceProfileCreationAuthorization(identity, command, intent);
    requireHouseholdExists(command.householdId());
    requireEligibleLocalManager(command);

    var profile = persistProfile(command);
    grantProfileManagement(identity, command, profile);
    shareProfileWithHousehold(command, profile);
    return profile;
  }

  private Optional<ProfileRejections.CreateProfile> creationInputRejection(
      CreateProfileCommand command) {
    if (isBlank(command.name())) {
      return Optional.of(new ProfileRejections.ProfileNameRequired());
    }

    if (isNegative(command.maximumAllowedRatingAge())) {
      return Optional.of(new ProfileRejections.MaximumAllowedRatingAgeInvalid());
    }

    return Optional.empty();
  }

  private void enforceProfileCreationAuthorization(
      AuthenticatedIdentity identity, CreateProfileCommand command, Intent.UnitIntent intent) {
    var refusal =
        refusalOf(
            identity,
            intent,
            () -> mayViewHousehold(identity, command.householdId()),
            ProfileRejections.HouseholdNotFound::new,
            Optional.empty());
    if (refusal.isPresent()) {
      throw new MutationRejection(refusal.get());
    }
  }

  private void requireHouseholdExists(UUID householdId) {
    if (householdRepository.findById(householdId).isEmpty()) {
      throw new MutationRejection(new ProfileRejections.HouseholdNotFound());
    }
  }

  private void requireEligibleLocalManager(CreateProfileCommand command) {
    if (command.localManagerAccountId() == null) {
      return;
    }

    var localManager =
        userAccountRepository
            .findById(command.localManagerAccountId())
            .orElseThrow(
                () -> new MutationRejection(new ProfileRejections.ProfileManagerNotEligible()));
    if (!isEligibleLocalManager(localManager, command.householdId())) {
      throw new MutationRejection(new ProfileRejections.ManagerNotEligible());
    }
  }

  private Profile persistProfile(CreateProfileCommand command) {
    return profileRepository.saveAndFlush(
        Profile.builder()
            .householdId(command.householdId())
            .name(command.name().strip())
            .kind(command.kind() == null ? ProfileKind.ADULT : command.kind())
            .maximumAllowedRatingAge(command.maximumAllowedRatingAge())
            .build());
  }

  private void grantProfileManagement(
      AuthenticatedIdentity identity, CreateProfileCommand command, Profile profile) {
    profileManagerRepository.saveAndFlush(
        ProfileManager.builder()
            .accountId(identity.accountId())
            .profileId(profile.getId())
            .build());
    if (command.localManagerAccountId() == null
        || command.localManagerAccountId().equals(identity.accountId())) {
      return;
    }

    profileManagerRepository.saveAndFlush(
        ProfileManager.builder()
            .accountId(command.localManagerAccountId())
            .profileId(profile.getId())
            .build());
  }

  private void shareProfileWithHousehold(CreateProfileCommand command, Profile profile) {
    shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(command.householdId())
            .status(ProfileShareStatus.ACTIVE)
            .build());
  }

  private Optional<ProfileRejections.CreateProfile> creationConstraintRejection(String constraint) {
    return switch (constraint) {
      case CHK_NAMES_UNIQUE -> Optional.of(new ProfileRejections.ProfileNameTaken());
      case CHK_ELIGIBLE_MANAGER -> Optional.of(new ProfileRejections.EligibleManagerRequired());
      case CHK_RESTRICTED_AUTHORITY -> Optional.of(new ProfileRejections.ManagerNotEligible());
      default -> Optional.empty();
    };
  }

  public Outcome<Profile, ProfileRejections.RenameProfile> renameProfile(
      AuthenticatedIdentity identity, UUID profileId, String name) {
    if (isBlank(name)) {
      return Outcome.rejected(new ProfileRejections.ProfileNameRequired());
    }

    return mutationTransactions.write(
        () -> {
          if (!profileRepository.lockById(profileId)) {
            throw new MutationRejection(new ProfileRejections.ProfileNotFound());
          }

          var refusal = editRefusal(identity, new Intent.RenameProfile(profileId), profileId);
          if (refusal.isPresent()) {
            throw new MutationRejection(refusal.get());
          }

          if (!profileRepository.tryRename(profileId, name.strip())) {
            throw new MutationRejection(new ProfileRejections.ProfileNotFound());
          }

          return profileRepository.findRefreshedById(profileId).orElseThrow();
        },
        constraint ->
            CHK_NAMES_UNIQUE.equals(constraint)
                ? Optional.of(new ProfileRejections.ProfileNameTaken())
                : Optional.empty());
  }

  public Outcome<Profile, ProfileRejections.SetProfilePicture> setProfilePicture(
      AuthenticatedIdentity identity, UUID profileId, String picture) {
    return mutationTransactions.write(
        () -> {
          if (!profileRepository.lockById(profileId)) {
            throw new MutationRejection(new ProfileRejections.ProfileNotFound());
          }

          var refusal = editRefusal(identity, new Intent.SetProfilePicture(profileId), profileId);
          if (refusal.isPresent()) {
            throw new MutationRejection(refusal.get());
          }

          if (!profileRepository.trySetPicture(profileId, picture)) {
            throw new MutationRejection(new ProfileRejections.ProfileNotFound());
          }

          return profileRepository.findRefreshedById(profileId).orElseThrow();
        },
        _ -> Optional.empty());
  }

  public Outcome<Profile, ProfileRejections.ChangeProfilePolicy> changeProfileKind(
      AuthenticatedIdentity identity, UUID profileId, ProfileKind kind) {
    return applyPolicyChange(identity, new Intent.ChangeProfileKind(profileId, kind), profileId);
  }

  public Outcome<Profile, ProfileRejections.ChangeProfilePolicy> setProfileContentCeiling(
      AuthenticatedIdentity identity, UUID profileId, int maximumAllowedRatingAge) {
    if (maximumAllowedRatingAge < 0) {
      return Outcome.rejected(new ProfileRejections.MaximumAllowedRatingAgeInvalid());
    }

    return applyPolicyChange(
        identity,
        new Intent.SetProfileContentCeiling(profileId, maximumAllowedRatingAge),
        profileId);
  }

  public Outcome<Profile, ProfileRejections.ChangeProfilePolicy> clearProfileContentCeiling(
      AuthenticatedIdentity identity, UUID profileId) {
    return applyPolicyChange(identity, new Intent.ClearProfileContentCeiling(profileId), profileId);
  }

  public Outcome<Profile, ProfileRejections.SetProfilePin> setProfilePin(
      AuthenticatedIdentity identity, UUID profileId, String pin) {
    if (profileRepository.findById(profileId).isEmpty()) {
      return Outcome.rejected(new ProfileRejections.ProfileNotFound());
    }

    var refusal = editRefusal(identity, new Intent.ManageProfilePin(profileId), profileId);
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    if (!profilePinHasher.isWellFormed(pin)) {
      return Outcome.rejected(new ProfileRejections.PinMalformed());
    }

    var pinHash = profilePinHasher.hash(pin);
    return mutationTransactions.write(
        () -> {
          if (!profileRepository.lockById(profileId)) {
            throw new MutationRejection(new ProfileRejections.ProfileNotFound());
          }

          var authoritativeRefusal =
              editRefusal(identity, new Intent.ManageProfilePin(profileId), profileId);
          if (authoritativeRefusal.isPresent()) {
            throw new MutationRejection(authoritativeRefusal.get());
          }

          if (!profileRepository.trySetPinHash(profileId, pinHash)) {
            throw new MutationRejection(new ProfileRejections.ProfileNotFound());
          }

          return profileRepository.findRefreshedById(profileId).orElseThrow();
        },
        _ -> Optional.empty());
  }

  public Outcome<Profile, ProfileRejections.RemoveProfilePin> removeProfilePin(
      AuthenticatedIdentity identity, UUID profileId) {
    return mutationTransactions.write(
        () -> {
          if (!profileRepository.lockById(profileId)) {
            throw new MutationRejection(new ProfileRejections.ProfileNotFound());
          }

          var refusal = editRefusal(identity, new Intent.ManageProfilePin(profileId), profileId);
          if (refusal.isPresent()) {
            throw new MutationRejection(refusal.get());
          }

          var wouldLockIn = wouldLockIn(identity, profileId);
          if (wouldLockIn.isPresent()) {
            throw new MutationRejection(wouldLockIn.get());
          }

          if (!profileRepository.trySetPinHash(profileId, null)) {
            throw new MutationRejection(new ProfileRejections.ProfileNotFound());
          }

          return profileRepository.findRefreshedById(profileId).orElseThrow();
        },
        _ -> Optional.empty());
  }

  public Outcome<Profile, ProfileRejections.AdministrativelyResetProfilePin>
      administrativelyResetProfilePin(
          AuthenticatedIdentity identity, UUID profileId, String pin, String reason) {
    if (isBlank(reason)) {
      return Outcome.rejected(new ProfileRejections.ReasonRequired());
    }

    if (profileRepository.findById(profileId).isEmpty()) {
      return Outcome.rejected(new ProfileRejections.ProfileNotFound());
    }

    var intent = new Intent.AdministrativelyResetProfilePin(profileId);
    Optional<ProfileRejections.AdministrativelyResetProfilePin> refusal =
        refusalOf(
            identity,
            intent,
            () -> mayViewProfile(identity, profileId),
            ProfileRejections.ProfileNotFound::new,
            Optional.of(ProfileRejections.ReauthenticationRequired::new));
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    if (!profilePinHasher.isWellFormed(pin)) {
      return Outcome.rejected(new ProfileRejections.PinMalformed());
    }

    var pinHash = profilePinHasher.hash(pin);
    return mutationTransactions.write(
        () -> {
          if (!profileRepository.lockById(profileId)) {
            throw new MutationRejection(new ProfileRejections.ProfileNotFound());
          }

          Optional<ProfileRejections.AdministrativelyResetProfilePin> authoritativeRefusal =
              refusalOf(
                  identity,
                  intent,
                  () -> mayViewProfile(identity, profileId),
                  ProfileRejections.ProfileNotFound::new,
                  Optional.of(ProfileRejections.ReauthenticationRequired::new));
          if (authoritativeRefusal.isPresent()) {
            throw new MutationRejection(authoritativeRefusal.get());
          }

          if (!profileRepository.trySetPinHash(profileId, pinHash)) {
            throw new MutationRejection(new ProfileRejections.ProfileNotFound());
          }

          securityAuditEventRepository.append(
              SecurityAuditEntry.builder()
                  .operation("administrativelyResetProfilePin")
                  .actorAccountId(identity.accountId())
                  .reason(reason)
                  .resource("profileId", profileId)
                  .build());
          return profileRepository.findRefreshedById(profileId).orElseThrow();
        },
        _ -> Optional.empty());
  }

  public Outcome<UUID, ProfileRejections.DeleteProfile> deleteProfile(
      AuthenticatedIdentity identity, UUID profileId) {
    return mutationTransactions.write(
        () -> {
          if (!profileRepository.lockById(profileId)) {
            throw new MutationRejection(new ProfileRejections.ProfileNotFound());
          }

          var refusal =
              refusalOf(
                  identity,
                  new Intent.DeleteProfile(profileId),
                  // Deletion denial always uses the typed oracle below, never FORBIDDEN.
                  () -> false,
                  () -> deletionRejection(identity, profileId),
                  Optional.of(ProfileRejections.ReauthenticationRequired::new));
          if (refusal.isPresent()) {
            throw new MutationRejection(refusal.get());
          }

          accountInvitationRepository.invalidatePendingByProfileId(
              profileId, "Profile deleted", clock.instant());
          profileManagerInvitationRepository.invalidatePendingForProfile(
              profileId, "Profile deleted", clock.instant());
          profileRepository.deleteById(profileId);
          profileRepository.flush();
          securityAuditEventRepository.append(
              SecurityAuditEntry.builder()
                  .operation("deleteProfile")
                  .actorAccountId(identity.accountId())
                  .resource("profileId", profileId)
                  .build());
          return profileId;
        },
        _ -> Optional.empty());
  }

  private Outcome<Profile, ProfileRejections.ChangeProfilePolicy> applyPolicyChange(
      AuthenticatedIdentity identity, Intent.ProfilePolicyChange intent, UUID profileId) {
    return mutationTransactions.write(
        () -> {
          if (!profileRepository.lockById(profileId)) {
            throw new MutationRejection(new ProfileRejections.ProfileNotFound());
          }

          // Decided inside the transaction: the planner locks the row, so the classified state
          // holds until commit and the write below applies exactly the normalized target.
          var transition = decideTransition(identity, intent, profileId);
          if (!profileRepository.tryApplyPolicy(
              profileId,
              new ProfilePolicyTarget(transition.targetKind(), transition.targetCeiling()))) {
            throw new MutationRejection(new ProfileRejections.ProfileNotFound());
          }

          invalidateRecipientProposalsWhenRestricted(profileId, transition);

          // The decision above JPA-loaded this row in this transaction; re-read past the
          // first-level cache or the payload would show the pre-transition state.
          return profileRepository.findRefreshedById(profileId).orElseThrow();
        },
        constraint ->
            switch (constraint) {
              case CHK_ELIGIBLE_MANAGER ->
                  Optional.of(new ProfileRejections.EligibleManagerRequired());
              case CHK_RESTRICTED_AUTHORITY ->
                  Optional.of(new ProfileRejections.RestrictedAccountAuthority());
              case CHK_HOSTING_ADMIN ->
                  Optional.of(new ProfileRejections.HostingHouseholdLacksEligibleAdmin());
              default -> Optional.empty();
            });
  }

  /**
   * Restricting a linked Personal Profile ends the person's eligibility (ADR 0024): a pending
   * manager invitation naming them must not outlive it and later restore authority T5 forbids.
   */
  private void invalidateRecipientProposalsWhenRestricted(
      UUID profileId, ProfilePolicyTransition transition) {
    var restricted =
        transition.targetKind() == ProfileKind.KID || transition.targetCeiling() != null;
    if (!restricted) {
      return;
    }

    userAccountRepository
        .findByPersonalProfileId(profileId)
        .ifPresent(
            linked ->
                profileManagerInvitationRepository.invalidatePendingForRecipient(
                    linked.getId(), "recipient became ineligible", clock.instant()));
  }

  private ProfilePolicyTransition decideTransition(
      AuthenticatedIdentity identity, Intent.ProfilePolicyChange intent, UUID profileId) {
    return switch (authorizationService.decide(identity, intent)) {
      case Decision.Allowed<ProfilePolicyTransition>(var transition) -> transition;
      case Decision.Failed<ProfilePolicyTransition> _ ->
          throw new AuthorizationUnavailableException();
      case Decision.Denied<ProfilePolicyTransition>(var reason) ->
          throw switch (reason) {
            case REAUTHENTICATION_REQUIRED ->
                new MutationRejection(new ProfileRejections.ReauthenticationRequired());
            case POLICY -> {
              if (mayViewProfile(identity, profileId)) {
                yield new AccessDeniedException("Not allowed.");
              }

              yield new MutationRejection(new ProfileRejections.ProfileNotFound());
            }
          };
    };
  }

  /** The shared refusal shape for ordinary edits: FORBIDDEN when visible, not-found when not. */
  private Optional<ProfileRejections.ProfileNotFound> editRefusal(
      AuthenticatedIdentity identity, Intent.UnitIntent intent, UUID profileId) {
    return refusalOf(
        identity,
        intent,
        () -> mayViewProfile(identity, profileId),
        ProfileRejections.ProfileNotFound::new,
        Optional.empty());
  }

  private <R> Optional<R> refusalOf(
      AuthenticatedIdentity identity,
      Intent.UnitIntent intent,
      BooleanSupplier mayView,
      Supplier<? extends R> denied,
      Optional<? extends Supplier<? extends R>> reauthenticationRequired) {
    return switch (authorizationService.decide(identity, intent)) {
      case Decision.Allowed<AuthorizationUnit> _ -> Optional.empty();
      case Decision.Failed<AuthorizationUnit> _ -> throw new AuthorizationUnavailableException();
      case Decision.Denied<AuthorizationUnit>(var reason) ->
          switch (reason) {
            case REAUTHENTICATION_REQUIRED ->
                Optional.of(
                    reauthenticationRequired
                        .orElseThrow(AuthorizationUnavailableException::new)
                        .get());
            case POLICY -> {
              if (mayView.getAsBoolean()) {
                throw new AccessDeniedException("Not allowed.");
              }

              yield Optional.of(denied.get());
            }
          };
    };
  }

  /**
   * Refuses removal when it would lock the Profile in any Household where it is available. The
   * Household is named only for a caller who may view its administration.
   */
  private Optional<ProfileRejections.WouldLockProfile> wouldLockIn(
      AuthenticatedIdentity identity, UUID profileId) {
    var activeShares =
        shareRepository.findByProfileIdAndStatus(profileId, ProfileShareStatus.ACTIVE);
    for (var share : activeShares) {
      var householdId = share.getHouseholdId();
      var available = profileRepository.findAvailableInHousehold(householdId);
      var requiring = ProfileSafetyRule.profilesRequiringPin(available);
      if (requiring.contains(profileId)) {
        var name =
            mayViewHousehold(identity, householdId)
                ? householdRepository.findById(householdId).map(h -> h.getName())
                : Optional.<String>empty();
        return Optional.of(new ProfileRejections.WouldLockProfile(householdId, name));
      }
    }

    return Optional.empty();
  }

  private ProfileRejections.DeleteProfile deletionRejection(
      AuthenticatedIdentity identity, UUID profileId) {
    // The oracle rule: a caller who may see the Profile's administration learns why deletion is
    // refused; anyone else learns nothing beyond not-found.
    return mayViewProfile(identity, profileId)
        ? new ProfileRejections.ProfileNotDeletable()
        : new ProfileRejections.ProfileNotFound();
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

  private boolean isEligibleLocalManager(UserAccount account, UUID householdId) {
    if (!account.getHouseholdId().equals(householdId)
        || account.getHouseholdRole() != HouseholdRole.ADMIN) {
      return false;
    }

    return profileRepository
        .findById(account.getPersonalProfileId())
        .filter(profile -> profile.getKind() == ProfileKind.ADULT)
        .filter(profile -> !profile.isRestricted())
        .isPresent();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static boolean isNegative(Integer value) {
    return value != null && value < 0;
  }

  /** What createProfile needs; the builder keeps call sites named (no positional soup). */
  @Builder
  public record CreateProfileCommand(
      UUID householdId,
      String name,
      ProfileKind kind,
      Integer maximumAllowedRatingAge,
      UUID localManagerAccountId) {}
}
