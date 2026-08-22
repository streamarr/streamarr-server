package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationMode;
import com.streamarr.server.domain.auth.AccountInvitationReoffer;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.exceptions.InvitationEmailAlreadyUsedException;
import com.streamarr.server.repositories.auth.AccountInvitationReofferRepository;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The principal-less invitation ceremonies (ADR 0024 §Invitations): the recipient has no Account
 * yet, so lookup, accept, and decline authenticate by code alone — throttled per publicId, one
 * deliberate failure answer, expiry decided by predicate at presentation. Acceptance atomically
 * consumes the PENDING invitation and creates the Account, its Personal Profile, the structural
 * share, any required manager rows, and the first session; the deferred invariants judge the whole
 * shape at commit. Password hashing runs before the transaction opens.
 */
@Service
@RequiredArgsConstructor
public class AccountInvitationCeremonyService {

  private static final String EMAIL_UNIQUE_INDEX = "uq_user_account_email";

  private final AccountInvitationRepository invitationRepository;
  private final UserAccountRepository userAccountRepository;
  private final ProfileRepository profileRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final AccountInvitationReofferRepository reofferRepository;
  private final HouseholdRepository householdRepository;
  private final AuthSessionRepository authSessionRepository;
  private final RefreshTokenService refreshTokenService;
  private final OpaqueCodes opaqueCodes;
  private final CredentialGuessThrottle throttle;
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
        .endingHouseholds(endingHouseholds(invitation))
        .reofferHouseholds(reofferHouseholds(invitation))
        .build();
  }

  /** The direct managers the recipient keeps after connecting (ADR 0024 §Profile creation). */
  private List<String> remainingManagers(AccountInvitation invitation) {
    if (invitation.getMode() != AccountInvitationMode.CONNECT) {
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
  private List<String> endingHouseholds(AccountInvitation invitation) {
    if (invitation.getMode() != AccountInvitationMode.CONNECT) {
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

  private List<String> reofferHouseholds(AccountInvitation invitation) {
    if (invitation.getMode() != AccountInvitationMode.CONNECT) {
      return List.of();
    }

    return reofferRepository.findByInvitationId(invitation.getId()).stream()
        .map(AccountInvitationReoffer::getHouseholdName)
        .sorted()
        .toList();
  }

  public AcceptedInvitation accept(AcceptInvitationCommand command) {
    var invitation = resolvePending(command.code());
    var passwordHash = passwordEncoder.encode(command.password());

    try {
      return transactionTemplate.execute(
          _ -> {
            if (invitation.getMode() == AccountInvitationMode.CONNECT
                && !profileRepository.lockById(invitation.getProfileId())) {
              throw new InvalidOneTimeCodeException();
            }

            if (!invitationRepository.tryDecide(
                invitation.getId(), AccountInvitationStatus.ACCEPTED, clock.instant())) {
              throw new InvalidOneTimeCodeException();
            }

            var householdId = invitation.getHouseholdId();
            if (householdId == null) {
              // The target Household disappeared after issuance; invalidation should have caught
              // it, and a late code must fail exactly like an unknown one.
              throw new InvalidOneTimeCodeException();
            }

            var account =
                invitation.getMode() == AccountInvitationMode.CONNECT
                    ? connectAccount(command, invitation, passwordHash)
                    : createAccount(command, invitation, passwordHash);
            var issued = refreshTokenService.createSession(account, command.deviceName());
            return AcceptedInvitation.builder()
                .account(account)
                .session(issued.session())
                .rawRefreshToken(issued.rawToken())
                .build();
          });
    } catch (DataIntegrityViolationException exception) {
      var duplicateEmail =
          constraintViolationTranslator
              .constraintName(exception)
              .filter(EMAIL_UNIQUE_INDEX::equals)
              .isPresent();
      if (!duplicateEmail) {
        throw exception;
      }

      throw new InvitationEmailAlreadyUsedException(exception);
    }
  }

  public void decline(String rawCode) {
    var invitation = resolvePending(rawCode);
    if (!invitationRepository.tryDecide(
        invitation.getId(), AccountInvitationStatus.DECLINED, clock.instant())) {
      throw new InvalidOneTimeCodeException();
    }
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
                        .orElseThrow(InvalidOneTimeCodeException::new))
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
   * Connects the invitation's existing Profile as the new Account's Personal Profile (ADR 0024
   * §Profile creation): the home availability becomes the structural share, every current visit
   * ends — a share that admitted a Profile must never silently admit the person — pending offers
   * are invalidated, and each recorded reoffer Household receives a fresh PENDING offer to consent
   * to anew, made exactly once because exactly one acceptance wins the PENDING transition.
   */
  private UserAccount connectAccount(
      AcceptInvitationCommand command, AccountInvitation invitation, String passwordHash) {
    var householdId = invitation.getHouseholdId();
    var profileId = invitation.getProfileId();
    var profile =
        profileRepository.findById(profileId).orElseThrow(InvalidOneTimeCodeException::new);
    if (userAccountRepository.findByPersonalProfileId(profileId).isPresent()
        || !profile.getHouseholdId().equals(householdId)) {
      // The Profile moved on after issuance; a late code fails exactly like an unknown one.
      throw new InvalidOneTimeCodeException();
    }

    var role =
        userAccountRepository
            .roleForNewAccount(householdId, invitation.getHouseholdRole())
            .orElseThrow(InvalidOneTimeCodeException::new);
    if (profile.isRestricted() && role == HouseholdRole.ADMIN) {
      // The first Account becomes HouseholdAdmin, and a restricted Account holds no authority.
      throw new InvalidOneTimeCodeException();
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
    invitationRepository.invalidatePendingForProfile(
        profileId, "Profile connected to an Account", now);
    shareRepository.upsertStructuralHomeShare(profileId, householdId, now);
    endCurrentVisits(profileId, householdId, now);
    shareRepository.invalidatePendingSharesForProfile(
        profileId, "Profile connected to an Account", now);
    reoffer(invitation, account, now);
    return account;
  }

  private void endCurrentVisits(UUID profileId, UUID homeHouseholdId, Instant now) {
    for (var share :
        shareRepository.findByProfileIdAndStatus(profileId, ProfileShareStatus.ACTIVE)) {
      if (!share.getHouseholdId().equals(homeHouseholdId)) {
        shareRepository.tryEnd(share.getId(), now);
        authSessionRepository.clearSelections(profileId, share.getHouseholdId(), now);
      }
    }
  }

  private void reoffer(AccountInvitation invitation, UserAccount account, Instant now) {
    for (var recorded : reofferRepository.findByInvitationId(invitation.getId())) {
      if (recorded.getHouseholdId() != null
          && !recorded.getHouseholdId().equals(invitation.getHouseholdId())) {
        shareRepository.saveAndFlush(
            ProfileHouseholdShare.builder()
                .profileId(invitation.getProfileId())
                .householdId(recorded.getHouseholdId())
                .status(ProfileShareStatus.PENDING)
                .offeredByAccountId(account.getId())
                .expiresAt(now.plus(properties.invitationTtl()))
                .build());
      }
    }
  }

  /**
   * Resolves a presented code to its PENDING, unexpired row: throttled per publicId before any
   * lookup, constant-time digest comparison, one deliberate failure answer for every miss.
   */
  private AccountInvitation resolvePending(String rawCode) {
    var presented = opaqueCodes.parse(rawCode).orElseThrow(InvalidOneTimeCodeException::new);
    throttle.registerCodeGuess(presented.publicId());
    var invitation =
        invitationRepository
            .findByPublicId(presented.publicId())
            .orElseThrow(InvalidOneTimeCodeException::new);
    if (!opaqueCodes.matches(presented, invitation.getSecretDigest())) {
      throw new InvalidOneTimeCodeException();
    }

    if (invitation.getStatus() != AccountInvitationStatus.PENDING
        || !invitation.getExpiresAt().isAfter(clock.instant())) {
      throw new InvalidOneTimeCodeException();
    }

    if (invitation.getMode() == AccountInvitationMode.CONNECT
        && invitation.getProfileId() == null) {
      // The connectable Profile was deleted; invalidation should have flipped the row, and the
      // SET NULL is the backstop. A dead code fails exactly like an unknown one.
      throw new InvalidOneTimeCodeException();
    }

    throttle.resetCodeGuesses(presented.publicId());
    return invitation;
  }

  @Builder
  public record AcceptInvitationCommand(
      String code, String displayName, String password, String deviceName) {

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
      List<String> endingHouseholds,
      List<String> reofferHouseholds) {}

  @Builder
  public record AcceptedInvitation(
      UserAccount account, AuthSession session, String rawRefreshToken) {

    @Override
    public String toString() {
      return "AcceptedInvitation[account=%s, session=%s, rawRefreshToken=REDACTED]"
          .formatted(account.getId(), session.getId());
    }
  }
}
