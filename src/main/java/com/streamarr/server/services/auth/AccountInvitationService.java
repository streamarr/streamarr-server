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
 * Presents and consumes principal-less Account invitation codes.
 *
 * @see <a
 *     href="https://github.com/streamarr/streamarr-adr/blob/main/adr/0024-identity-authority-by-relationship.adoc#invitations">ADR
 *     0024 §Invitations</a>
 */
@Service
@RequiredArgsConstructor
public class AccountInvitationService {

  private static final String EMAIL_UNIQUE_INDEX = "uq_user_account_email";

  private final AccountInvitationRepository invitationRepository;
  private final UserAccountRepository userAccountRepository;
  private final ProfileRepository profileRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final RefreshTokenService refreshTokenService;
  private final OpaqueOneTimeCodes opaqueCodes;
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
      return acceptInTransaction(command, invitation, passwordHash);
    } catch (DataIntegrityViolationException exception) {
      throw translateAcceptanceFailure(exception);
    }
  }

  public void decline(String rawCode) {
    var invitation = resolvePending(rawCode);
    if (!invitationRepository.markDeclinedIfPendingAndUnexpired(
        invitation.getId(), clock.instant())) {
      throw new InvalidOneTimeCodeException();
    }
  }

  private AcceptedInvitation acceptInTransaction(
      AcceptInvitationCommand command, AccountInvitation invitation, String passwordHash) {
    return transactionTemplate.execute(
        _ -> acceptPendingInvitation(command, invitation, passwordHash));
  }

  private AcceptedInvitation acceptPendingInvitation(
      AcceptInvitationCommand command, AccountInvitation invitation, String passwordHash) {
    consumeInvitation(invitation);
    requireTargetHousehold(invitation);
    var account = createAccount(command, invitation, passwordHash);
    var issued = refreshTokenService.createSession(account, command.deviceName());
    return AcceptedInvitation.builder()
        .account(account)
        .session(issued.session())
        .rawRefreshToken(issued.rawToken())
        .build();
  }

  private void consumeInvitation(AccountInvitation invitation) {
    if (!invitationRepository.markAcceptedIfPendingAndUnexpired(
        invitation.getId(), clock.instant())) {
      throw new InvalidOneTimeCodeException();
    }
  }

  private static void requireTargetHousehold(AccountInvitation invitation) {
    if (invitation.getHouseholdId() == null) {
      throw new InvalidOneTimeCodeException();
    }
  }

  private RuntimeException translateAcceptanceFailure(DataIntegrityViolationException exception) {
    var duplicateEmail =
        constraintViolationTranslator
            .constraintName(exception)
            .filter(EMAIL_UNIQUE_INDEX::equals)
            .isPresent();
    if (duplicateEmail) {
      return new InvitationEmailAlreadyUsedException(exception);
    }

    return exception;
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
   * Resolves a presented code to its PENDING, unexpired row: known public IDs are throttled before
   * constant-time digest comparison, with one deliberate failure answer for every miss.
   */
  private AccountInvitation resolvePending(String rawCode) {
    var presented = opaqueCodes.parse(rawCode).orElseThrow(InvalidOneTimeCodeException::new);
    var invitation =
        invitationRepository
            .findByPublicId(presented.publicId())
            .orElseThrow(InvalidOneTimeCodeException::new);
    throttle.registerCodeGuess(presented.publicId());
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
