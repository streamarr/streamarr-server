package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.config.security.AuthThrottleProperties;
import com.streamarr.server.config.security.CredentialCodeProperties;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.CredentialGuessThrottle;
import com.streamarr.server.services.auth.OpaqueOneTimeCodes;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The direct-manager lifecycle over fakes: invitation and consent, one winner per transition, the
 * override boundary, and the invalidation rules that keep stale proposals from restoring disputed
 * authority.
 */
@Tag("UnitTest")
@DisplayName("Profile Manager Administration Service Tests")
class ProfileManagerAdministrationServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeUserAccountRepository accounts = new FakeUserAccountRepository(shares);
  private final FakeProfileManagerRepository managers = new FakeProfileManagerRepository();
  private final FakeProfileManagerInvitationRepository invitations =
      new FakeProfileManagerInvitationRepository();
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final ProfileManagerAdministrationService service =
      new ProfileManagerAdministrationService(
          authorization,
          invitations,
          managers,
          profiles,
          accounts,
          shares,
          audit,
          new OpaqueOneTimeCodes(),
          new CredentialGuessThrottle(
              AuthThrottleProperties.builder()
                  .maxAttempts(5)
                  .window(Duration.ofMinutes(15))
                  .build(),
              clock),
          CredentialCodeProperties.builder()
              .invitationTtl(Duration.ofDays(7))
              .replacementLockTimeout(Duration.ofSeconds(5))
              .build(),
          new MutationTransactions(
              new FakeTransactionManager(), new ConstraintViolationTranslator()),
          new PaginationService(),
          clock);

  private UserAccount inviter;
  private UserAccount recipient;
  private Profile orphan;

  @BeforeEach
  void setUp() {
    inviter = eligibleAccount(authorization.currentIdentity().accountId());
    recipient = eligibleAccount(UUID.randomUUID());
    orphan = profiles.save(ProfileFixture.defaultProfileBuilder().name("Joe").build());
    managers.tryGrant(inviter.getId(), orphan.getId());
  }

  @Test
  @DisplayName("Should replace the older pending invitation when the same pair is invited again")
  void shouldReplaceOlderPendingInvitationWhenSamePairIsInvitedAgain() {
    var first = issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getId()));
    assertThat(first.code()).isNotBlank();
    assertThat(first.invitation().getProfileName()).isEqualTo("Joe");
    assertThat(first.invitation().getInviterDisplayName()).isEqualTo(inviter.getDisplayName());
    assertThat(first.toString()).doesNotContain(first.code());

    var second =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getId()));
    assertThat(invitations.findById(first.invitation().getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(second.invitation().getStatus()).isEqualTo(ProfileManagerInvitationStatus.PENDING);
  }

  @Test
  @DisplayName("Should cancel a pending invitation when its inviter acts")
  void shouldCancelPendingInvitationWhenInviterActs() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getId()));

    var canceled = service.cancelManagerInvitation(identity(), issued.invitation().getId());

    assertThat(canceled).isInstanceOf(Outcome.Accepted.class);
    assertThat(invitations.findById(issued.invitation().getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.CANCELED);
    assertThat(
            rejectionOf(service.cancelManagerInvitation(identity(), issued.invitation().getId())))
        .isInstanceOf(ManagerRejections.InvitationNotPending.class);
  }

  @Test
  @DisplayName(
      "Should reject an invitation when the recipient is missing, ineligible, or already a manager")
  void shouldRejectInvitationWhenRecipientIsMissingIneligibleOrAlreadyManager() {
    assertThat(
            rejectionOf(
                service.inviteProfileManager(identity(), orphan.getId(), UUID.randomUUID())))
        .isInstanceOf(ManagerRejections.RecipientNotFound.class);

    var restricted = accounts.save(AccountFixture.defaultAccountBuilder().build());
    profiles.save(ProfileFixture.kidProfileBuilder().id(restricted.getPersonalProfileId()).build());
    assertThat(
            rejectionOf(
                service.inviteProfileManager(identity(), orphan.getId(), restricted.getId())))
        .isInstanceOf(ManagerRejections.RecipientNotEligible.class);

    assertThat(
            rejectionOf(service.inviteProfileManager(identity(), orphan.getId(), inviter.getId())))
        .isInstanceOf(ManagerRejections.AlreadyManager.class);
  }

  @Test
  @DisplayName("Should return Profile not found when the caller cannot view the Profile")
  void shouldReturnProfileNotFoundWhenCallerCannotViewProfile() {
    authorization.denyAll();
    assertThat(
            rejectionOf(
                service.inviteProfileManager(identity(), orphan.getId(), recipient.getId())))
        .isInstanceOf(ManagerRejections.ProfileNotFound.class);
  }

  @Test
  @DisplayName("Should allow only one decision when a pending invitation is presented again")
  void shouldAllowOnlyOneDecisionWhenPendingInvitationIsPresentedAgain() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getId()));

    var accepted = service.acceptManagerInvitation(recipientIdentity(), issued.code());
    assertThat(accepted).isInstanceOf(Outcome.Accepted.class);
    assertThat(managers.existsByAccountIdAndProfileId(recipient.getId(), orphan.getId())).isTrue();
    assertThat(audit.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.operation()).isEqualTo("acceptManagerInvitation");
              assertThat(entry.actorAccountId()).isEqualTo(recipient.getId());
              assertThat(entry.reason()).isNull();
              assertThat(entry.resources())
                  .containsEntry("profileId", orphan.getId())
                  .containsEntry("accountId", recipient.getId());
            });

    assertThat(rejectionOf(service.acceptManagerInvitation(recipientIdentity(), issued.code())))
        .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
    assertThat(
            rejectionOf(service.cancelManagerInvitation(identity(), issued.invitation().getId())))
        .isInstanceOf(ManagerRejections.InvitationNotPending.class);
  }

  @Test
  @DisplayName("Should return a uniform miss and throttle when acceptance codes are invalid")
  void shouldReturnUniformMissAndThrottleWhenAcceptanceCodesAreInvalid() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getId()));

    assertThat(rejectionOf(service.acceptManagerInvitation(recipientIdentity(), "garbage")))
        .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
    assertThat(rejectionOf(service.acceptManagerInvitation(recipientIdentity(), "unknown.secret")))
        .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);

    var publicId = issued.invitation().getPublicId();
    for (var attempt = 0; attempt < 5; attempt++) {
      var guess = publicId + ".guess-" + attempt;
      assertThat(rejectionOf(service.acceptManagerInvitation(recipientIdentity(), guess)))
          .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
    }

    var throttled = issued.code();
    var recipientIdentity = recipientIdentity();
    assertThatThrownBy(() -> service.acceptManagerInvitation(recipientIdentity, throttled))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName("Should invalidate the invitation when the inviting manager loses management")
  void shouldInvalidateInvitationWhenInvitingManagerLosesManagement() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getId()));
    managers.tryRemove(inviter.getId(), orphan.getId());

    assertThat(rejectionOf(service.acceptManagerInvitation(recipientIdentity(), issued.code())))
        .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
    assertThat(invitations.findById(issued.invitation().getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
  }

  @Test
  @DisplayName("Should refuse acceptance when the recipient became ineligible")
  void shouldRefuseAcceptanceWhenRecipientBecameIneligible() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getId()));
    profiles
        .findById(recipient.getPersonalProfileId())
        .orElseThrow()
        .setMaximumAllowedRatingAge(12);

    assertThat(rejectionOf(service.acceptManagerInvitation(recipientIdentity(), issued.code())))
        .isInstanceOf(ManagerRejections.RecipientNotEligible.class);
  }

  @Test
  @DisplayName("Should allow a single decline when the caller is the named recipient")
  void shouldAllowSingleDeclineWhenCallerIsNamedRecipient() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getId()));

    // A different authenticated holder of the code learns nothing beyond the one answer;
    // Cedar's recipient policy is stubbed as the denial here and proven in the policy tests.
    authorization.decideWith(
        intent ->
            intent instanceof Intent.DeclineManagerInvitation
                ? new Decision.Denied<>(Decision.DenialReason.POLICY)
                : new Decision.Allowed<>(AuthorizationUnit.INSTANCE));
    assertThat(rejectionOf(service.declineManagerInvitation(identity(), issued.code())))
        .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);

    authorization.allowAll();
    var declined = service.declineManagerInvitation(recipientIdentity(), issued.code());
    assertThat(declined).isInstanceOf(Outcome.Accepted.class);
    assertThat(rejectionOf(service.declineManagerInvitation(recipientIdentity(), issued.code())))
        .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
    assertThat(managers.existsByAccountIdAndProfileId(recipient.getId(), orphan.getId())).isFalse();
  }

  @Test
  @DisplayName("Should invalidate an invitation when its inviter only supervises the Profile")
  void shouldInvalidateInvitationWhenInviterOnlySupervisesProfile() {
    var kid = profiles.save(ProfileFixture.kidProfileBuilder().name("Kid").build());
    shares.share(kid.getId(), inviter.getHouseholdId(), false);
    inviter.setHouseholdRole(HouseholdRole.ADMIN);
    accounts.save(inviter);
    var issued = issued(service.inviteProfileManager(identity(), kid.getId(), recipient.getId()));
    // Share-derived supervision ends with the share and cannot keep portable authority standing.
    managers.tryRemove(inviter.getId(), kid.getId());

    assertThat(rejectionOf(service.acceptManagerInvitation(recipientIdentity(), issued.code())))
        .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
    assertThat(invitations.findById(issued.invitation().getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
  }

  @Test
  @DisplayName("Should reject an override grant when the Account does not exist")
  void shouldRejectOverrideGrantWhenAccountDoesNotExist() {
    assertThat(
            rejectionOf(
                service.grantProfileManagerOverride(
                    identity(), orphan.getId(), UUID.randomUUID(), "support")))
        .isInstanceOf(ManagerRejections.RecipientNotFound.class);
  }

  @Test
  @DisplayName("Should invalidate the redundant invitation when an override grant succeeds once")
  void shouldInvalidateRedundantInvitationWhenOverrideGrantSucceedsOnce() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getId()));

    var granted =
        service.grantProfileManagerOverride(
            identity(), orphan.getId(), recipient.getId(), "support");
    assertThat(granted).isInstanceOf(Outcome.Accepted.class);
    assertThat(managers.existsByAccountIdAndProfileId(recipient.getId(), orphan.getId())).isTrue();
    assertThat(invitations.findById(issued.invitation().getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(audit.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.operation()).isEqualTo("grantProfileManagerOverride");
              assertThat(entry.actorAccountId()).isEqualTo(inviter.getId());
              assertThat(entry.reason()).isEqualTo("support");
              assertThat(entry.resources())
                  .containsEntry("profileId", orphan.getId())
                  .containsEntry("accountId", recipient.getId());
            });

    assertThat(
            rejectionOf(
                service.grantProfileManagerOverride(
                    identity(), orphan.getId(), recipient.getId(), "again")))
        .isInstanceOf(ManagerRejections.AlreadyManager.class);
  }

  @Test
  @DisplayName("Should invalidate restorable proposals when an override removal succeeds once")
  void shouldInvalidateRestorableProposalsWhenOverrideRemovalSucceedsOnce() {
    managers.tryGrant(recipient.getId(), orphan.getId());
    var restorable =
        invitations.save(pendingInvitation(orphan.getId(), recipient.getId(), inviter.getId()));
    var offered =
        shares.save(
            ProfileHouseholdShare.builder()
                .profileId(orphan.getId())
                .householdId(UUID.randomUUID())
                .status(ProfileShareStatus.PENDING)
                .offeredByAccountId(recipient.getId())
                .expiresAt(NOW.plusSeconds(3600))
                .build());

    var removed =
        service.removeProfileManagerOverride(
            identity(), orphan.getId(), recipient.getId(), "abuse");
    assertThat(removed).isInstanceOf(Outcome.Accepted.class);
    assertThat(managers.existsByAccountIdAndProfileId(recipient.getId(), orphan.getId())).isFalse();
    assertThat(invitations.findById(restorable.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(shares.findById(offered.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.INVALIDATED);
    assertThat(shares.findById(offered.getId()).orElseThrow().getLastModifiedOn()).isEqualTo(NOW);
    assertThat(audit.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.operation()).isEqualTo("removeProfileManagerOverride");
              assertThat(entry.actorAccountId()).isEqualTo(inviter.getId());
              assertThat(entry.reason()).isEqualTo("abuse");
              assertThat(entry.resources())
                  .containsEntry("profileId", orphan.getId())
                  .containsEntry("accountId", recipient.getId());
            });

    assertThat(
            rejectionOf(
                service.removeProfileManagerOverride(
                    identity(), orphan.getId(), recipient.getId(), "again")))
        .isInstanceOf(ManagerRejections.NotAManager.class);
  }

  @Test
  @DisplayName("Should invalidate the leaver's proposals when a manager relinquishes once")
  void shouldInvalidateLeaversProposalsWhenManagerRelinquishesOnce() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getId()));

    var relinquished = service.relinquishProfileManagement(identity(), orphan.getId());
    assertThat(relinquished).isInstanceOf(Outcome.Accepted.class);
    assertThat(managers.existsByAccountIdAndProfileId(inviter.getId(), orphan.getId())).isFalse();
    assertThat(invitations.findById(issued.invitation().getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);

    assertThat(rejectionOf(service.relinquishProfileManagement(identity(), orphan.getId())))
        .isInstanceOf(ManagerRejections.ManagementAlreadyRemoved.class);
  }

  @Test
  @DisplayName(
      "Should remove a direct manager when the sovereign Account acts on its Personal Profile")
  void shouldRemoveDirectManagerWhenSovereignAccountActsOnPersonalProfile() {
    var personal =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().id(inviter.getPersonalProfileId()).build());
    managers.tryGrant(recipient.getId(), personal.getId());

    var removed = service.removeProfileManager(identity(), personal.getId(), recipient.getId());
    assertThat(removed).isInstanceOf(Outcome.Accepted.class);
    assertThat(managers.existsByAccountIdAndProfileId(recipient.getId(), personal.getId()))
        .isFalse();

    assertThat(
            rejectionOf(
                service.removeProfileManager(identity(), personal.getId(), recipient.getId())))
        .isInstanceOf(ManagerRejections.NotAManager.class);
  }

  @Test
  @DisplayName("Should validate the reason before the ceremony when an override is requested")
  void shouldValidateReasonBeforeCeremonyWhenOverrideIsRequested() {
    assertThat(
            rejectionOf(
                service.grantProfileManagerOverride(
                    identity(), orphan.getId(), recipient.getId(), " ")))
        .isInstanceOf(ManagerRejections.ReasonRequired.class);
    assertThat(authorization.recordedIntents()).isEmpty();

    authorization.decideWith(
        intent ->
            intent instanceof Intent.OverrideProfileManager
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : new Decision.Allowed<>(AuthorizationUnit.INSTANCE));
    assertThat(
            rejectionOf(
                service.grantProfileManagerOverride(
                    identity(), orphan.getId(), recipient.getId(), "support")))
        .isInstanceOf(ManagerRejections.ReauthenticationRequired.class);
  }

  @Test
  @DisplayName("Should filter by visibility and expiry when invitation queries run")
  void shouldFilterByVisibilityAndExpiryWhenInvitationQueriesRun() {
    invitations.save(pendingInvitation(orphan.getId(), recipient.getId(), inviter.getId()));
    var expired = pendingInvitation(orphan.getId(), UUID.randomUUID(), inviter.getId());
    expired.setExpiresAt(NOW.minusSeconds(1));
    invitations.save(expired);

    assertThat(service.managerInvitations(identity(), orphan.getId(), paginationOptions()).items())
        .hasSize(1);
    assertThat(service.pendingManagerInvitations(recipientIdentity(), paginationOptions()).items())
        .hasSize(1);

    authorization.denyAll();
    assertThat(service.managerInvitations(identity(), orphan.getId(), paginationOptions()).items())
        .isEmpty();
  }

  private UserAccount eligibleAccount(UUID accountId) {
    var account =
        accounts.save(
            AccountFixture.defaultAccountBuilder()
                .id(accountId)
                .householdRole(HouseholdRole.MEMBER)
                .build());
    profiles.save(
        ProfileFixture.defaultProfileBuilder().id(account.getPersonalProfileId()).build());
    return account;
  }

  private static KeysetPaginationOptions paginationOptions() {
    return new KeysetPaginationOptions(
        null,
        PaginationOptions.builder()
            .paginationDirection(PaginationDirection.FORWARD)
            .cursor(Optional.empty())
            .limit(100)
            .build());
  }

  private ProfileManagerInvitation pendingInvitation(
      UUID profileId, UUID recipientId, UUID inviterId) {
    return ProfileManagerInvitation.builder()
        .profileId(profileId)
        .profileName("Joe")
        .inviterAccountId(inviterId)
        .inviterDisplayName("Inviter")
        .recipientAccountId(recipientId)
        .recipientEmail("recipient@example.com")
        .expiresAt(NOW.plusSeconds(3600))
        .publicId(UUID.randomUUID().toString())
        .secretDigest(new byte[] {1})
        .build();
  }

  private AuthenticatedIdentity identity() {
    return authorization.currentIdentity();
  }

  private AuthenticatedIdentity recipientIdentity() {
    return AuthenticatedIdentityFixture.accountScopedBuilder().accountId(recipient.getId()).build();
  }

  private static ProfileManagerAdministrationService.IssuedManagerInvitation issued(
      Outcome<ProfileManagerAdministrationService.IssuedManagerInvitation, ?> outcome) {
    return outcome.fold(
        value -> value,
        rejections -> {
          throw new AssertionError("expected acceptance but got " + rejections);
        });
  }

  private static Object rejectionOf(Outcome<?, ?> outcome) {
    return switch (outcome) {
      case Outcome.Rejected<?, ?>(var rejections) -> rejections.getFirst();
      case Outcome.Accepted<?, ?> accepted ->
          throw new AssertionError("expected a rejection but got " + accepted);
    };
  }
}
