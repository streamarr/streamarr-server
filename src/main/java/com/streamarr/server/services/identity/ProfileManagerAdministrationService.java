package com.streamarr.server.services.identity;

import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.SecurityAuditEntry;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.SecurityAuditEventRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.CredentialGuessThrottle;
import com.streamarr.server.services.auth.OpaqueOneTimeCodes;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.MutationRejection;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationService;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/**
 * Direct ProfileManagers (ADR 0024 §ProfileManager): durable authority granted by invitation and
 * consent, or by a fresh-reauthenticated ServerAdmin override. Accept, GRANT, and REMOVE share one
 * serialization boundary — the Profile row lock — and each transition is a conditional statement
 * with exactly one winner. Losing management invalidates the leaver's outstanding proposals so a
 * stale invitation or offer can never restore disputed authority. Codes follow the opaque
 * publicId.secret discipline: throttled per publicId, digest-compared, one deliberate answer.
 */
@Service
@RequiredArgsConstructor
public class ProfileManagerAdministrationService {

  private static final String CHK_RESTRICTED_AUTHORITY =
      "chk_restricted_account_holds_no_authority";
  private static final String CHK_HOME_ANCHOR = "chk_profile_home_anchor";
  private static final String REPLACED_REASON = "replaced by a newer invitation";
  private static final String INVITER_LEFT_REASON = "inviting manager lost management";

  private final AuthorizationService authorizationService;
  private final ProfileManagerInvitationRepository invitationRepository;
  private final ProfileManagerRepository profileManagerRepository;
  private final ProfileRepository profileRepository;
  private final UserAccountRepository userAccountRepository;
  private final ProfileHouseholdShareRepository shareRepository;
  private final SecurityAuditEventRepository securityAuditEventRepository;
  private final OpaqueOneTimeCodes opaqueCodes;
  private final CredentialGuessThrottle throttle;
  private final CredentialCodeProperties properties;
  private final MutationTransactions mutationTransactions;
  private final PaginationService paginationService;
  private final Clock clock;

  public Outcome<IssuedManagerInvitation, ManagerRejections.Invite> inviteProfileManager(
      AuthenticatedIdentity identity, UUID profileId, UUID recipientAccountId) {
    var refusal =
        refusalOf(
            identity,
            new Intent.InviteProfileManager(profileId),
            () -> mayViewProfile(identity, profileId),
            ManagerRejections.ProfileNotFound::new,
            null);
    if (refusal.isPresent()) {
      return Outcome.rejected((ManagerRejections.Invite) refusal.get());
    }

    var recipient = userAccountRepository.findById(recipientAccountId);
    if (recipient.isEmpty()) {
      return Outcome.rejected(new ManagerRejections.RecipientNotFound());
    }

    if (!isEligible(recipient.get())) {
      return Outcome.rejected(new ManagerRejections.RecipientNotEligible());
    }

    if (alreadyManages(recipient.get(), profileId)) {
      return Outcome.rejected(new ManagerRejections.AlreadyManager());
    }

    var profile = profileRepository.findById(profileId).orElseThrow();
    var inviterName =
        userAccountRepository
            .findById(identity.accountId())
            .map(UserAccount::getDisplayName)
            .orElse("");
    var issued = opaqueCodes.issue();
    var now = clock.instant();
    return mutationTransactions.write(
        () -> {
          invitationRepository.invalidatePendingForProfileAndRecipient(
              profileId, recipientAccountId, REPLACED_REASON, now);
          var invitation =
              invitationRepository.saveAndFlush(
                  ProfileManagerInvitation.builder()
                      .profileId(profileId)
                      .profileName(profile.getName())
                      .inviterAccountId(identity.accountId())
                      .inviterDisplayName(inviterName)
                      .recipientAccountId(recipientAccountId)
                      .recipientEmail(recipient.get().getEmail())
                      .expiresAt(now.plus(properties.invitationTtl()))
                      .publicId(issued.publicId())
                      .secretDigest(issued.digest())
                      .build());
          return new IssuedManagerInvitation(invitation, issued.code());
        },
        _ -> Optional.empty());
  }

  public Outcome<ProfileManagerInvitation, ManagerRejections.Cancel> cancelManagerInvitation(
      AuthenticatedIdentity identity, UUID invitationId) {
    var refusal =
        refusalOf(
            identity,
            new Intent.CancelManagerInvitation(invitationId),
            () -> mayViewInvitation(identity, invitationId),
            ManagerRejections.ManagerInvitationNotFound::new,
            null);
    if (refusal.isPresent()) {
      return Outcome.rejected((ManagerRejections.Cancel) refusal.get());
    }

    return mutationTransactions.write(
        () -> {
          if (!invitationRepository.tryDecide(
              invitationId, ProfileManagerInvitationStatus.CANCELED, clock.instant())) {
            throw new MutationRejection(new ManagerRejections.InvitationNotPending());
          }

          return invitationRepository.findById(invitationId).orElseThrow();
        },
        _ -> Optional.empty());
  }

  public Outcome<ProfileManagerInvitation, ManagerRejections.Accept> acceptManagerInvitation(
      AuthenticatedIdentity identity, String rawCode) {
    var resolved = resolvePresented(rawCode);
    if (resolved.isEmpty()) {
      return Outcome.rejected(new ManagerRejections.ManagerInvitationNotFound());
    }

    var invitation = resolved.get();
    if (!(authorizationService.decide(
            identity, new Intent.AcceptManagerInvitation(invitation.getId()))
        instanceof Decision.Allowed<?>)) {
      // Whoever is not the named recipient learns nothing beyond the one deliberate answer.
      return Outcome.rejected(new ManagerRejections.ManagerInvitationNotFound());
    }

    if (!userAccountRepository.findById(identity.accountId()).map(this::isEligible).orElse(false)) {
      return Outcome.rejected(new ManagerRejections.RecipientNotEligible());
    }

    var now = clock.instant();
    if (!stillProposable(invitation)) {
      mutationTransactions.write(
          () ->
              invitationRepository.invalidatePendingForProfileAndRecipient(
                  invitation.getProfileId(),
                  invitation.getRecipientAccountId(),
                  INVITER_LEFT_REASON,
                  now),
          _ -> Optional.empty());
      return Outcome.rejected(new ManagerRejections.ManagerInvitationNotFound());
    }

    return mutationTransactions.write(
        () -> {
          lockProfile(invitation.getProfileId(), ManagerRejections.ManagerInvitationNotFound::new);
          if (!invitationRepository.tryDecide(
              invitation.getId(), ProfileManagerInvitationStatus.ACCEPTED, now)) {
            throw new MutationRejection(new ManagerRejections.ManagerInvitationNotFound());
          }

          if (!profileManagerRepository.tryGrant(identity.accountId(), invitation.getProfileId())) {
            throw new MutationRejection(new ManagerRejections.AlreadyManager());
          }

          audit(
              identity,
              SecurityAuditEntry.builder()
                  .operation("acceptManagerInvitation")
                  .resource("profileId", invitation.getProfileId())
                  .resource("accountId", identity.accountId()));
          return invitationRepository.findById(invitation.getId()).orElseThrow();
        },
        constraint ->
            CHK_RESTRICTED_AUTHORITY.equals(constraint)
                ? Optional.of(new ManagerRejections.RecipientNotEligible())
                : Optional.empty());
  }

  public Outcome<ProfileManagerInvitation, ManagerRejections.Decline> declineManagerInvitation(
      AuthenticatedIdentity identity, String rawCode) {
    var resolved = resolvePresented(rawCode);
    if (resolved.isEmpty()) {
      return Outcome.rejected(new ManagerRejections.ManagerInvitationNotFound());
    }

    var invitation = resolved.get();
    if (!(authorizationService.decide(
            identity, new Intent.DeclineManagerInvitation(invitation.getId()))
        instanceof Decision.Allowed<?>)) {
      return Outcome.rejected(new ManagerRejections.ManagerInvitationNotFound());
    }

    return mutationTransactions.write(
        () -> {
          if (!invitationRepository.tryDecide(
              invitation.getId(), ProfileManagerInvitationStatus.DECLINED, clock.instant())) {
            throw new MutationRejection(new ManagerRejections.ManagerInvitationNotFound());
          }

          return invitationRepository.findById(invitation.getId()).orElseThrow();
        },
        _ -> Optional.empty());
  }

  public Outcome<UUID, ManagerRejections.Relinquish> relinquishProfileManagement(
      AuthenticatedIdentity identity, UUID profileId) {
    var refusal =
        refusalOf(
            identity,
            new Intent.RelinquishProfileManagement(profileId),
            () -> mayViewProfile(identity, profileId),
            ManagerRejections.ProfileNotFound::new,
            null);
    if (refusal.isPresent()) {
      return Outcome.rejected((ManagerRejections.Relinquish) refusal.get());
    }

    return mutationTransactions.write(
        () -> {
          lockProfile(profileId, ManagerRejections.ProfileNotFound::new);
          if (!profileManagerRepository.tryRemove(identity.accountId(), profileId)) {
            throw new MutationRejection(new ManagerRejections.ManagementAlreadyRemoved());
          }

          invalidateLeaversProposals(profileId, identity.accountId());
          return profileId;
        },
        constraint -> anchorRejection(constraint, ManagerRejections.ManagerAnchorRequired::new));
  }

  public Outcome<UUID, ManagerRejections.Remove> removeProfileManager(
      AuthenticatedIdentity identity, UUID profileId, UUID managerAccountId) {
    var refusal =
        refusalOf(
            identity,
            new Intent.RemoveProfileManager(profileId),
            () -> mayViewProfile(identity, profileId),
            ManagerRejections.ProfileNotFound::new,
            null);
    if (refusal.isPresent()) {
      return Outcome.rejected((ManagerRejections.Remove) refusal.get());
    }

    return mutationTransactions.write(
        () -> {
          lockProfile(profileId, ManagerRejections.ProfileNotFound::new);
          removeDisputedAuthority(identity, profileId, managerAccountId, "removeProfileManager");
          return profileId;
        },
        constraint -> anchorRejection(constraint, ManagerRejections.ManagerAnchorRequired::new));
  }

  public Outcome<UUID, ManagerRejections.OverrideGrant> grantProfileManagerOverride(
      AuthenticatedIdentity identity, UUID profileId, UUID accountId, String reason) {
    if (isBlank(reason)) {
      return Outcome.rejected(new ManagerRejections.ReasonRequired());
    }

    var refusal =
        refusalOf(
            identity,
            new Intent.OverrideProfileManager(profileId),
            () -> mayViewProfile(identity, profileId),
            ManagerRejections.ProfileNotFound::new,
            ManagerRejections.ReauthenticationRequired::new);
    if (refusal.isPresent()) {
      return Outcome.rejected((ManagerRejections.OverrideGrant) refusal.get());
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
          if (!profileManagerRepository.tryGrant(accountId, profileId)) {
            throw new MutationRejection(new ManagerRejections.AlreadyManager());
          }

          // The consent this grant makes redundant must not linger as a second live path.
          invitationRepository.invalidatePendingForProfileAndRecipient(
              profileId, accountId, "granted by override", now);
          audit(
              identity,
              SecurityAuditEntry.builder()
                  .operation("grantProfileManagerOverride")
                  .reason(reason)
                  .resource("profileId", profileId)
                  .resource("accountId", accountId));
          return profileId;
        },
        constraint ->
            CHK_RESTRICTED_AUTHORITY.equals(constraint)
                ? Optional.of(new ManagerRejections.RecipientNotEligible())
                : Optional.empty());
  }

  public Outcome<UUID, ManagerRejections.OverrideRemove> removeProfileManagerOverride(
      AuthenticatedIdentity identity, UUID profileId, UUID accountId, String reason) {
    if (isBlank(reason)) {
      return Outcome.rejected(new ManagerRejections.ReasonRequired());
    }

    var refusal =
        refusalOf(
            identity,
            new Intent.OverrideProfileManager(profileId),
            () -> mayViewProfile(identity, profileId),
            ManagerRejections.ProfileNotFound::new,
            ManagerRejections.ReauthenticationRequired::new);
    if (refusal.isPresent()) {
      return Outcome.rejected((ManagerRejections.OverrideRemove) refusal.get());
    }

    return mutationTransactions.write(
        () -> {
          lockProfile(profileId, ManagerRejections.ProfileNotFound::new);
          removeDisputedAuthority(
              identity, profileId, accountId, "removeProfileManagerOverride", reason);
          return profileId;
        },
        constraint -> anchorRejection(constraint, ManagerRejections.ManagerAnchorRequired::new));
  }

  /** Pending invitations for the Profile, visible only to direct managers and ServerAdmin. */
  public MediaPage<ProfileManagerInvitation> managerInvitations(
      AuthenticatedIdentity identity, UUID profileId, KeysetPaginationOptions options) {
    if (!(authorizationService.decide(identity, new Intent.ViewManagerInvitations(profileId))
        instanceof Decision.Allowed<?>)) {
      return page(List.of(), options);
    }

    return page(
        unexpired(
            invitationRepository.findByProfileIdAndStatus(
                profileId, ProfileManagerInvitationStatus.PENDING)),
        options);
  }

  /** The caller's own pending invitations; possession of the seat is the visibility. */
  public MediaPage<ProfileManagerInvitation> pendingManagerInvitations(
      AuthenticatedIdentity identity, KeysetPaginationOptions options) {
    return page(
        unexpired(
            invitationRepository.findByRecipientAccountIdAndStatus(
                identity.accountId(), ProfileManagerInvitationStatus.PENDING)),
        options);
  }

  private List<ProfileManagerInvitation> unexpired(List<ProfileManagerInvitation> pending) {
    var now = clock.instant();
    return pending.stream().filter(invitation -> invitation.getExpiresAt().isAfter(now)).toList();
  }

  private MediaPage<ProfileManagerInvitation> page(
      List<ProfileManagerInvitation> invitations, KeysetPaginationOptions options) {
    var items =
        invitations.stream()
            .sorted(
                Comparator.comparing(
                        ProfileManagerInvitation::getCreatedOn,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(ProfileManagerInvitation::getId))
            .map(invitation -> new PageItem<>(invitation, invitation.getCreatedOn()))
            .toList();
    return paginationService.buildKeysetPage(items, options, ProfileManagerInvitation::getId);
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
    if (!profileManagerRepository.tryRemove(managerAccountId, profileId)) {
      throw new MutationRejection(new ManagerRejections.NotAManager());
    }

    var now = clock.instant();
    // An older invitation naming the removed Account could silently restore what was disputed.
    invitationRepository.invalidatePendingForProfileAndRecipient(
        profileId, managerAccountId, "removal disputes the authority", now);
    invalidateLeaversProposals(profileId, managerAccountId);
    audit(
        identity,
        SecurityAuditEntry.builder()
            .operation(operation)
            .reason(reason)
            .resource("profileId", profileId)
            .resource("accountId", managerAccountId));
  }

  private void invalidateLeaversProposals(UUID profileId, UUID leaverAccountId) {
    var now = clock.instant();
    invitationRepository.invalidatePendingInvitedBy(
        leaverAccountId, profileId, INVITER_LEFT_REASON, now);
    shareRepository.invalidatePendingSharesOfferedBy(
        profileId, leaverAccountId, "offering manager lost management", now);
  }

  private void lockProfile(UUID profileId, Supplier<Object> vanished) {
    if (profileRepository.lockPolicyById(profileId).isEmpty()) {
      throw new MutationRejection(vanished.get());
    }
  }

  /**
   * Resolves a presented code: throttled per publicId before any lookup, constant-time digest
   * comparison, PENDING and unexpired by predicate. Empty is the one deliberate answer.
   */
  private Optional<ProfileManagerInvitation> resolvePresented(String rawCode) {
    var presented = opaqueCodes.parse(rawCode);
    if (presented.isEmpty()) {
      return Optional.empty();
    }

    throttle.registerCodeGuess(presented.get().publicId());
    return invitationRepository
        .findByPublicId(presented.get().publicId())
        .filter(invitation -> opaqueCodes.matches(presented.get(), invitation.getSecretDigest()))
        .filter(invitation -> invitation.getStatus() == ProfileManagerInvitationStatus.PENDING)
        .filter(invitation -> invitation.getExpiresAt().isAfter(clock.instant()));
  }

  /** The invitation stands only while its inviter could still propose it (ADR 0024). */
  private boolean stillProposable(ProfileManagerInvitation invitation) {
    var inviterId = invitation.getInviterAccountId();
    if (inviterId == null) {
      return false;
    }

    return userAccountRepository
        .findById(inviterId)
        .filter(this::isEligible)
        .filter(account -> alreadyManages(account, invitation.getProfileId()))
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

  private static <R> Optional<R> anchorRejection(String constraint, Supplier<R> anchor) {
    return CHK_HOME_ANCHOR.equals(constraint) ? Optional.of(anchor.get()) : Optional.empty();
  }

  private Optional<Object> refusalOf(
      AuthenticatedIdentity identity,
      Intent.UnitIntent intent,
      BooleanSupplier mayView,
      Supplier<Object> denied,
      Supplier<Object> reauthenticationRequired) {
    return switch (authorizationService.decide(identity, intent)) {
      case Decision.Allowed<?> _ -> Optional.empty();
      case Decision.Failed<?> _ -> throw new AuthorizationUnavailableException();
      case Decision.Denied<?>(var reason) ->
          switch (reason) {
            case REAUTHENTICATION_REQUIRED -> Optional.of(reauthenticationRequired.get());
            case POLICY -> {
              if (mayView.getAsBoolean()) {
                throw new AccessDeniedException("Not allowed.");
              }

              yield Optional.of(denied.get());
            }
          };
    };
  }

  /** The invitation is visible to its parties and to whoever may view the Profile's admin. */
  private boolean mayViewInvitation(AuthenticatedIdentity identity, UUID invitationId) {
    return invitationRepository
        .findById(invitationId)
        .map(
            invitation ->
                identity.accountId().equals(invitation.getRecipientAccountId())
                    || identity.accountId().equals(invitation.getInviterAccountId())
                    || (invitation.getProfileId() != null
                        && mayViewProfile(identity, invitation.getProfileId())))
        .orElse(false);
  }

  private boolean mayViewProfile(AuthenticatedIdentity identity, UUID profileId) {
    return authorizationService.decide(identity, new Intent.ViewProfileAdministration(profileId))
        instanceof Decision.Allowed<?>;
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
