package com.streamarr.server.services.identity;

import com.streamarr.server.domain.auth.CredentialAttemptMetadata;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileManagerInvitationsFilter;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.InvalidOneTimeCodeException;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.CredentialAttemptGate;
import com.streamarr.server.services.auth.EmailAddressValidator;
import com.streamarr.server.services.auth.InvitationLifetime;
import com.streamarr.server.services.auth.OpaqueOneTimeCodes;
import com.streamarr.server.services.auth.ProfileManagerInvitationLifecycle;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.Pager;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Manages direct ProfileManagers through invitations or ServerAdmin overrides (ADR 0024). Overrides
 * require reauthentication. Removing a manager invalidates their outstanding invitations and share
 * offers. Code checks are journaled by invitation ID and return the same error for all invalid
 * codes.
 */
@Service
@RequiredArgsConstructor
public class ProfileManagerAdministrationService {

  private static final String CHK_RESTRICTED_AUTHORITY =
      "chk_restricted_account_holds_no_authority";
  private static final String CHK_ELIGIBLE_MANAGER = "chk_profile_home_anchor";
  private static final String REPLACED_REASON = "replaced by a newer invitation";
  private static final String BECAME_MANAGER_REASON = "named Account became a manager";
  // ADR 0024: so that a manager cannot fill other people's Notifications with Requests.
  private static final int PENDING_INVITATIONS_PER_PROFILE = 10;
  private static final String RESOURCE_PROFILE_ID = "profileId";
  private static final String RESOURCE_ACCOUNT_ID = "accountId";

  private final AuthorizationService authorizationService;
  private final ProfileManagerInvitationRepository invitationRepository;
  private final ProfileManagerInvitationLifecycle invitationLifecycle;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileRepository profileRepository;
  private final UserAccountRepository userAccountRepository;
  private final LeavingManagerProposals leavingManagerProposals;
  private final SecurityAuditEventRepository securityAuditEventRepository;
  private final OpaqueOneTimeCodes opaqueCodes;
  private final CredentialAttemptGate credentialAttempts;
  private final InvitationLifetime invitationLifetime;
  private final MutationTransactions mutationTransactions;
  private final ProfileManagerInvitationsList profileManagerInvitationsList;
  private final ReceivedManagerInvitationsList receivedManagerInvitationsList;
  private final Clock clock;

  /**
   * Invites whoever has the email address to manage the Profile. The answer is the same whether or
   * not an Account has the address and whether or not that Account may accept: the invitation names
   * the Account only when it may, and otherwise waits with nobody who can accept it (ADR 0024).
   */
  public Outcome<IssuedManagerInvitation, ManagerRejections.Invite> inviteProfileManager(
      AuthenticatedIdentity identity, UUID profileId, String email) {
    var profile = profileRepository.findById(profileId);
    if (profile.isEmpty()) {
      return Outcome.rejected(new ManagerRejections.ProfileNotFound());
    }

    var refusal = inviteRefusal(identity, profileId);
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    return switch (EmailAddressValidator.validate(email)) {
      case EmailAddressValidator.Blank _ -> Outcome.rejected(new ManagerRejections.EmailRequired());
      case EmailAddressValidator.Malformed _ ->
          Outcome.rejected(new ManagerRejections.EmailInvalid());
      case EmailAddressValidator.Valid(var address) -> invite(identity, profile.get(), address);
    };
  }

  // Every address runs the same statements in the same order, whether or not an Account may accept
  // the invitation, so neither the answer nor its timing tells the inviter who has an Account.
  private Outcome<IssuedManagerInvitation, ManagerRejections.Invite> invite(
      AuthenticatedIdentity identity, Profile profile, String address) {
    var profileId = profile.getId();
    var inviterName =
        userAccountRepository
            .findById(identity.accountId())
            .map(UserAccount::getDisplayName)
            .orElse("");
    var issued = opaqueCodes.issue();
    var now = clock.instant();
    return mutationTransactions.write(
        () -> {
          lockProfile(profileId, ManagerRejections.ProfileNotFound::new);
          var currentRefusal = inviteRefusal(identity, profileId);
          if (currentRefusal.isPresent()) {
            throw new MutationRejection(currentRefusal.get());
          }

          // Counted under the Profile's lock, which every invitation to it takes first.
          if (invitationRepository.countLivePendingForProfileExceptTo(profileId, address, now)
              >= PENDING_INVITATIONS_PER_PROFILE) {
            throw new MutationRejection(new ManagerRejections.ManagerInvitationLimitReached());
          }

          var recipientAccountId =
              userAccountRepository.lockAccountThatMayAcceptManagement(profileId, address);
          invitationLifecycle.invalidatePendingByProfileIdAndRecipientEmail(
              profileId, address, REPLACED_REASON, now);
          var invitation =
              invitationLifecycle.issue(
                  ProfileManagerInvitation.builder()
                      .profileId(profileId)
                      .profileName(profile.getName())
                      .inviterAccountId(identity.accountId())
                      .inviterDisplayName(inviterName)
                      .recipientAccountId(recipientAccountId.orElse(null))
                      .recipientEmail(address)
                      .expiresAt(now.plus(invitationLifetime.invitationLifetime()))
                      .publicId(issued.publicId())
                      .secretDigest(issued.digest())
                      .build());
          return new IssuedManagerInvitation(invitation, issued.code());
        },
        _ -> Optional.empty());
  }

  private Optional<ManagerRejections.Invite> inviteRefusal(
      AuthenticatedIdentity identity, UUID profileId) {
    return refusalOf(
        identity,
        new Intent.InviteProfileManager(profileId),
        () -> mayViewProfile(identity, profileId),
        ManagerRejections.ProfileNotFound::new,
        Optional.empty());
  }

  public Outcome<ProfileManagerInvitation, ManagerRejections.Cancel> cancelManagerInvitation(
      AuthenticatedIdentity identity, UUID invitationId) {
    Optional<ManagerRejections.Cancel> refusal =
        refusalOf(
            identity,
            new Intent.CancelManagerInvitation(invitationId),
            () -> mayViewInvitation(identity, invitationId),
            ManagerRejections.ManagerInvitationNotFound::new,
            Optional.empty());
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    return mutationTransactions.write(
        () -> {
          if (!invitationLifecycle.tryCancelPending(
              invitationId, identity.accountId(), clock.instant())) {
            throw new MutationRejection(new ManagerRejections.InvitationNotPending());
          }

          return invitationRepository.findById(invitationId).orElseThrow();
        },
        _ -> Optional.empty());
  }

  /**
   * The invitation a presented code belongs to, so that its named Account can decide it by id. Each
   * lookup is a credential attempt by the presenting Account, counted against the invitation, and
   * it leaves the invitation pending. A session that may not read the invitations it received is
   * refused with FORBIDDEN before any attempt counts. A code that does not work, and one that names
   * another Account, get ManagerInvitationNotFound.
   */
  public Outcome<ProfileManagerInvitation, ManagerRejections.LookUp> lookUpManagerInvitationCode(
      AuthenticatedIdentity identity, ManagerInvitationCodeCommand command) {
    authorizationService.requireAllowed(identity, new Intent.ViewReceivedManagerInvitations());
    return resolvePresented(command)
        .filter(
            invitation ->
                authorizationService.isAllowed(
                    identity, new Intent.ViewReceivedManagerInvitation(invitation.getId())))
        .<Outcome<ProfileManagerInvitation, ManagerRejections.LookUp>>map(Outcome::accepted)
        .orElseGet(() -> Outcome.rejected(new ManagerRejections.ManagerInvitationNotFound()));
  }

  /**
   * The named Account accepts by the invitation's id and becomes a direct manager. No code is
   * presented, so nothing counts as a credential attempt.
   */
  public Outcome<ProfileManagerInvitation, ManagerRejections.Accept> acceptManagerInvitation(
      AuthenticatedIdentity identity, UUID invitationId) {
    var found =
        decidableBy(identity, invitationId, new Intent.AcceptManagerInvitation(invitationId));
    if (found.isEmpty()) {
      return Outcome.rejected(new ManagerRejections.ManagerInvitationNotFound());
    }

    var invitation = found.get();
    var now = clock.instant();
    if (invitation.statusToItsAccountAt(now) != ProfileManagerInvitationStatus.PENDING) {
      return Outcome.rejected(new ManagerRejections.InvitationNotPending());
    }

    if (!userAccountRepository.findById(identity.accountId()).map(this::isEligible).orElse(false)) {
      return Outcome.rejected(new ManagerRejections.RecipientNotEligible());
    }

    Outcome<Optional<ProfileManagerInvitation>, ManagerRejections.Accept> attempt =
        mutationTransactions.write(
            () -> {
              lockAcceptanceProfiles(invitation);
              if (!stillProposable(invitation)) {
                invitationLifecycle.tryInvalidatePending(
                    invitation.getId(), LeavingManagerProposals.INVITER_LEFT_REASON, now);
                return Optional.empty();
              }

              if (!invitationLifecycle.tryAcceptPending(
                  invitation.getId(), identity.accountId(), now)) {
                throw new MutationRejection(new ManagerRejections.InvitationNotPending());
              }

              if (!profileManagerRepository.tryGrantDirectManagement(
                  identity.accountId(), invitation.getProfileId())) {
                throw new MutationRejection(new ManagerRejections.AlreadyManager());
              }

              // Another invitation to this Account, sent to an address it had before, must not
              // outlive its becoming a manager (ADR 0024 §Invitations).
              invitationLifecycle.invalidatePendingByProfileIdAndRecipientAccountId(
                  invitation.getProfileId(), identity.accountId(), BECAME_MANAGER_REASON, now);

              audit(
                  identity,
                  SecurityAuditEntry.builder()
                      .operation("acceptManagerInvitation")
                      .resource(RESOURCE_PROFILE_ID, invitation.getProfileId())
                      .resource(RESOURCE_ACCOUNT_ID, identity.accountId()));
              return invitationRepository.findById(invitation.getId());
            },
            constraint ->
                CHK_RESTRICTED_AUTHORITY.equals(constraint)
                    ? Optional.of(new ManagerRejections.RecipientNotEligible())
                    : Optional.empty());
    return attempt.fold(
        accepted ->
            accepted
                .<Outcome<ProfileManagerInvitation, ManagerRejections.Accept>>map(Outcome::accepted)
                .orElseGet(() -> Outcome.rejected(new ManagerRejections.InvitationNotPending())),
        Outcome::rejected);
  }

  private void lockAcceptanceProfiles(ProfileManagerInvitation invitation) {
    // Lock both Profiles in UUID order to avoid deadlocks while rechecking the inviter's
    // eligibility.
    var invitationProfileId = invitation.getProfileId();
    var inviterProfileId =
        Optional.ofNullable(invitation.getInviterAccountId())
            .flatMap(userAccountRepository::findById)
            .map(UserAccount::getPersonalProfileId);
    var profileIds =
        inviterProfileId
            .map(profileId -> List.of(invitationProfileId, profileId))
            .orElseGet(() -> List.of(invitationProfileId));
    profileIds.stream()
        .distinct()
        .sorted()
        .forEach(profileId -> lockProfile(profileId, ManagerRejections.InvitationNotPending::new));
  }

  /** The named Account declines by the invitation's id. */
  public Outcome<ProfileManagerInvitation, ManagerRejections.Decline> declineManagerInvitation(
      AuthenticatedIdentity identity, UUID invitationId) {
    if (decidableBy(identity, invitationId, new Intent.DeclineManagerInvitation(invitationId))
        .isEmpty()) {
      return Outcome.rejected(new ManagerRejections.ManagerInvitationNotFound());
    }

    return mutationTransactions.write(
        () -> {
          if (!invitationLifecycle.tryDeclinePending(
              invitationId, identity.accountId(), clock.instant())) {
            throw new MutationRejection(new ManagerRejections.InvitationNotPending());
          }

          return invitationRepository.findById(invitationId).orElseThrow();
        },
        _ -> Optional.empty());
  }

  /**
   * The invitation, when the caller is the Account it names; empty for anyone else and for an id
   * that names no invitation, which answer alike.
   */
  private Optional<ProfileManagerInvitation> decidableBy(
      AuthenticatedIdentity identity, UUID invitationId, Intent.UnitIntent decision) {
    return invitationRepository
        .findById(invitationId)
        .filter(_ -> authorizationService.isAllowed(identity, decision));
  }

  public Outcome<UUID, ManagerRejections.Relinquish> relinquishProfileManagement(
      AuthenticatedIdentity identity, UUID profileId) {
    Optional<ManagerRejections.Relinquish> refusal =
        refusalOf(
            identity,
            new Intent.RelinquishProfileManagement(profileId),
            () -> mayViewProfile(identity, profileId),
            ManagerRejections.ProfileNotFound::new,
            Optional.empty());
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    return mutationTransactions.write(
        () -> {
          lockProfile(profileId, ManagerRejections.ProfileNotFound::new);
          if (!profileManagerRepository.tryRevokeDirectManagement(
              identity.accountId(), profileId)) {
            throw new MutationRejection(new ManagerRejections.ManagementAlreadyRemoved());
          }

          // An old invitation must not bring back the authority the Account gave up.
          invitationLifecycle.invalidatePendingByProfileIdAndRecipientAccountId(
              profileId, identity.accountId(), "named Account stopped managing", clock.instant());
          leavingManagerProposals.withdraw(profileId, identity.accountId(), clock.instant());
          return profileId;
        },
        constraint ->
            eligibleManagerRejection(constraint, ManagerRejections.EligibleManagerRequired::new));
  }

  public Outcome<UUID, ManagerRejections.Remove> removeProfileManager(
      AuthenticatedIdentity identity, UUID profileId, UUID managerAccountId) {
    Optional<ManagerRejections.Remove> refusal =
        refusalOf(
            identity,
            new Intent.RemoveProfileManager(profileId),
            () -> mayViewProfile(identity, profileId),
            ManagerRejections.ProfileNotFound::new,
            Optional.empty());
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    return mutationTransactions.write(
        () -> {
          lockProfile(profileId, ManagerRejections.ProfileNotFound::new);
          removeDisputedAuthority(identity, profileId, managerAccountId, "removeProfileManager");
          return profileId;
        },
        constraint ->
            eligibleManagerRejection(constraint, ManagerRejections.EligibleManagerRequired::new));
  }

  public Outcome<UUID, ManagerRejections.AdministrativelyGrant> administrativelyGrantProfileManager(
      AuthenticatedIdentity identity, UUID profileId, UUID accountId, String reason) {
    if (isBlank(reason)) {
      return Outcome.rejected(new ManagerRejections.ReasonRequired());
    }

    Optional<ManagerRejections.AdministrativelyGrant> refusal =
        refusalOf(
            identity,
            new Intent.AdministrativelyGrantProfileManager(profileId),
            () -> mayViewProfile(identity, profileId),
            ManagerRejections.ProfileNotFound::new,
            Optional.of(ManagerRejections.ReauthenticationRequired::new));
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    var recipient = userAccountRepository.findById(accountId);
    if (recipient.isEmpty()) {
      return Outcome.rejected(new ManagerRejections.RecipientNotFound());
    }

    if (!isEligible(recipient.get())) {
      return Outcome.rejected(new ManagerRejections.RecipientNotEligible());
    }

    var now = clock.instant();
    return mutationTransactions.write(
        () -> {
          lockProfile(profileId, ManagerRejections.ProfileNotFound::new);
          if (!profileManagerRepository.tryGrantDirectManagement(accountId, profileId)) {
            throw new MutationRejection(new ManagerRejections.AlreadyManager());
          }

          // The consent this grant makes redundant must not linger as a second live path.
          invitationLifecycle.invalidatePendingByProfileIdAndRecipientAccountId(
              profileId, accountId, "granted by override", now);
          audit(
              identity,
              SecurityAuditEntry.builder()
                  .operation("administrativelyGrantProfileManager")
                  .reason(reason)
                  .resource(RESOURCE_PROFILE_ID, profileId)
                  .resource(RESOURCE_ACCOUNT_ID, accountId));
          return profileId;
        },
        constraint ->
            CHK_RESTRICTED_AUTHORITY.equals(constraint)
                ? Optional.of(new ManagerRejections.RecipientNotEligible())
                : Optional.empty());
  }

  public Outcome<UUID, ManagerRejections.AdministrativelyRemove>
      administrativelyRemoveProfileManager(
          AuthenticatedIdentity identity, UUID profileId, UUID accountId, String reason) {
    if (isBlank(reason)) {
      return Outcome.rejected(new ManagerRejections.ReasonRequired());
    }

    Optional<ManagerRejections.AdministrativelyRemove> refusal =
        refusalOf(
            identity,
            new Intent.AdministrativelyRemoveProfileManager(profileId),
            () -> mayViewProfile(identity, profileId),
            ManagerRejections.ProfileNotFound::new,
            Optional.of(ManagerRejections.ReauthenticationRequired::new));
    if (refusal.isPresent()) {
      return Outcome.rejected(refusal.get());
    }

    return mutationTransactions.write(
        () -> {
          lockProfile(profileId, ManagerRejections.ProfileNotFound::new);
          removeDisputedAuthority(
              identity, profileId, accountId, "administrativelyRemoveProfileManager", reason);
          return profileId;
        },
        constraint ->
            eligibleManagerRejection(constraint, ManagerRejections.EligibleManagerRequired::new));
  }

  /**
   * A page of the Profile's pending manager invitations, newest first; hidden reads as empty. An
   * invitation invalidated because of what became of its invited Account still waits here for the
   * Profile's managers, and not for a ServerAdmin, who reads it as invalidated (ADR 0024).
   */
  public ListPage<ProfileManagerInvitation> pendingManagerInvitationsForProfile(
      AuthenticatedIdentity identity, UUID profileId, PageRequest request) {
    // A decision about a Profile that does not exist fails rather than denies.
    if (!profileRepository.existsById(profileId)
        || !authorizationService.isAllowed(
            identity, new Intent.ViewManagerInvitations(profileId))) {
      return ListPage.empty();
    }

    return Pager.page(
        profileManagerInvitationsList,
        new ProfileManagerInvitationsFilter(profileId, !readsAsServerAdmin(identity)),
        request);
  }

  /**
   * A page of the caller's own pending manager invitations, newest first. Empty for a device-bound
   * session and a disabled Account, which may not answer them.
   */
  public ListPage<ProfileManagerInvitation> pendingManagerInvitations(
      AuthenticatedIdentity identity, PageRequest request) {
    if (!authorizationService.isAllowed(identity, new Intent.ViewReceivedManagerInvitations())) {
      return ListPage.empty();
    }

    return Pager.page(receivedManagerInvitationsList, identity.accountId(), request);
  }

  /**
   * One invitation at any status, for its named Account, the managers of its Profile and
   * ServerAdmins; empty for everyone else and when no invitation has the id. Its named Account and
   * a ServerAdmin read every invalidation; the Profile's managers read an invitation invalidated
   * because of what became of its invited Account as still waiting (ADR 0024 §Read authorization).
   */
  public Optional<ManagerInvitationReading> managerInvitation(
      AuthenticatedIdentity identity, UUID invitationId) {
    return invitationRepository
        .findById(invitationId)
        .flatMap(invitation -> readingOf(identity, invitation));
  }

  private Optional<ManagerInvitationReading> readingOf(
      AuthenticatedIdentity identity, ProfileManagerInvitation invitation) {
    var now = clock.instant();
    if (authorizationService.isAllowed(
            identity, new Intent.ViewReceivedManagerInvitation(invitation.getId()))
        || readsAsServerAdmin(identity)) {
      return Optional.of(
          new ManagerInvitationReading(invitation, invitation.statusToItsAccountAt(now)));
    }

    if (invitation.getProfileId() != null
        && authorizationService.isAllowed(
            identity, new Intent.ViewManagerInvitations(invitation.getProfileId()))) {
      return Optional.of(new ManagerInvitationReading(invitation, invitation.statusAt(now)));
    }

    return Optional.empty();
  }

  // ServerAdmin authority, read live, is what viewing every Household takes.
  private boolean readsAsServerAdmin(AuthenticatedIdentity identity) {
    return authorizationService.isAllowed(identity, new Intent.ViewHouseholds());
  }

  private void removeDisputedAuthority(
      AuthenticatedIdentity identity, UUID profileId, UUID managerAccountId, String operation) {
    removeDisputedAuthority(identity, profileId, managerAccountId, operation, null);
  }

  private void removeDisputedAuthority(
      AuthenticatedIdentity identity,
      UUID profileId,
      UUID managerAccountId,
      String operation,
      String reason) {
    if (!profileManagerRepository.tryRevokeDirectManagement(managerAccountId, profileId)) {
      throw new MutationRejection(new ManagerRejections.NotAManager());
    }

    var now = clock.instant();
    // An older invitation naming the removed Account could silently restore what was disputed.
    invitationLifecycle.invalidatePendingByProfileIdAndRecipientAccountId(
        profileId, managerAccountId, "removal disputes the authority", now);
    leavingManagerProposals.withdraw(profileId, managerAccountId, clock.instant());
    audit(
        identity,
        SecurityAuditEntry.builder()
            .operation(operation)
            .reason(reason)
            .resource(RESOURCE_PROFILE_ID, profileId)
            .resource(RESOURCE_ACCOUNT_ID, managerAccountId));
  }

  private <R> void lockProfile(UUID profileId, Supplier<? extends R> vanished) {
    if (profileRepository.lockPolicyById(profileId).isEmpty()) {
      throw new MutationRejection(vanished.get());
    }
  }

  private Optional<ProfileManagerInvitation> resolvePresented(
      ManagerInvitationCodeCommand command) {
    var presented = opaqueCodes.parse(command.code());
    if (presented.isEmpty()) {
      return Optional.empty();
    }

    var invitation = invitationRepository.findByPublicId(presented.get().publicId()).orElse(null);
    try {
      return Optional.of(
          credentialAttempts.attempt(
              codeMetadata(invitation, command.ipAddress()),
              () -> {
                if (invitation == null
                    || !opaqueCodes.matches(presented.get(), invitation.getSecretDigest())
                    || invitation.statusToItsAccountAt(clock.instant())
                        != ProfileManagerInvitationStatus.PENDING) {
                  throw new InvalidOneTimeCodeException();
                }

                return invitation;
              }));
    } catch (InvalidOneTimeCodeException _) {
      return Optional.empty();
    }
  }

  private static CredentialAttemptMetadata codeMetadata(
      ProfileManagerInvitation invitation, String ipAddress) {
    return CredentialAttemptMetadata.builder()
        .kind(CredentialKind.PROFILE_MANAGER_INVITATION_CODE)
        .credentialId(invitation == null ? null : invitation.getId())
        .ipAddress(ipAddress)
        .build();
  }

  @Builder
  public record ManagerInvitationCodeCommand(String code, @NonNull String ipAddress) {

    @Override
    public String toString() {
      return "ManagerInvitationCodeCommand[code=REDACTED, ipAddress=%s]".formatted(ipAddress);
    }

    public static class ManagerInvitationCodeCommandBuilder {

      @Override
      public String toString() {
        return "ManagerInvitationCodeCommandBuilder[REDACTED]";
      }
    }
  }

  /** The invitation stands only while its inviter could still propose it (ADR 0024). */
  private boolean stillProposable(ProfileManagerInvitation invitation) {
    var inviterId = invitation.getInviterAccountId();
    if (inviterId == null) {
      return false;
    }

    return stillProposable(inviterId, invitation.getProfileId());
  }

  private boolean stillProposable(UUID inviterId, UUID profileId) {
    return userAccountRepository
        .findById(inviterId)
        .filter(this::isEligible)
        .filter(account -> alreadyManages(account, profileId))
        .isPresent();
  }

  private boolean isEligible(UserAccount account) {
    return profileRepository
        .findById(account.getPersonalProfileId())
        .filter(profile -> !profile.isRestricted())
        .isPresent();
  }

  private boolean alreadyManages(UserAccount account, UUID profileId) {
    return profileManagerRepository.existsByAccountIdAndProfileId(account.getId(), profileId)
        || profileId.equals(account.getPersonalProfileId());
  }

  private void audit(
      AuthenticatedIdentity identity, SecurityAuditEntry.SecurityAuditEntryBuilder entry) {
    securityAuditEventRepository.append(entry.actorAccountId(identity.accountId()).build());
  }

  private static <R> Optional<R> eligibleManagerRejection(
      String constraint, Supplier<R> rejection) {
    return CHK_ELIGIBLE_MANAGER.equals(constraint)
        ? Optional.of(rejection.get())
        : Optional.empty();
  }

  private <R> Optional<R> refusalOf(
      AuthenticatedIdentity identity,
      Intent.UnitIntent intent,
      BooleanSupplier mayView,
      Supplier<? extends R> denied,
      Optional<? extends Supplier<? extends R>> reauthenticationRequired) {
    return AuthorizationRefusal.from(
        authorizationService.decide(identity, intent),
        new AuthorizationRefusal.Response<>(mayView, denied, reauthenticationRequired));
  }

  /** The invitation is visible to its parties and to whoever may view the Profile's admin. */
  // Whoever may read the invitation learns that it exists: the read rule of managerInvitation.
  private boolean mayViewInvitation(AuthenticatedIdentity identity, UUID invitationId) {
    return managerInvitation(identity, invitationId).isPresent();
  }

  private boolean mayViewProfile(AuthenticatedIdentity identity, UUID profileId) {
    return authorizationService.isAllowed(
        identity, new Intent.ViewProfileAdministration(profileId));
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /** The raw code rides here once; it is never stored, logged, or queryable again. */
  public record IssuedManagerInvitation(ProfileManagerInvitation invitation, String code) {

    @Override
    public String toString() {
      return "IssuedManagerInvitation[invitation=%s, code=REDACTED]".formatted(invitation.getId());
    }
  }
}
