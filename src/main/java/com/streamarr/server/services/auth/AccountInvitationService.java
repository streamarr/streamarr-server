package com.streamarr.server.services.auth;

import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AuthSession;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvitationEmailAlreadyUsedException;
import com.streamarr.server.exceptions.InvitationNotAcceptableException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import java.time.Clock;
import java.time.Instant;
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
 *     href="https://github.com/streamarr/streamarr-adr/blob/main/adr/0024-identity-authority-by-relationship.adoc#invitations">ADR
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

  private final AccountInvitationRepository invitationRepository;
  private final UserAccountRepository userAccountRepository;
  private final ProfileRepository profileRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final RefreshTokenService refreshTokenService;
  private final OpaqueCodeResolver codeResolver;
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

  private AccountInvitation resolvePending(String rawCode) {
    return codeResolver.resolvePending(rawCode, invitationRepository::findByPublicId);
  }

  @Builder
  public record AcceptInvitationCommand(
      String code, String displayName, String password, String deviceName) {

    public static class AcceptInvitationCommandBuilder {

      @Override
      public String toString() {
        return "AcceptInvitationCommandBuilder[code=REDACTED, displayName=%s, password=REDACTED,"
            + " deviceName=%s]".formatted(displayName, deviceName);
      }
    }

    @Override
    public String toString() {
      return "AcceptInvitationCommand[code=REDACTED, displayName=%s, password=REDACTED,"
          + " deviceName=%s]".formatted(displayName, deviceName);
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
