package com.streamarr.server.services.identity;

import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManagerEligibility;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.OpaqueOneTimeCodes;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Issuing and canceling the opaque one-time credentials (ADR 0024 §Invitations, §Account):
 * ServerAdmin work, decided by Cedar. The raw code exists only in the returned value — the row
 * stores its digest, and the audit row a reset issuance writes stores neither. Replacement
 * atomically invalidates the older pending artifact for the same target.
 */
@Service
@RequiredArgsConstructor
public class CredentialIssuanceService {

  private final AuthorizationService authorizationService;
  private final AccountInvitationRepository invitationRepository;
  private final PasswordResetCodeRepository resetCodeRepository;
  private final UserAccountRepository userAccountRepository;
  private final HouseholdRepository householdRepository;
  private final ProfileRepository profileRepository;
  private final SecurityAuditEventRepository securityAuditEventRepository;
  private final OpaqueOneTimeCodes opaqueCodes;
  private final CredentialCodeProperties properties;
  private final MutationTransactions mutationTransactions;
  private final Clock clock;

  public Outcome<IssuedInvitation, CredentialRejections.Issue> issueAccountInvitation(
      AuthenticatedIdentity identity, IssueInvitationCommand command) {
    authorizationService.requireAllowed(identity, new Intent.IssueAccountInvitation());
    var inputRejection = inputRejection(command);
    if (inputRejection.isPresent()) {
      return Outcome.rejected(inputRejection.get());
    }

    var household = householdRepository.findById(command.householdId());
    if (household.isEmpty()) {
      return Outcome.rejected(new CredentialRejections.HouseholdNotFound());
    }

    var shapeRejection = profileShapeRejection(command);
    if (shapeRejection.isPresent()) {
      return Outcome.rejected(shapeRejection.get());
    }

    return issueInvitation(identity, command, household.get());
  }

  /**
   * Field-level refusals that need no Household: blank fields, an invalid ceiling, a used email.
   */
  private Optional<CredentialRejections.Issue> inputRejection(IssueInvitationCommand command) {
    if (isBlank(command.recipientEmail())) {
      return Optional.of(new CredentialRejections.EmailRequired());
    }

    if (!isPlausibleEmail(command.recipientEmail().strip())) {
      return Optional.of(new CredentialRejections.EmailInvalid());
    }

    if (isBlank(command.profileName())) {
      return Optional.of(new CredentialRejections.ProfileNameRequired());
    }

    if (command.maximumAllowedRatingAge() != null && command.maximumAllowedRatingAge() < 0) {
      return Optional.of(new CredentialRejections.MaximumAllowedRatingAgeInvalid());
    }

    if (userAccountRepository.findByEmailIgnoreCase(command.recipientEmail().strip()).isPresent()) {
      // An existing email cannot be invited or reassigned; ServerAdmin transfers instead.
      return Optional.of(new CredentialRejections.EmailAlreadyUsed());
    }

    return Optional.empty();
  }

  /**
   * One local part, one @, a domain of dot-separated non-empty labels, no whitespace: the shape,
   * not deliverability. Iterative checks, so a long domain costs no stack.
   */
  private static boolean isPlausibleEmail(String email) {
    var at = email.indexOf('@');
    if (at < 1 || at != email.lastIndexOf('@')) {
      return false;
    }

    if (email.chars().anyMatch(Character::isWhitespace)) {
      return false;
    }

    var labels = email.substring(at + 1).split("\\.", -1);
    return labels.length > 1 && Arrays.stream(labels).noneMatch(String::isEmpty);
  }

  /** The new Profile's shape against its Household: name, restriction, and required manager. */
  private Optional<CredentialRejections.Issue> profileShapeRejection(
      IssueInvitationCommand command) {
    if (profileRepository.existsAvailableInHouseholdWithNameIgnoreCase(
        command.householdId(), command.profileName().strip())) {
      return Optional.of(new CredentialRejections.ProfileNameTaken());
    }

    var restricted = command.restricted();
    var emptyHousehold = userAccountRepository.findByHouseholdId(command.householdId()).isEmpty();
    if (restricted && emptyHousehold) {
      // The first Account becomes HouseholdAdmin, and a restricted Account holds no authority.
      return Optional.of(new CredentialRejections.RestrictedFirstAccount());
    }

    if (restricted && command.householdRole() == HouseholdRole.ADMIN) {
      // Otherwise acceptance would fail at commit (chk_restricted_account_holds_no_authority).
      return Optional.of(new CredentialRejections.RestrictedHouseholdAdmin());
    }

    if (restricted && command.localManagerAccountId() == null) {
      return Optional.of(new CredentialRejections.LocalManagerRequired());
    }

    if (command.localManagerAccountId() != null
        && !userAccountRepository.isEligibleProfileManager(
            command.localManagerAccountId(),
            command.householdId(),
            ProfileManagerEligibility.forRestricted(restricted))) {
      return Optional.of(new CredentialRejections.ProfileManagerNotEligible());
    }

    return Optional.empty();
  }

  private Outcome<IssuedInvitation, CredentialRejections.Issue> issueInvitation(
      AuthenticatedIdentity identity, IssueInvitationCommand command, Household household) {
    var recipientEmail = command.recipientEmail().strip();
    var profileName = command.profileName().strip();
    var profileKind = Objects.requireNonNullElse(command.profileKind(), ProfileKind.ADULT);
    var issued = opaqueCodes.issue();
    return mutationTransactions.write(
        () -> {
          invitationRepository.lockInvitationIssuanceForRecipientEmail(recipientEmail);
          requireIssuerStillAllowed(identity);
          if (userAccountRepository.findByEmailIgnoreCase(recipientEmail).isPresent()) {
            throw new MutationRejection(new CredentialRejections.EmailAlreadyUsed());
          }

          var now = clock.instant();
          invitationRepository.expirePendingInvitationsForRecipientEmail(recipientEmail, now);
          invitationRepository.invalidatePendingInvitationsForRecipientEmail(
              recipientEmail, "replaced by a newer invitation", now);
          var invitation =
              invitationRepository.saveAndFlush(
                  AccountInvitation.builder()
                      .recipientEmail(recipientEmail)
                      .householdId(command.householdId())
                      .householdName(household.getName())
                      .householdRole(command.householdRole())
                      .profileName(profileName)
                      .profileKind(profileKind)
                      .maximumAllowedRatingAge(command.maximumAllowedRatingAge())
                      .localManagerAccountId(command.localManagerAccountId())
                      .issuerAccountId(identity.accountId())
                      .expiresAt(now.plus(properties.invitationTtl()))
                      .publicId(issued.publicId())
                      .secretDigest(issued.digest())
                      .build());
          return new IssuedInvitation(invitation, issued.code());
        },
        _ -> Optional.empty());
  }

  public Outcome<AccountInvitation, CredentialRejections.Cancel> cancelAccountInvitation(
      AuthenticatedIdentity identity, UUID invitationId) {
    authorizationService.requireAllowed(identity, new Intent.CancelAccountInvitation());
    return mutationTransactions.write(
        () ->
            invitationRepository
                .cancelIfPendingAndUnexpired(invitationId, clock.instant())
                .orElseThrow(
                    () -> new MutationRejection(new CredentialRejections.InvitationNotPending())),
        _ -> Optional.empty());
  }

  public Outcome<IssuedResetCode, CredentialRejections.IssueReset> issuePasswordReset(
      AuthenticatedIdentity identity, UUID accountId, String reason) {
    if (isBlank(reason)) {
      return Outcome.rejected(new CredentialRejections.ReasonRequired());
    }

    var refusal = resetRefusal(identity, accountId);
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    if (userAccountRepository.findById(accountId).isEmpty()) {
      return Outcome.rejected(new CredentialRejections.AccountNotFound());
    }

    var issued = opaqueCodes.issue();
    return mutationTransactions.write(
        () -> {
          lockResetParticipants(identity, accountId);
          requireIssuerStillAllowed(identity);
          var now = clock.instant();
          resetCodeRepository.expirePendingPasswordResetCodesForAccount(accountId, now);
          resetCodeRepository.invalidatePendingPasswordResetCodesForAccount(
              accountId, "replaced by a newer code", now);
          var code =
              resetCodeRepository.saveAndFlush(
                  PasswordResetCode.builder()
                      .accountId(accountId)
                      .issuerAccountId(identity.accountId())
                      .expiresAt(now.plus(properties.passwordResetTtl()))
                      .publicId(issued.publicId())
                      .secretDigest(issued.digest())
                      .build());
          securityAuditEventRepository.append(
              SecurityAuditEntry.builder()
                  .operation("issuePasswordReset")
                  .actorAccountId(identity.accountId())
                  .reason(reason)
                  .resource("accountId", accountId)
                  .build());
          return new IssuedResetCode(code, issued.code());
        },
        _ -> Optional.empty());
  }

  private void lockResetParticipants(AuthenticatedIdentity identity, UUID accountId) {
    var participantIds = Set.copyOf(List.of(identity.accountId(), accountId));
    var lockedIds =
        userAccountRepository.lockByIds(participantIds, properties.replacementLockTimeout());
    if (!lockedIds.contains(accountId)) {
      throw new MutationRejection(new CredentialRejections.AccountNotFound());
    }

    if (!lockedIds.contains(identity.accountId())) {
      throw new AccessDeniedException("Not allowed.");
    }
  }

  private Optional<CredentialRejections.IssueReset> resetRefusal(
      AuthenticatedIdentity identity, UUID accountId) {
    return switch (authorizationService.decide(
        identity, new Intent.IssuePasswordReset(accountId))) {
      case Decision.Allowed<?> _ -> Optional.empty();
      case Decision.Failed<?> _ -> throw new AuthorizationUnavailableException();
      case Decision.Denied<?>(var reason) ->
          switch (reason) {
            case REAUTHENTICATION_REQUIRED ->
                Optional.of(new CredentialRejections.ReauthenticationRequired());
            case POLICY -> {
              if (mayViewAccount(identity, accountId)) {
                throw new AccessDeniedException("Not allowed.");
              }

              yield Optional.of(new CredentialRejections.AccountNotFound());
            }
          };
    };
  }

  private boolean mayViewAccount(AuthenticatedIdentity identity, UUID accountId) {
    return authorizationService.isAllowed(
        identity, new Intent.ViewAccountAdministration(accountId));
  }

  private void requireIssuerStillAllowed(AuthenticatedIdentity identity) {
    if (!userAccountRepository.tryLockEnabledServerAdmin(identity.accountId())) {
      throw new AccessDeniedException("Not allowed.");
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** The raw code rides here once; it is never stored, logged, or queryable again. */
  public record IssuedInvitation(AccountInvitation invitation, String code) {

    @Override
    public String toString() {
      return "IssuedInvitation[invitation=%s, code=REDACTED]".formatted(invitation.getId());
    }
  }

  public record IssuedResetCode(PasswordResetCode resetCode, String code) {

    @Override
    public String toString() {
      return "IssuedResetCode[resetCode=%s, code=REDACTED]".formatted(resetCode.getId());
    }
  }

  @Builder(toBuilder = true)
  public record IssueInvitationCommand(
      String recipientEmail,
      UUID householdId,
      HouseholdRole householdRole,
      String profileName,
      ProfileKind profileKind,
      Integer maximumAllowedRatingAge,
      UUID localManagerAccountId) {

    /** A restriction means supervision: Kid kind or any Content Ceiling. */
    public boolean restricted() {
      return Profile.isRestricted(profileKind, maximumAllowedRatingAge);
    }
  }
}
