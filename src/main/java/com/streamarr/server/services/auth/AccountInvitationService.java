package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationMode;
import com.streamarr.server.domain.auth.AccountInvitationReoffer;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvitationEmailAlreadyUsedException;
import com.streamarr.server.exceptions.InvitationNotAcceptableException;
import com.streamarr.server.repositories.auth.AccountInvitationReofferRepository;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Presents and consumes principal-less Account invitation codes.
 *
 * @see <a
 *     href="https://github.com/streamarr/streamarr-adr/blob/main/adr/0024-identity-authority-by-relationship.adoc#_invitations">ADR
 *     0024 §Invitations</a>
 */
@Service
@RequiredArgsConstructor
public class AccountInvitationService {

  private static final String EMAIL_UNIQUE_INDEX = "uq_user_account_email";
  private static final String CHK_NAMES_UNIQUE = "chk_household_profile_names_unique";
  private static final String CHK_ELIGIBLE_MANAGER = "chk_profile_home_anchor";
  private static final String CHK_RESTRICTED_AUTHORITY =
      "chk_restricted_account_holds_no_authority";
  private static final String PROFILE_CONNECTED_REASON = "Profile linked to an Account";

  private final AccountInvitationRepository invitationRepository;
  private final UserAccountRepository userAccountRepository;
  private final ProfileRepository profileRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileManagerInvitationRepository profileManagerInvitationRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final AccountInvitationReofferRepository reofferRepository;
  private final HouseholdRepository householdRepository;
  private final AuthSessionRepository authSessionRepository;
  private final RefreshTokenService refreshTokenService;
  private final OpaqueCodeResolver codeResolver;
  private final PasswordEncoder passwordEncoder;
  private final TransactionTemplate transactionTemplate;
  private final ConstraintViolationTranslator constraintViolationTranslator;
  private final CredentialCodeProperties properties;
  private final Clock clock;

  /** What the code holder needs to decide; never the secret, never other Households' data. */
  public InvitationPreview lookup(String rawCode) {
    var invitation = resolvePending(rawCode);
    return InvitationPreview.builder()
        .recipientEmail(invitation.getRecipientEmail())
        .householdName(invitation.getHouseholdName())
        .householdRole(invitation.getHouseholdRole())
        .mode(invitation.getMode())
        .profileName(invitation.getProfileName())
        .profileKind(invitation.getProfileKind())
        .maximumAllowedRatingAge(invitation.getMaximumAllowedRatingAge())
        .expiresAt(invitation.getExpiresAt())
        .remainingManagers(remainingManagers(invitation))
        .householdsLosingProfileAccess(householdsLosingProfileAccess(invitation))
        .profileShareOfferTargets(profileShareOfferTargets(invitation))
        .build();
  }

  /** The direct managers the recipient keeps after linking (ADR 0024 §Profile creation). */
  private List<String> remainingManagers(AccountInvitation invitation) {
    if (invitation.getMode() != AccountInvitationMode.LINK) {
      return List.of();
    }

    return profileManagerRepository.findByProfileId(invitation.getProfileId()).stream()
        .map(manager -> userAccountRepository.findById(manager.getAccountId()))
        .flatMap(Optional::stream)
        .map(UserAccount::getDisplayName)
        .sorted()
        .toList();
  }

  /** Every current visit ends at acceptance; the same share must never admit the person. */
  private List<String> householdsLosingProfileAccess(AccountInvitation invitation) {
    if (invitation.getMode() != AccountInvitationMode.LINK) {
      return List.of();
    }

    return shareRepository
        .findByProfileIdAndStatus(invitation.getProfileId(), ProfileShareStatus.ACTIVE)
        .stream()
        .filter(share -> !share.getHouseholdId().equals(invitation.getHouseholdId()))
        .map(share -> householdRepository.findById(share.getHouseholdId()))
        .flatMap(Optional::stream)
        .map(Household::getName)
        .sorted()
        .toList();
  }

  private List<String> profileShareOfferTargets(AccountInvitation invitation) {
    if (invitation.getMode() != AccountInvitationMode.LINK) {
      return List.of();
    }

    return profileShareOfferTargetsAtDecision(invitation).stream()
        .map(AccountInvitationReoffer::getHouseholdName)
        .sorted()
        .toList();
  }

  public AcceptedInvitation accept(AcceptInvitationCommand command) {
    var invitation = resolvePending(command.code());
    var passwordHash = passwordEncoder.encode(command.password());

    try {
      return acceptInTransaction(command, invitation, passwordHash);
    } catch (DataIntegrityViolationException exception) {
      throw translateAcceptanceFailure(exception);
    }
  }

  public void decline(String rawCode) {
    var invitation = resolvePending(rawCode);
    if (!invitationRepository.markDeclinedIfPendingAndUnexpired(
        invitation.getId(), clock.instant())) {
      throw OpaqueCodeResolver.rejected(
          OpaqueCodeResolver.MissReason.LOST_RACE, invitation.getPublicId());
    }
  }

  private AcceptedInvitation acceptInTransaction(
      AcceptInvitationCommand command, AccountInvitation invitation, String passwordHash) {
    return transactionTemplate.execute(
        _ -> acceptPendingInvitation(command, invitation, passwordHash));
  }

  private AcceptedInvitation acceptPendingInvitation(
      AcceptInvitationCommand command, AccountInvitation invitation, String passwordHash) {
    invitationRepository.lockInvitationIssuanceForRecipientEmail(invitation.getRecipientEmail());
    lockLocalManager(invitation);
    lockLinkProfile(invitation);
    consumeInvitation(invitation);
    requireTargetHousehold(invitation);
    var account =
        invitation.getMode() == AccountInvitationMode.LINK
            ? linkAccount(command, invitation, passwordHash)
            : createAccount(command, invitation, passwordHash);
    var issued = refreshTokenService.createSession(account, command.deviceName());
    return AcceptedInvitation.builder()
        .account(account)
        .session(issued.session())
        .rawRefreshToken(issued.rawToken())
        .build();
  }

  /**
   * Account before credential, the order issuer disablement takes (its Account row, then the
   * invitations it issued): the manager row is locked before this transaction touches the
   * invitation, so the two cannot deadlock when the manager is the issuer. Bounded like issuance.
   */
  private void lockLocalManager(AccountInvitation invitation) {
    if (invitation.getLocalManagerAccountId() == null) {
      return;
    }

    userAccountRepository.lockByIds(
        Set.of(invitation.getLocalManagerAccountId()), properties.replacementLockTimeout());
  }

  private void lockLinkProfile(AccountInvitation invitation) {
    if (invitation.getMode() != AccountInvitationMode.LINK) {
      return;
    }

    if (!profileRepository.lockById(invitation.getProfileId())) {
      throw OpaqueCodeResolver.rejected(
          OpaqueCodeResolver.MissReason.NOT_REDEEMABLE, invitation.getPublicId());
    }
  }

  private void consumeInvitation(AccountInvitation invitation) {
    if (!invitationRepository.markAcceptedIfPendingAndUnexpired(
        invitation.getId(), clock.instant())) {
      throw OpaqueCodeResolver.rejected(
          OpaqueCodeResolver.MissReason.LOST_RACE, invitation.getPublicId());
    }
  }

  /**
   * V058 invalidates a PENDING row the moment its Household goes; PENDING without one is corrupt.
   */
  private static void requireTargetHousehold(AccountInvitation invitation) {
    if (invitation.getHouseholdId() == null) {
      throw new IllegalStateException(
          "Invitation %s is PENDING without a target Household".formatted(invitation.getId()));
    }
  }

  /** The Household row still resolves (the invitation is locked), so its guard row must exist. */
  private static IllegalStateException missingHouseholdGuard(UUID householdId) {
    return new IllegalStateException(
        "No household_guard row for Household %s while accepting an invitation"
            .formatted(householdId));
  }

  /**
   * The deferred Household invariants judge the accepted shape at commit; each one the issuance
   * pre-checks can be raced is answered with a typed conflict instead of a bare 500.
   */
  private RuntimeException translateAcceptanceFailure(DataIntegrityViolationException exception) {
    var constraint = constraintViolationTranslator.constraintName(exception).orElse("");
    return switch (constraint) {
      case EMAIL_UNIQUE_INDEX -> new InvitationEmailAlreadyUsedException(exception);
      case CHK_NAMES_UNIQUE ->
          new InvitationNotAcceptableException(
              "The Profile name is no longer available in the Household.", exception);
      case CHK_ELIGIBLE_MANAGER ->
          new InvitationNotAcceptableException(
              "The required Profile manager is no longer eligible.", exception);
      case CHK_RESTRICTED_AUTHORITY ->
          new InvitationNotAcceptableException(
              "A restricted Profile cannot hold Household authority.", exception);
      default -> exception;
    };
  }

  private UserAccount createAccount(
      AcceptInvitationCommand command, AccountInvitation invitation, String passwordHash) {
    var householdId = invitation.getHouseholdId();
    var profile =
        profileRepository.saveAndFlush(
            Profile.builder()
                .householdId(householdId)
                .name(invitation.getProfileName())
                .kind(invitation.getProfileKind())
                .maximumAllowedRatingAge(invitation.getMaximumAllowedRatingAge())
                .build());
    var account =
        userAccountRepository.saveAndFlush(
            UserAccount.builder()
                .email(invitation.getRecipientEmail())
                .displayName(command.displayName())
                .passwordHash(passwordHash)
                .householdId(householdId)
                .householdRole(
                    userAccountRepository
                        .roleForNewAccount(householdId, invitation.getHouseholdRole())
                        .orElseThrow(() -> missingHouseholdGuard(householdId)))
                .personalProfileId(profile.getId())
                .enabled(true)
                .build());
    if (invitation.getLocalManagerAccountId() != null) {
      profileManagerRepository.saveAndFlush(
          ProfileManager.builder()
              .accountId(invitation.getLocalManagerAccountId())
              .profileId(profile.getId())
              .build());
    }

    shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(profile.getId())
            .householdId(householdId)
            .status(ProfileShareStatus.ACTIVE)
            .structural(true)
            .build());
    return account;
  }

  /**
   * Links the invitation's existing Profile as the new Account's Personal Profile (ADR 0024
   * §Profile creation): the home availability becomes the structural share, every current visit
   * ends — a share that admitted a Profile must never silently admit the person — pending offers
   * are invalidated, and each recorded reoffer Household receives a fresh PENDING offer to consent
   * to anew, made exactly once because exactly one acceptance wins the PENDING transition.
   */
  private UserAccount linkAccount(
      AcceptInvitationCommand command, AccountInvitation invitation, String passwordHash) {
    var householdId = invitation.getHouseholdId();
    var profileId = invitation.getProfileId();
    var profile =
        profileRepository
            .findById(profileId)
            .orElseThrow(
                () ->
                    OpaqueCodeResolver.rejected(
                        OpaqueCodeResolver.MissReason.NOT_REDEEMABLE, invitation.getPublicId()));
    if (userAccountRepository.findByPersonalProfileId(profileId).isPresent()
        || !profile.getHouseholdId().equals(householdId)) {
      // The Profile moved on after issuance; a late code fails exactly like an unknown one.
      throw OpaqueCodeResolver.rejected(
          OpaqueCodeResolver.MissReason.NOT_REDEEMABLE, invitation.getPublicId());
    }

    var recordedReoffers = reofferRepository.findByInvitationId(invitation.getId());
    profileRepository.lockProfileTransitionAcrossHouseholds(
        profileId,
        recordedReoffers.stream()
            .map(AccountInvitationReoffer::getHouseholdId)
            .filter(Objects::nonNull)
            .toList());
    var reofferTargets =
        recordedReoffers.stream()
            .filter(recorded -> isProfileShareOfferTarget(recorded, invitation))
            .toList();
    var role =
        userAccountRepository
            .roleForNewAccount(householdId, invitation.getHouseholdRole())
            .orElseThrow(
                () ->
                    OpaqueCodeResolver.rejected(
                        OpaqueCodeResolver.MissReason.NOT_REDEEMABLE, invitation.getPublicId()));
    if (profile.isRestricted() && role == HouseholdRole.ADMIN) {
      // The first Account becomes HouseholdAdmin, and a restricted Account holds no authority.
      throw OpaqueCodeResolver.rejected(
          OpaqueCodeResolver.MissReason.NOT_REDEEMABLE, invitation.getPublicId());
    }

    var account =
        userAccountRepository.saveAndFlush(
            UserAccount.builder()
                .email(invitation.getRecipientEmail())
                .displayName(command.displayName())
                .passwordHash(passwordHash)
                .householdId(householdId)
                .householdRole(profile.isRestricted() ? HouseholdRole.MEMBER : role)
                .personalProfileId(profileId)
                .enabled(true)
                .build());
    var now = clock.instant();
    invitationRepository.invalidatePendingByProfileId(profileId, PROFILE_CONNECTED_REASON, now);
    profileManagerInvitationRepository.invalidatePendingForProfile(
        profileId, PROFILE_CONNECTED_REASON, now);
    shareRepository.ensureActiveMembershipShare(profileId, householdId, now);
    endCurrentVisits(profileId, householdId, now);
    shareRepository.invalidatePendingByProfileId(profileId, PROFILE_CONNECTED_REASON, now);
    var reofferAccountId =
        profile.isRestricted() ? invitation.getIssuerAccountId() : account.getId();
    reoffer(
        ReofferPlan.builder()
            .profileId(profileId)
            .targets(reofferTargets)
            .offererAccountId(reofferAccountId)
            .now(now)
            .build());
    return account;
  }

  private void endCurrentVisits(UUID profileId, UUID homeHouseholdId, Instant now) {
    for (var share :
        shareRepository.findByProfileIdAndStatus(profileId, ProfileShareStatus.ACTIVE)) {
      if (share.getHouseholdId().equals(homeHouseholdId)) {
        continue;
      }

      if (!shareRepository.tryEndActive(share.getId(), now)) {
        throw new IllegalStateException(
            "Active Profile share %s could not be ended during Account linking"
                .formatted(share.getId()));
      }

      authSessionRepository.clearProfileSelectionFromLiveSessions(
          profileId, share.getHouseholdId(), now);
    }
  }

  private void reoffer(ReofferPlan plan) {
    for (var target : plan.targets()) {
      shareRepository.saveAndFlush(
          ProfileHouseholdShare.builder()
              .profileId(plan.profileId())
              .householdId(target.getHouseholdId())
              .status(ProfileShareStatus.PENDING)
              .offeredByAccountId(plan.offererAccountId())
              .expiresAt(plan.now().plus(properties.invitationTtl()))
              .build());
    }
  }

  private List<AccountInvitationReoffer> profileShareOfferTargetsAtDecision(
      AccountInvitation invitation) {
    return reofferRepository.findByInvitationId(invitation.getId()).stream()
        .filter(recorded -> isProfileShareOfferTarget(recorded, invitation))
        .toList();
  }

  private static boolean isProfileShareOfferTarget(
      AccountInvitationReoffer recorded, AccountInvitation invitation) {
    return recorded.getHouseholdId() != null
        && !recorded.getHouseholdId().equals(invitation.getHouseholdId());
  }

  @Builder
  private record ReofferPlan(
      UUID profileId, List<AccountInvitationReoffer> targets, UUID offererAccountId, Instant now) {}

  private AccountInvitation resolvePending(String rawCode) {
    var invitation = codeResolver.resolvePending(rawCode, invitationRepository::findByPublicId);
    if (isLinkInvitationMissingProfile(invitation)) {
      // The linkable Profile was deleted; invalidation should have flipped the row, and the
      // SET NULL is the backstop. A dead code fails exactly like an unknown one.
      throw OpaqueCodeResolver.rejected(
          OpaqueCodeResolver.MissReason.NOT_REDEEMABLE, invitation.getPublicId());
    }

    return invitation;
  }

  private static boolean isLinkInvitationMissingProfile(AccountInvitation invitation) {
    return invitation.getMode() == AccountInvitationMode.LINK && invitation.getProfileId() == null;
  }

  @Builder
  public record AcceptInvitationCommand(
      String code, String displayName, String password, String deviceName) {

    public static class AcceptInvitationCommandBuilder {

      @Override
      public String toString() {
        return "AcceptInvitationCommandBuilder[code=REDACTED, displayName=%s, password=REDACTED,"
                .formatted(displayName)
            + " deviceName=%s]".formatted(deviceName);
      }
    }

    @Override
    public String toString() {
      return "AcceptInvitationCommand[code=REDACTED, displayName=%s, password=REDACTED,"
              .formatted(displayName)
          + " deviceName=%s]".formatted(deviceName);
    }
  }

  @Builder
  public record InvitationPreview(
      String recipientEmail,
      String householdName,
      HouseholdRole householdRole,
      AccountInvitationMode mode,
      String profileName,
      ProfileKind profileKind,
      Integer maximumAllowedRatingAge,
      Instant expiresAt,
      List<String> remainingManagers,
      List<String> householdsLosingProfileAccess,
      List<String> profileShareOfferTargets) {}

  @Builder
  public record AcceptedInvitation(
      UserAccount account, AuthSession session, String rawRefreshToken) {

    public static class AcceptedInvitationBuilder {

      @Override
      public String toString() {
        return "AcceptedInvitationBuilder[rawRefreshToken=REDACTED]";
      }
    }

    @Override
    public String toString() {
      return "AcceptedInvitation[account=%s, session=%s, rawRefreshToken=REDACTED]"
          .formatted(account.getId(), session.getId());
    }
  }
}
