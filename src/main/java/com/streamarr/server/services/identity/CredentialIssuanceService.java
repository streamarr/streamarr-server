package com.streamarr.server.services.identity;

import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.AccountInvitation;
import com.streamarr.server.domain.auth.AccountInvitationMode;
import com.streamarr.server.domain.auth.AccountInvitationReoffer;
import com.streamarr.server.domain.auth.AccountInvitationStatus;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.PasswordResetCode;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileKind;
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
import com.streamarr.server.services.auth.OpaqueCodes;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Issuing and canceling the opaque one-time credentials (ADR 0024 §Invitations, §Account):
 * ServerAdmin work, decided by Cedar. The raw code exists only in the returned value — the row
 * stores its digest, the audit row stores neither. Replacement atomically invalidates the older
 * pending artifact for the same target.
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
  private final OpaqueCodes opaqueCodes;
  private final CredentialCodeProperties properties;
  private final MutationTransactions mutationTransactions;
  private final Clock clock;

  public Outcome<IssuedInvitation, InvitationRejections.Issue> issueAccountInvitation(
      AuthenticatedIdentity identity, IssueInvitationCommand command) {
    authorizationService.requireAllowed(identity, new Intent.IssueAccountInvitation());
    var mode = command.mode() == null ? AccountInvitationMode.CREATE : command.mode();
    var refusal = issueRefusal(mode, command);
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    var household = householdRepository.findById(command.householdId()).orElseThrow();
    var profile =
        mode == AccountInvitationMode.CREATE
            ? null
            : profileRepository.findById(command.profileId()).orElseThrow();
    var emptyHousehold = userAccountRepository.findByHouseholdId(command.householdId()).isEmpty();

    var issued = opaqueCodes.issue();
    var now = clock.instant();
    return mutationTransactions.write(
        () -> {
          invitationRepository.lockRecipientForReplacement(command.recipientEmail());
          requireIssuerStillAllowed(identity);
          invitationRepository.invalidatePendingForEmail(
              command.recipientEmail().strip(), "replaced by a newer invitation", now);
          var invitation =
              invitationRepository.saveAndFlush(
                  AccountInvitation.builder()
                      .recipientEmail(command.recipientEmail().strip())
                      .householdId(command.householdId())
                      .householdName(household.getName())
                      .householdRole(invitedRole(profile, command.householdRole(), emptyHousehold))
                      .mode(mode)
                      .profileId(profile == null ? null : profile.getId())
                      .profileName(
                          profile == null ? command.profileName().strip() : profile.getName())
                      .profileKind(profile == null ? kindFor(command) : profile.getKind())
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

  private static HouseholdRole invitedRole(
      Profile profile, HouseholdRole requestedRole, boolean emptyHousehold) {
    if (emptyHousehold) {
      return HouseholdRole.ADMIN;
    }
    if (profile != null && profile.isRestricted()) {
      return HouseholdRole.MEMBER;
    }
    return requestedRole;
  }

  /** Every issue-time validation, in answer order; empty means the invitation may be written. */
  private Optional<InvitationRejections.Issue> issueRefusal(
      AccountInvitationMode mode, IssueInvitationCommand command) {
    if (isBlank(command.recipientEmail())) {
      return Optional.of(new InvitationRejections.EmailRequired());
    }
    if (mode == AccountInvitationMode.CREATE && isBlank(command.profileName())) {
      return Optional.of(new InvitationRejections.ProfileNameRequired());
    }
    if (userAccountRepository.findByEmailIgnoreCase(command.recipientEmail().strip()).isPresent()) {
      // An existing email cannot be invited or reassigned; ServerAdmin transfers instead.
      return Optional.of(new InvitationRejections.EmailAlreadyUsed());
    }
    if (householdRepository.findById(command.householdId()).isEmpty()) {
      return Optional.of(new InvitationRejections.HouseholdNotFound());
    }
    var connectRefusal = connectRefusal(mode, command);
    if (connectRefusal.isPresent()) {
      return connectRefusal;
    }
    var profile =
        mode == AccountInvitationMode.CREATE
            ? null
            : profileRepository.findById(command.profileId()).orElseThrow();
    var restrictionRefusal = restrictionRefusal(mode, command, profile);
    if (restrictionRefusal.isPresent()) {
      return restrictionRefusal;
    }
    if (command.localManagerAccountId() != null
        && !userAccountRepository.isEligibleProfileManager(
            command.localManagerAccountId(), command.householdId(), isRestricted(command, profile))) {
      return Optional.of(new InvitationRejections.LocalManagerNotFound());
    }
    return reofferRefusal(profile, command);
  }

  /** A restricted first Account is impossible, and a restricted new Profile names its anchor. */
  private Optional<InvitationRejections.Issue> restrictionRefusal(
      AccountInvitationMode mode, IssueInvitationCommand command, Profile profile) {
    var restricted = isRestricted(command, profile);
    if (!restricted) {
      return Optional.empty();
    }
    if (userAccountRepository.findByHouseholdId(command.householdId()).isEmpty()) {
      // The first Account becomes HouseholdAdmin, and a restricted Account holds no authority.
      return Optional.of(new InvitationRejections.RestrictedFirstAccount());
    }
    if (mode == AccountInvitationMode.CREATE && command.localManagerAccountId() == null) {
      return Optional.of(new InvitationRejections.LocalManagerRequired());
    }
    return Optional.empty();
  }

  private static boolean isRestricted(IssueInvitationCommand command, Profile profile) {
    return profile == null
        ? command.profileKind() == ProfileKind.KID || command.maximumAllowedRatingAge() != null
        : profile.isRestricted();
  }

  /** CREATE has no Profile yet; CONNECT names an existing, unlinked one in that Household. */
  private Optional<InvitationRejections.Issue> connectRefusal(
      AccountInvitationMode mode, IssueInvitationCommand command) {
    if (mode == AccountInvitationMode.CREATE) {
      return Optional.empty();
    }
    if (command.profileId() == null) {
      return Optional.of(new InvitationRejections.ConnectProfileRequired());
    }
    var profile = profileRepository.findById(command.profileId());
    if (profile.isEmpty()) {
      return Optional.of(new InvitationRejections.ConnectProfileNotFound());
    }
    if (userAccountRepository.findByPersonalProfileId(command.profileId()).isPresent()) {
      return Optional.of(new InvitationRejections.ProfileAlreadyLinked());
    }
    if (!profile.get().getHouseholdId().equals(command.householdId())) {
      return Optional.of(new InvitationRejections.ProfileNotInHousehold());
    }
    return Optional.empty();
  }

  /** Every reoffer target must exist and actively host the Profile as a visit today. */
  private Optional<InvitationRejections.Issue> reofferRefusal(
      Profile profile, IssueInvitationCommand command) {
    for (var householdId : reofferHouseholdIds(command)) {
      if (householdRepository.findById(householdId).isEmpty()) {
        return Optional.of(new InvitationRejections.ReofferHouseholdNotFound());
      }
      var visiting =
          !householdId.equals(command.householdId())
              && shareRepository
                  .findByProfileIdAndHouseholdIdAndStatus(
                      profile.getId(), householdId, ProfileShareStatus.ACTIVE)
                  .isPresent();
      if (!visiting) {
        return Optional.of(new InvitationRejections.ReofferHouseholdNotShared());
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

  private static ProfileKind kindFor(IssueInvitationCommand command) {
    return command.profileKind() == null ? ProfileKind.ADULT : command.profileKind();
  }

  public Outcome<AccountInvitation, InvitationRejections.Cancel> cancelAccountInvitation(
      AuthenticatedIdentity identity, UUID invitationId) {
    authorizationService.requireAllowed(identity, new Intent.CancelAccountInvitation());
    return mutationTransactions.write(
        () -> {
          if (!invitationRepository.tryDecide(
              invitationId, AccountInvitationStatus.CANCELED, clock.instant())) {
            throw new MutationRejection(new InvitationRejections.InvitationNotPending());
          }

          return invitationRepository.findById(invitationId).orElseThrow();
        },
        _ -> Optional.empty());
  }

  public Outcome<IssuedResetCode, InvitationRejections.IssueReset> issuePasswordReset(
      AuthenticatedIdentity identity, UUID accountId, String reason) {
    if (isBlank(reason)) {
      return Outcome.rejected(new InvitationRejections.ReasonRequired());
    }

    var refusal = resetRefusal(identity, accountId);
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    if (userAccountRepository.findById(accountId).isEmpty()) {
      return Outcome.rejected(new InvitationRejections.AccountNotFound());
    }

    var issued = opaqueCodes.issue();
    var now = clock.instant();
    return mutationTransactions.write(
        () -> {
          resetCodeRepository.lockAccountForReplacement(accountId);
          requireIssuerStillAllowed(identity);
          resetCodeRepository.invalidatePendingForAccount(
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

  private Optional<InvitationRejections.IssueReset> resetRefusal(
      AuthenticatedIdentity identity, UUID accountId) {
    return switch (authorizationService.decide(
        identity, new Intent.IssuePasswordReset(accountId))) {
      case Decision.Allowed<?> _ -> Optional.empty();
      case Decision.Failed<?> _ -> throw new AuthorizationUnavailableException();
      case Decision.Denied<?>(var reason) ->
          switch (reason) {
            case REAUTHENTICATION_REQUIRED ->
                Optional.of(new InvitationRejections.ReauthenticationRequired());
            case POLICY -> {
              if (mayViewAccount(identity, accountId)) {
                throw new AccessDeniedException("Not allowed.");
              }

              yield Optional.of(new InvitationRejections.AccountNotFound());
            }
          };
    };
  }

  private boolean mayViewAccount(AuthenticatedIdentity identity, UUID accountId) {
    return authorizationService.decide(identity, new Intent.ViewAccountAdministration(accountId))
        instanceof Decision.Allowed<?>;
  }

  private void requireIssuerStillAllowed(AuthenticatedIdentity identity) {
    if (!userAccountRepository.lockIfEnabledServerAdmin(identity.accountId())) {
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
      UUID localManagerAccountId) {}
}
