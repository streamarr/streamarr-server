package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.exceptions.InvitationEmailAlreadyUsedException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import java.time.Clock;
import java.time.Instant;
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
  private final RefreshTokenService refreshTokenService;
  private final OpaqueCodes opaqueCodes;
  private final CredentialGuessThrottle throttle;
  private final PasswordEncoder passwordEncoder;
  private final TransactionTemplate transactionTemplate;
  private final ConstraintViolationTranslator constraintViolationTranslator;
  private final Clock clock;

  /** What the code holder needs to decide; never the secret, never other Households' data. */
  public InvitationPreview lookup(String rawCode) {
    var invitation = resolvePending(rawCode);
    return InvitationPreview.builder()
        .recipientEmail(invitation.getRecipientEmail())
        .householdName(invitation.getHouseholdName())
        .householdRole(invitation.getHouseholdRole())
        .profileName(invitation.getProfileName())
        .profileKind(invitation.getProfileKind())
        .maximumAllowedRatingAge(invitation.getMaximumAllowedRatingAge())
        .expiresAt(invitation.getExpiresAt())
        .build();
  }

  public AcceptedInvitation accept(AcceptInvitationCommand command) {
    var invitation = resolvePending(command.code());
    var passwordHash = passwordEncoder.encode(command.password());

    try {
      return transactionTemplate.execute(
          _ -> {
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
            var account = createAccount(command, invitation, passwordHash);
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
      String profileName,
      ProfileKind profileKind,
      Integer maximumAllowedRatingAge,
      Instant expiresAt) {}

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
