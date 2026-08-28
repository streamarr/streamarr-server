package com.streamarr.server.services.identity;

import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationMode;
import com.streamarr.server.domain.auth.AccountInvitationReoffer;
import com.streamarr.server.domain.auth.Household;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManagerEligibility;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.AccountInvitationReofferRepository;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.PasswordResetCodeRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.EmailAddressValidator;
import com.streamarr.server.services.auth.OpaqueOneTimeCodes;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
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
  private final ProfileHouseholdShareRepository shareRepository;
  private final AccountInvitationReofferRepository reofferRepository;
  private final SecurityAuditEventRepository securityAuditEventRepository;
  private final OpaqueOneTimeCodes opaqueCodes;
  private final CredentialCodeProperties properties;
  private final MutationTransactions mutationTransactions;
  private final Clock clock;

  public Outcome<IssuedInvitation, CredentialRejections.Issue> issueAccountInvitation(
      AuthenticatedIdentity identity, IssueInvitationCommand command) {
    authorizationService.requireAllowed(identity, new Intent.IssueAccountInvitation());
    return switch (EmailAddressValidator.validate(command.recipientEmail())) {
      case EmailAddressValidator.Blank _ ->
          Outcome.rejected(new CredentialRejections.EmailRequired());
      case EmailAddressValidator.Malformed _ ->
          Outcome.rejected(new CredentialRejections.EmailInvalid());
      case EmailAddressValidator.Valid(var recipientEmail) ->
          issueInvitationTo(identity, command.toBuilder().recipientEmail(recipientEmail).build());
    };
  }

  /** The remaining refusals and the write, for a command whose recipient email is normalized. */
  private Outcome<IssuedInvitation, CredentialRejections.Issue> issueInvitationTo(
      AuthenticatedIdentity identity, IssueInvitationCommand command) {
    var mode = modeFor(command);
    var inputRejection = inputRejection(command, mode);
    if (inputRejection.isPresent()) {
      return Outcome.rejected(inputRejection.get());
    }

    var household = householdRepository.findById(command.householdId());
    if (household.isEmpty()) {
      return Outcome.rejected(new CredentialRejections.HouseholdNotFound());
    }

    var connectRejection = connectRejection(mode, command);
    if (connectRejection.isPresent()) {
      return Outcome.rejected(connectRejection.get());
    }

    var profile =
        mode == AccountInvitationMode.CREATE
            ? null
            : profileRepository.findById(command.profileId()).orElseThrow();
    var shapeRejection = profileShapeRejection(command, mode, profile);
    if (shapeRejection.isPresent()) {
      return Outcome.rejected(shapeRejection.get());
    }

    var reofferRejection =
        profile == null
            ? Optional.<CredentialRejections.Issue>empty()
            : reofferRejection(profile, command);
    if (reofferRejection.isPresent()) {
      return Outcome.rejected(reofferRejection.get());
    }

    return issueInvitation(identity, command, household.get());
  }

  /**
   * Field-level refusals that need no Household: a blank name, an invalid ceiling, a used email.
   */
  private Optional<CredentialRejections.Issue> inputRejection(
      IssueInvitationCommand command, AccountInvitationMode mode) {
    if (mode == AccountInvitationMode.CREATE && isBlank(command.profileName())) {
      return Optional.of(new CredentialRejections.ProfileNameRequired());
    }

    if (command.maximumAllowedRatingAge() != null && command.maximumAllowedRatingAge() < 0) {
      return Optional.of(new CredentialRejections.MaximumAllowedRatingAgeInvalid());
    }

    if (userAccountRepository.findByEmailIgnoreCase(command.recipientEmail()).isPresent()) {
      // An existing email cannot be invited or reassigned; ServerAdmin transfers instead.
      return Optional.of(new CredentialRejections.EmailAlreadyUsed());
    }

    return Optional.empty();
  }

  /** The new Profile's shape against its Household: name, restriction, and required manager. */
  private Optional<CredentialRejections.Issue> profileShapeRejection(
      IssueInvitationCommand command, AccountInvitationMode mode, Profile profile) {
    if (mode == AccountInvitationMode.CREATE
        && profileRepository.existsAvailableInHouseholdWithNameIgnoreCase(
            command.householdId(), command.profileName().strip())) {
      return Optional.of(new CredentialRejections.ProfileNameTaken());
    }

    var restricted = profile == null ? command.restricted() : profile.isRestricted();
    var emptyHousehold = userAccountRepository.findByHouseholdId(command.householdId()).isEmpty();
    if (restricted && emptyHousehold) {
      // The first Account becomes HouseholdAdmin, and a restricted Account holds no authority.
      return Optional.of(new CredentialRejections.RestrictedFirstAccount());
    }

    if (mode == AccountInvitationMode.CREATE
        && restricted
        && command.householdRole() == HouseholdRole.ADMIN) {
      // Otherwise acceptance would fail at commit (chk_restricted_account_holds_no_authority).
      return Optional.of(new CredentialRejections.RestrictedHouseholdAdmin());
    }

    if (mode == AccountInvitationMode.CREATE
        && restricted
        && command.localManagerAccountId() == null) {
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
    var recipientEmail = command.recipientEmail();
    var mode = modeFor(command);
    var issued = opaqueCodes.issue();
    return mutationTransactions.write(
        () -> {
          invitationRepository.lockInvitationIssuanceForRecipientEmail(recipientEmail);
          requireIssuerStillAllowed(identity);
          var profile = lockConnectProfile(mode, command);
          if (userAccountRepository.findByEmailIgnoreCase(recipientEmail).isPresent()) {
            throw new MutationRejection(new CredentialRejections.EmailAlreadyUsed());
          }

          var profileName = profile == null ? command.profileName().strip() : profile.getName();
          var profileKind =
              profile == null
                  ? Objects.requireNonNullElse(command.profileKind(), ProfileKind.ADULT)
                  : profile.getKind();
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
                      .householdRole(invitedRole(profile, command.householdRole()))
                      .mode(mode)
                      .profileId(profile == null ? null : profile.getId())
                      .profileName(profileName)
                      .profileKind(profileKind)
                      .maximumAllowedRatingAge(
                          profile == null
                              ? command.maximumAllowedRatingAge()
                              : profile.getMaximumAllowedRatingAge())
                      .localManagerAccountId(command.localManagerAccountId())
                      .issuerAccountId(identity.accountId())
                      .expiresAt(now.plus(properties.invitationTtl()))
                      .publicId(issued.publicId())
                      .secretDigest(issued.digest())
                      .build());
          saveReoffers(invitation, command);
          return new IssuedInvitation(invitation, issued.code());
        },
        _ -> Optional.empty());
  }

  private Profile lockConnectProfile(AccountInvitationMode mode, IssueInvitationCommand command) {
    if (mode == AccountInvitationMode.CREATE) {
      return null;
    }

    if (!profileRepository.lockById(command.profileId())) {
      throw new MutationRejection(new CredentialRejections.ConnectProfileNotFound());
    }

    var rejection = connectRejection(mode, command);
    if (rejection.isPresent()) {
      throw new MutationRejection(rejection.get());
    }

    return profileRepository.findById(command.profileId()).orElseThrow();
  }

  private static HouseholdRole invitedRole(Profile profile, HouseholdRole requestedRole) {
    if (profile != null && profile.isRestricted()) {
      return HouseholdRole.MEMBER;
    }

    return requestedRole;
  }

  /** CREATE has no Profile yet; CONNECT names an existing, unlinked one in that Household. */
  private Optional<CredentialRejections.Issue> connectRejection(
      AccountInvitationMode mode, IssueInvitationCommand command) {
    if (mode == AccountInvitationMode.CREATE) {
      return Optional.empty();
    }

    if (command.profileId() == null) {
      return Optional.of(new CredentialRejections.ConnectProfileRequired());
    }

    var profile = profileRepository.findById(command.profileId());
    if (profile.isEmpty()) {
      return Optional.of(new CredentialRejections.ConnectProfileNotFound());
    }

    if (userAccountRepository.findByPersonalProfileId(command.profileId()).isPresent()) {
      return Optional.of(new CredentialRejections.ProfileAlreadyLinked());
    }

    if (!profile.get().getHouseholdId().equals(command.householdId())) {
      return Optional.of(new CredentialRejections.ProfileNotInHousehold());
    }

    return Optional.empty();
  }

  /** Every reoffer target must exist and actively host the Profile as a visit today. */
  private Optional<CredentialRejections.Issue> reofferRejection(
      Profile profile, IssueInvitationCommand command) {
    for (var householdId : reofferHouseholdIds(command)) {
      if (householdRepository.findById(householdId).isEmpty()) {
        return Optional.of(new CredentialRejections.ReofferHouseholdNotFound());
      }

      var visiting =
          !householdId.equals(command.householdId())
              && shareRepository
                  .findByProfileIdAndHouseholdIdAndStatus(
                      profile.getId(), householdId, ProfileShareStatus.ACTIVE)
                  .isPresent();
      if (!visiting) {
        return Optional.of(new CredentialRejections.ReofferHouseholdNotShared());
      }
    }

    return Optional.empty();
  }

  private void saveReoffers(AccountInvitation invitation, IssueInvitationCommand command) {
    for (var householdId : reofferHouseholdIds(command)) {
      var name = householdRepository.findById(householdId).orElseThrow().getName();
      reofferRepository.saveAndFlush(
          AccountInvitationReoffer.builder()
              .invitationId(invitation.getId())
              .householdId(householdId)
              .householdName(name)
              .build());
    }
  }

  private static List<UUID> reofferHouseholdIds(IssueInvitationCommand command) {
    if (command.mode() != AccountInvitationMode.CONNECT || command.reofferHouseholdIds() == null) {
      return List.of();
    }

    return command.reofferHouseholdIds().stream().distinct().toList();
  }

  private static AccountInvitationMode modeFor(IssueInvitationCommand command) {
    return Objects.requireNonNullElse(command.mode(), AccountInvitationMode.CREATE);
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
      AccountInvitationMode mode,
      UUID profileId,
      List<UUID> reofferHouseholdIds,
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
