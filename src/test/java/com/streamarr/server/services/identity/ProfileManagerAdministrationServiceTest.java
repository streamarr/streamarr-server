package com.streamarr.server.services.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.domain.auth.CredentialAttemptMetadata;
import com.streamarr.server.domain.auth.CredentialAttemptResult;
import com.streamarr.server.domain.auth.CredentialKind;
import com.streamarr.server.domain.auth.HouseholdRole;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.UserAccount;
import com.streamarr.server.exceptions.AuthorizationUnavailableException;
import com.streamarr.server.exceptions.TooManyCredentialAttemptsException;
import com.streamarr.server.fakes.CapturingEventPublisher;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeCredentialAttemptRepository;
import com.streamarr.server.fakes.FakeProfileHouseholdShareRepository;
import com.streamarr.server.fakes.FakeProfileManagerInvitationRepository;
import com.streamarr.server.fakes.FakeProfileManagerRepository;
import com.streamarr.server.fakes.FakeProfileRepository;
import com.streamarr.server.fakes.FakeSecurityAuditEventRepository;
import com.streamarr.server.fakes.FakeTransactionManager;
import com.streamarr.server.fakes.FakeUserAccountRepository;
import com.streamarr.server.fixtures.AccountFixture;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.fixtures.PaginationFixture;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.OpaqueOneTimeCodes;
import com.streamarr.server.services.auth.ProfileManagerInvitationLifecycle;
import com.streamarr.server.services.auth.ProfileShareLifecycle;
import com.streamarr.server.services.authorization.AuthorizationUnit;
import com.streamarr.server.services.authorization.Decision;
import com.streamarr.server.services.authorization.Intent;
import com.streamarr.server.services.mutation.ConstraintViolationTranslator;
import com.streamarr.server.services.mutation.MutationTransactions;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.PageRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Profile Manager Administration Service Tests")
class ProfileManagerAdministrationServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
  private static final Duration INVITATION_LIFETIME = Duration.ofDays(2);

  private final FakeProfileHouseholdShareRepository shares =
      new FakeProfileHouseholdShareRepository();
  private final FakeProfileRepository profiles = new FakeProfileRepository(shares);
  private final FakeProfileManagerRepository managers = new FakeProfileManagerRepository();
  private final FakeUserAccountRepository accounts =
      new FakeUserAccountRepository(profiles, managers);
  private final FakeProfileManagerInvitationRepository invitations =
      new FakeProfileManagerInvitationRepository();
  private final FakeSecurityAuditEventRepository audit = new FakeSecurityAuditEventRepository();
  private final FakeCredentialAttemptRepository credentialAttempts =
      new FakeCredentialAttemptRepository();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final FakeAuthorizationService authorization =
      new FakeAuthorizationService(AuthenticatedIdentityFixture.accountScopedBuilder().build());

  private final ProfileManagerAdministrationService service = serviceAt(clock);

  private UserAccount inviter;
  private UserAccount recipient;
  private Profile orphan;

  @BeforeEach
  void setUp() {
    inviter = eligibleAccount(authorization.currentIdentity().accountId());
    recipient = eligibleAccount(UUID.randomUUID());
    orphan = profiles.save(ProfileFixture.defaultProfileBuilder().name("Joe").build());
    managers.tryGrantDirectManagement(inviter.getId(), orphan.getId());
  }

  private ProfileManagerAdministrationService serviceAt(Clock clock) {
    return new ProfileManagerAdministrationService(
        authorization,
        new ProfileAuthorityExplanation(authorization, profiles),
        invitations,
        new ProfileManagerInvitationLifecycle(invitations, new CapturingEventPublisher()),
        managers,
        profiles,
        accounts,
        new LeavingManagerProposals(
            new ProfileManagerInvitationLifecycle(invitations, new CapturingEventPublisher()),
            new ProfileShareLifecycle(shares, new CapturingEventPublisher())),
        new ProfileManagementNotifications(profiles, new CapturingEventPublisher()),
        audit,
        new OpaqueOneTimeCodes(),
        credentialAttempts.gate(clock),
        () -> INVITATION_LIFETIME,
        new MutationTransactions(new FakeTransactionManager(), new ConstraintViolationTranslator()),
        new ProfileManagerInvitationsList(invitations, clock),
        new ReceivedManagerInvitationsList(invitations, clock),
        clock);
  }

  @Test
  @DisplayName("Should replace the older pending invitation when the same pair is invited again")
  void shouldReplaceOlderPendingInvitationWhenSamePairIsInvitedAgain() {
    var first =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));
    assertThat(first.code()).isNotBlank();
    assertThat(first.invitation().getProfileName()).isEqualTo("Joe");
    assertThat(first.invitation().getInviterDisplayName()).isEqualTo(inviter.getDisplayName());
    assertThat(first.toString()).doesNotContain(first.code());

    var second =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));
    assertThat(invitations.findById(first.invitation().getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(second.invitation().getStatus()).isEqualTo(ProfileManagerInvitationStatus.PENDING);
  }

  @Test
  @DisplayName(
      "Should expire the invitation after the invitation lifetime when a manager is invited")
  void shouldExpireInvitationAfterInvitationLifetimeWhenManagerIsInvited() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));

    assertThat(invitations.findById(issued.invitation().getId()).orElseThrow().getExpiresAt())
        .isEqualTo(NOW.plus(INVITATION_LIFETIME));
  }

  @Test
  @DisplayName(
      "Should answer the expiry to the microsecond, as PostgreSQL stores it, when the clock reads"
          + " nanoseconds")
  void shouldAnswerTheExpiryToTheMicrosecondAsPostgresStoresItWhenTheClockReadsNanoseconds() {
    var fine = Clock.fixed(NOW.plusNanos(445_953_526), ZoneOffset.UTC);

    var issued =
        issued(
            serviceAt(fine).inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));

    assertThat(issued.invitation().getExpiresAt())
        .isEqualTo(NOW.plusNanos(445_953_000).plus(INVITATION_LIFETIME));
  }

  @Test
  @DisplayName("Should cancel a pending invitation when its inviter acts")
  void shouldCancelPendingInvitationWhenInviterActs() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));

    var canceled = service.cancelManagerInvitation(identity(), issued.invitation().getId());

    assertThat(canceled).isInstanceOf(Outcome.Accepted.class);
    assertThat(invitations.findById(issued.invitation().getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.CANCELED);
    assertThat(
            rejectionOf(service.cancelManagerInvitation(identity(), issued.invitation().getId())))
        .isInstanceOf(ManagerRejections.InvitationNotPending.class);
  }

  @Test
  @DisplayName("Should hide an invitation when an unauthorized cancellation caller cannot view it")
  void shouldHideInvitationWhenUnauthorizedCancellationCallerCannotViewIt() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));
    var outsider =
        AuthenticatedIdentityFixture.accountScopedBuilder().accountId(UUID.randomUUID()).build();
    authorization.denyAll();

    assertThat(rejectionOf(service.cancelManagerInvitation(outsider, issued.invitation().getId())))
        .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
  }

  @Test
  @DisplayName("Should forbid cancellation when an unauthorized caller can view the invitation")
  void shouldForbidCancellationWhenUnauthorizedCallerCanViewInvitation() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.ViewReceivedManagerInvitation
                ? new Decision.Allowed<>(AuthorizationUnit.INSTANCE)
                : new Decision.Denied<>(Decision.DenialReason.POLICY));
    var caller = recipientIdentity();
    var invitationId = issued.invitation().getId();

    assertThatThrownBy(() -> service.cancelManagerInvitation(caller, invitationId))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  @DisplayName("Should refuse the address when it is blank or not the shape of an email address")
  void shouldRefuseTheAddressWhenItIsBlankOrNotTheShapeOfAnEmailAddress() {
    assertThat(rejectionOf(service.inviteProfileManager(identity(), orphan.getId(), "  ")))
        .isInstanceOf(ManagerRejections.EmailRequired.class);
    assertThat(rejectionOf(service.inviteProfileManager(identity(), orphan.getId(), "mara")))
        .isInstanceOf(ManagerRejections.EmailInvalid.class);
    assertThat(invitations.findAll()).isEmpty();
  }

  @Test
  @DisplayName("Should refuse an eleventh invitation when ten wait for the Profile")
  void shouldRefuseAnEleventhInvitationWhenTenWaitForTheProfile() {
    inviteTenAddresses();

    assertThat(
            rejectionOf(
                service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail())))
        .isInstanceOf(ManagerRejections.ManagerInvitationLimitReached.class);
    assertThat(waitingFor(orphan)).hasSize(10);
  }

  @Test
  @DisplayName("Should count it toward the limit when nobody can answer the invitation")
  void shouldCountItTowardTheLimitWhenNobodyCanAnswerTheInvitation() {
    inviteTenAddresses();

    assertThat(waitingFor(orphan))
        .hasSize(10)
        .allSatisfy(invitation -> assertThat(invitation.getRecipientAccountId()).isNull());
    assertThat(
            rejectionOf(
                service.inviteProfileManager(identity(), orphan.getId(), "sam@example.com")))
        .isInstanceOf(ManagerRejections.ManagerInvitationLimitReached.class);
  }

  @Test
  @DisplayName("Should make room when a waiting invitation expires")
  void shouldMakeRoomWhenAWaitingInvitationExpires() {
    inviteTenAddresses();
    var lapsing = waitingFor(orphan).getFirst();
    lapsing.setExpiresAt(NOW);

    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));

    assertThat(issued.invitation().getStatus()).isEqualTo(ProfileManagerInvitationStatus.PENDING);
  }

  @Test
  @DisplayName("Should replace the invitation when ten wait and the same address is invited again")
  void shouldReplaceTheInvitationWhenTenWaitAndTheSameAddressIsInvitedAgain() {
    inviteTenAddresses();

    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), "Person.0@Example.com"));

    assertThat(waitingFor(orphan)).hasSize(10).contains(issued.invitation());
  }

  @Test
  @DisplayName("Should return Profile not found when the caller cannot view the Profile")
  void shouldReturnProfileNotFoundWhenCallerCannotViewProfile() {
    authorization.denyAll();
    assertThat(
            rejectionOf(
                service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail())))
        .isInstanceOf(ManagerRejections.ProfileNotFound.class);
  }

  @Test
  @DisplayName("Should return Profile not found before authorization when the Profile is missing")
  void shouldReturnProfileNotFoundBeforeAuthorizationWhenProfileIsMissing() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);

    assertThat(
            rejectionOf(
                service.inviteProfileManager(identity(), UUID.randomUUID(), recipient.getEmail())))
        .isInstanceOf(ManagerRejections.ProfileNotFound.class);
    assertThat(authorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should surface authorization unavailable when Profile visibility cannot decide")
  void shouldSurfaceAuthorizationUnavailableWhenProfileVisibilityCannotDecide() {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.ViewProfileAdministration
                ? new Decision.Failed<>(Decision.FailureCause.ENGINE_FAILURE)
                : new Decision.Denied<>(Decision.DenialReason.POLICY));
    var caller = identity();
    var profileId = orphan.getId();
    var address = recipient.getEmail();

    assertThatThrownBy(() -> service.inviteProfileManager(caller, profileId, address))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should allow only one decision when the named Account accepts by id twice")
  void shouldAllowOnlyOneDecisionWhenTheNamedAccountAcceptsByIdTwice() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));

    var accepted =
        service.acceptManagerInvitation(recipientIdentity(), issued.invitation().getId());
    assertThat(accepted).isInstanceOf(Outcome.Accepted.class);
    assertThat(credentialAttempts.attempts()).isEmpty();
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

    assertThat(
            rejectionOf(
                service.acceptManagerInvitation(recipientIdentity(), issued.invitation().getId())))
        .isInstanceOf(ManagerRejections.InvitationNotPending.class);
    assertThat(
            rejectionOf(service.cancelManagerInvitation(identity(), issued.invitation().getId())))
        .isInstanceOf(ManagerRejections.InvitationNotPending.class);
  }

  @Test
  @DisplayName(
      "Should answer as for a missing invitation when another Account decides by id or no"
          + " invitation has the id")
  void shouldAnswerAsForAMissingInvitationWhenAnotherAccountDecidesByIdOrNoInvitationHasTheId() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));
    // Cedar's named-recipient policy is stubbed as the denial here and proven in the policy tests.
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.AcceptManagerInvitation
                    || intent instanceof Intent.DeclineManagerInvitation
                ? new Decision.Denied<>(Decision.DenialReason.POLICY)
                : new Decision.Allowed<>(AuthorizationUnit.INSTANCE));

    for (var invitationId : List.of(issued.invitation().getId(), UUID.randomUUID())) {
      assertThat(rejectionOf(service.acceptManagerInvitation(identity(), invitationId)))
          .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
      assertThat(rejectionOf(service.declineManagerInvitation(identity(), invitationId)))
          .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
    }

    assertThat(invitations.findById(issued.invitation().getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.PENDING);
  }

  @Test
  @DisplayName("Should surface authorization unavailable when invitation acceptance cannot decide")
  void shouldSurfaceAuthorizationUnavailableWhenInvitationAcceptanceCannotDecide() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var caller = recipientIdentity();
    var invitationId = issued.invitation().getId();

    assertThatThrownBy(() -> service.acceptManagerInvitation(caller, invitationId))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should answer the invitation when the named Account looks up its code")
  void shouldAnswerTheInvitationWhenTheNamedAccountLooksUpItsCode() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));

    var found = service.lookUpManagerInvitationCode(recipientIdentity(), code(issued.code()));

    assertThat(found)
        .isEqualTo(
            Outcome.accepted(invitations.findById(issued.invitation().getId()).orElseThrow()));
    assertThat(credentialAttempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.metadata())
                  .isEqualTo(
                      CredentialAttemptMetadata.builder()
                          .kind(CredentialKind.PROFILE_MANAGER_INVITATION_CODE)
                          .credentialId(issued.invitation().getId())
                          .ipAddress("192.0.2.30")
                          .build());
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.SUCCEEDED);
            });
    assertThat(invitations.findById(issued.invitation().getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.PENDING);
  }

  @Test
  @DisplayName(
      "Should count the lookup and answer as for a missing invitation when another Account"
          + " presents the code")
  void shouldCountTheLookupAndAnswerAsForAMissingInvitationWhenAnotherAccountPresentsTheCode() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.ViewReceivedManagerInvitation
                ? new Decision.Denied<>(Decision.DenialReason.POLICY)
                : new Decision.Allowed<>(AuthorizationUnit.INSTANCE));

    assertThat(rejectionOf(service.lookUpManagerInvitationCode(identity(), code(issued.code()))))
        .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
    assertThat(credentialAttempts.attempts())
        .singleElement()
        .satisfies(
            attempt ->
                assertThat(attempt.metadata().credentialId())
                    .isEqualTo(issued.invitation().getId()));
  }

  @Test
  @DisplayName(
      "Should refuse a lookup before the code counts when the session may not read the"
          + " invitations it received")
  void shouldRefuseALookupBeforeTheCodeCountsWhenTheSessionMayNotReadTheInvitationsItReceived() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.ViewReceivedManagerInvitations
                ? new Decision.Denied<>(Decision.DenialReason.POLICY)
                : new Decision.Allowed<>(AuthorizationUnit.INSTANCE));
    var caller = recipientIdentity();
    var command = code(issued.code());

    assertThatThrownBy(() -> service.lookUpManagerInvitationCode(caller, command))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(credentialAttempts.attempts()).isEmpty();
  }

  @Test
  @DisplayName("Should return a uniform miss for invalid codes and refuse when the journal blocks")
  void shouldReturnUniformMissForInvalidCodesAndRefuseWhenJournalBlocks() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));

    assertThat(
            rejectionOf(service.lookUpManagerInvitationCode(recipientIdentity(), code("garbage"))))
        .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
    assertThat(credentialAttempts.attempts()).isEmpty();
    assertThat(
            rejectionOf(
                service.lookUpManagerInvitationCode(recipientIdentity(), code("unknown.secret"))))
        .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
    assertThat(credentialAttempts.attempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.metadata())
                  .isEqualTo(
                      CredentialAttemptMetadata.builder()
                          .kind(CredentialKind.PROFILE_MANAGER_INVITATION_CODE)
                          .ipAddress("192.0.2.30")
                          .build());
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.FAILED);
            });

    var guess = issued.invitation().getPublicId() + ".guess";
    assertThat(rejectionOf(service.lookUpManagerInvitationCode(recipientIdentity(), code(guess))))
        .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
    assertThat(credentialAttempts.attempts().getLast().metadata().credentialId())
        .isEqualTo(issued.invitation().getId());

    credentialAttempts.rejectReservations(Duration.ofMinutes(15));
    var recipientIdentity = recipientIdentity();
    var command = code(issued.code());
    assertThatThrownBy(() -> service.lookUpManagerInvitationCode(recipientIdentity, command))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
  }

  @Test
  @DisplayName(
      "Should refuse the code but not the invitation when five wrong secrets exhaust the code's"
          + " budget")
  void shouldRefuseTheCodeButNotTheInvitationWhenFiveWrongSecretsExhaustTheCodesBudget() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));
    var wrong = code(issued.invitation().getPublicId() + ".wrong-secret");
    var caller = recipientIdentity();
    for (var i = 0; i < 5; i++) {
      assertThat(rejectionOf(service.lookUpManagerInvitationCode(caller, wrong)))
          .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
    }

    var correct = code(issued.code());
    assertThatThrownBy(() -> service.lookUpManagerInvitationCode(caller, correct))
        .isInstanceOf(TooManyCredentialAttemptsException.class);
    assertThat(credentialAttempts.attempts())
        .hasSize(5)
        .allSatisfy(
            attempt -> {
              assertThat(attempt.metadata().credentialId()).isEqualTo(issued.invitation().getId());
              assertThat(attempt.result()).isEqualTo(CredentialAttemptResult.FAILED);
            });

    // The throttle guards the code; the named Account still decides by id, which is no credential.
    assertThat(service.acceptManagerInvitation(caller, issued.invitation().getId()))
        .isInstanceOf(Outcome.Accepted.class);
  }

  @Test
  @DisplayName(
      "Should refuse acceptance as not pending and the code as unknown when the invitation has"
          + " expired")
  void shouldRefuseAcceptanceAsNotPendingAndTheCodeAsUnknownWhenTheInvitationHasExpired() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));
    issued.invitation().setExpiresAt(NOW.minusSeconds(1));

    assertThat(
            rejectionOf(
                service.acceptManagerInvitation(recipientIdentity(), issued.invitation().getId())))
        .isInstanceOf(ManagerRejections.InvitationNotPending.class);
    assertThat(
            rejectionOf(
                service.lookUpManagerInvitationCode(recipientIdentity(), code(issued.code()))))
        .isInstanceOf(ManagerRejections.ManagerInvitationNotFound.class);
    assertThat(managers.existsByAccountIdAndProfileId(recipient.getId(), orphan.getId())).isFalse();
  }

  @Test
  @DisplayName("Should invalidate the invitation when the inviting manager loses management")
  void shouldInvalidateInvitationWhenInvitingManagerLosesManagement() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));
    managers.tryRevokeDirectManagement(inviter.getId(), orphan.getId());

    assertThat(
            rejectionOf(
                service.acceptManagerInvitation(recipientIdentity(), issued.invitation().getId())))
        .isInstanceOf(ManagerRejections.InvitationNotPending.class);
    assertThat(invitations.findById(issued.invitation().getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
  }

  @Test
  @DisplayName(
      "Should invalidate only the stale invitation when its acceptance finds its inviter gone")
  void shouldInvalidateOnlyTheStaleInvitationWhenItsAcceptanceFindsItsInviterGone() {
    var stale =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));
    var replacementInviter = eligibleAccount(UUID.randomUUID());
    managers.tryGrantDirectManagement(replacementInviter.getId(), orphan.getId());
    // To the same Account for the same Profile, at another address it has had.
    var other =
        invitations.save(
            pendingInvitation(orphan.getId(), recipient.getId(), replacementInviter.getId()));
    managers.tryRevokeDirectManagement(inviter.getId(), orphan.getId());

    assertThat(
            rejectionOf(
                service.acceptManagerInvitation(recipientIdentity(), stale.invitation().getId())))
        .isInstanceOf(ManagerRejections.InvitationNotPending.class);
    assertThat(invitations.findById(stale.invitation().getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(invitations.findById(other.getId()).orElseThrow().statusToItsAccountAt(NOW))
        .isEqualTo(ProfileManagerInvitationStatus.PENDING);
  }

  @Test
  @DisplayName("Should refuse acceptance when the recipient became ineligible")
  void shouldRefuseAcceptanceWhenRecipientBecameIneligible() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));
    profiles
        .findById(recipient.getPersonalProfileId())
        .orElseThrow()
        .setMaximumAllowedRatingAge(12);

    assertThat(
            rejectionOf(
                service.acceptManagerInvitation(recipientIdentity(), issued.invitation().getId())))
        .isInstanceOf(ManagerRejections.RecipientNotEligible.class);
  }

  @Test
  @DisplayName("Should allow a single decline when the named Account declines by id")
  void shouldAllowASingleDeclineWhenTheNamedAccountDeclinesById() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));

    var declined =
        service.declineManagerInvitation(recipientIdentity(), issued.invitation().getId());
    assertThat(declined).isInstanceOf(Outcome.Accepted.class);
    assertThat(credentialAttempts.attempts()).isEmpty();
    assertThat(
            rejectionOf(
                service.declineManagerInvitation(recipientIdentity(), issued.invitation().getId())))
        .isInstanceOf(ManagerRejections.InvitationNotPending.class);
    assertThat(managers.existsByAccountIdAndProfileId(recipient.getId(), orphan.getId())).isFalse();
  }

  @Test
  @DisplayName("Should surface authorization unavailable when invitation decline cannot decide")
  void shouldSurfaceAuthorizationUnavailableWhenInvitationDeclineCannotDecide() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var caller = recipientIdentity();
    var invitationId = issued.invitation().getId();

    assertThatThrownBy(() -> service.declineManagerInvitation(caller, invitationId))
        .isInstanceOf(AuthorizationUnavailableException.class);
  }

  @Test
  @DisplayName("Should invalidate an invitation when its inviter only supervises the Profile")
  void shouldInvalidateInvitationWhenInviterOnlySupervisesProfile() {
    var kid = profiles.save(ProfileFixture.kidProfileBuilder().name("Kid").build());
    shares.share(kid.getId(), inviter.getHouseholdId(), false);
    inviter.setHouseholdRole(HouseholdRole.ADMIN);
    accounts.save(inviter);
    var issued =
        issued(service.inviteProfileManager(identity(), kid.getId(), recipient.getEmail()));
    // Share-derived supervision ends with the share and cannot keep portable authority standing.
    managers.tryRevokeDirectManagement(inviter.getId(), kid.getId());

    assertThat(
            rejectionOf(
                service.acceptManagerInvitation(recipientIdentity(), issued.invitation().getId())))
        .isInstanceOf(ManagerRejections.InvitationNotPending.class);
    assertThat(invitations.findById(issued.invitation().getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
  }

  @Test
  @DisplayName("Should reject an override grant when the Account does not exist")
  void shouldRejectOverrideGrantWhenAccountDoesNotExist() {
    assertThat(
            rejectionOf(
                service.administrativelyGrantProfileManager(
                    identity(), orphan.getId(), UUID.randomUUID(), "support")))
        .isInstanceOf(ManagerRejections.RecipientNotFound.class);
  }

  @Test
  @DisplayName("Should reject an administrative grant when the recipient is ineligible")
  void shouldRejectAdministrativeGrantWhenRecipientIsIneligible() {
    profiles
        .findById(recipient.getPersonalProfileId())
        .orElseThrow()
        .setMaximumAllowedRatingAge(12);

    assertThat(
            rejectionOf(
                service.administrativelyGrantProfileManager(
                    identity(), orphan.getId(), recipient.getId(), "support")))
        .isInstanceOf(ManagerRejections.RecipientNotEligible.class);
    assertThat(managers.existsByAccountIdAndProfileId(recipient.getId(), orphan.getId())).isFalse();
  }

  @Test
  @DisplayName("Should invalidate the redundant invitation when an override grant succeeds once")
  void shouldInvalidateRedundantInvitationWhenOverrideGrantSucceedsOnce() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));

    var granted =
        service.administrativelyGrantProfileManager(
            identity(), orphan.getId(), recipient.getId(), "support");
    assertThat(granted).isInstanceOf(Outcome.Accepted.class);
    assertThat(managers.existsByAccountIdAndProfileId(recipient.getId(), orphan.getId())).isTrue();
    assertThat(invitations.findById(issued.invitation().getId()).orElseThrow())
        .satisfies(ProfileManagerAdministrationServiceTest::invalidatedForItsAccountAlone);
    assertThat(audit.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.operation()).isEqualTo("administrativelyGrantProfileManager");
              assertThat(entry.actorAccountId()).isEqualTo(inviter.getId());
              assertThat(entry.reason()).isEqualTo("support");
              assertThat(entry.resources())
                  .containsEntry("profileId", orphan.getId())
                  .containsEntry("accountId", recipient.getId());
            });

    assertThat(
            rejectionOf(
                service.administrativelyGrantProfileManager(
                    identity(), orphan.getId(), recipient.getId(), "again")))
        .isInstanceOf(ManagerRejections.AlreadyManager.class);
  }

  @Test
  @DisplayName("Should invalidate restorable proposals when an override removal succeeds once")
  void shouldInvalidateRestorableProposalsWhenOverrideRemovalSucceedsOnce() {
    managers.tryGrantDirectManagement(recipient.getId(), orphan.getId());
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
        service.administrativelyRemoveProfileManager(
            identity(), orphan.getId(), recipient.getId(), "abuse");
    assertThat(removed).isInstanceOf(Outcome.Accepted.class);
    assertThat(managers.existsByAccountIdAndProfileId(recipient.getId(), orphan.getId())).isFalse();
    assertThat(invitations.findById(restorable.getId()).orElseThrow())
        .satisfies(ProfileManagerAdministrationServiceTest::invalidatedForItsAccountAlone);
    assertThat(shares.findById(offered.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.INVALIDATED);
    assertThat(shares.findById(offered.getId()).orElseThrow().getLastModifiedOn()).isEqualTo(NOW);
    assertThat(audit.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.operation()).isEqualTo("administrativelyRemoveProfileManager");
              assertThat(entry.actorAccountId()).isEqualTo(inviter.getId());
              assertThat(entry.reason()).isEqualTo("abuse");
              assertThat(entry.resources())
                  .containsEntry("profileId", orphan.getId())
                  .containsEntry("accountId", recipient.getId());
            });

    assertThat(
            rejectionOf(
                service.administrativelyRemoveProfileManager(
                    identity(), orphan.getId(), recipient.getId(), "again")))
        .isInstanceOf(ManagerRejections.NotAManager.class);
  }

  @Test
  @DisplayName("Should invalidate the leaver's proposals when a manager relinquishes once")
  void shouldInvalidateLeaversProposalsWhenManagerRelinquishesOnce() {
    var issued =
        issued(service.inviteProfileManager(identity(), orphan.getId(), recipient.getEmail()));

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
      "Should remove a direct manager and invalidate their proposals when the sovereign Account acts on its Personal Profile")
  void
      shouldRemoveDirectManagerAndInvalidateTheirProposalsWhenSovereignAccountActsOnPersonalProfile() {
    var personal =
        profiles.save(
            ProfileFixture.defaultProfileBuilder().id(inviter.getPersonalProfileId()).build());
    managers.tryGrantDirectManagement(recipient.getId(), personal.getId());
    var restorable =
        invitations.save(pendingInvitation(personal.getId(), recipient.getId(), inviter.getId()));
    var proposed =
        invitations.save(pendingInvitation(personal.getId(), UUID.randomUUID(), recipient.getId()));
    var offered =
        shares.save(
            ProfileHouseholdShare.builder()
                .profileId(personal.getId())
                .householdId(UUID.randomUUID())
                .status(ProfileShareStatus.PENDING)
                .offeredByAccountId(recipient.getId())
                .expiresAt(NOW.plusSeconds(3600))
                .build());

    var removed = service.removeProfileManager(identity(), personal.getId(), recipient.getId());
    assertThat(removed).isInstanceOf(Outcome.Accepted.class);
    assertThat(managers.existsByAccountIdAndProfileId(recipient.getId(), personal.getId()))
        .isFalse();
    assertThat(invitations.findById(restorable.getId()).orElseThrow())
        .satisfies(ProfileManagerAdministrationServiceTest::invalidatedForItsAccountAlone);
    assertThat(invitations.findById(proposed.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(shares.findById(offered.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.INVALIDATED);

    assertThat(
            rejectionOf(
                service.removeProfileManager(identity(), personal.getId(), recipient.getId())))
        .isInstanceOf(ManagerRejections.NotAManager.class);
  }

  @Test
  @DisplayName(
      "Should validate the reason before the ceremony when an administrative grant is requested")
  void shouldValidateReasonBeforeCeremonyWhenAdministrativeGrantIsRequested() {
    assertThat(
            rejectionOf(
                service.administrativelyGrantProfileManager(
                    identity(), orphan.getId(), recipient.getId(), " ")))
        .isInstanceOf(ManagerRejections.ReasonRequired.class);
    assertThat(authorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should require reauthentication when an administrative grant is requested")
  void shouldRequireReauthenticationWhenAdministrativeGrantIsRequested() {
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.AdministrativelyGrantProfileManager
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : new Decision.Allowed<>(AuthorizationUnit.INSTANCE));
    assertThat(
            rejectionOf(
                service.administrativelyGrantProfileManager(
                    identity(), orphan.getId(), recipient.getId(), "support")))
        .isInstanceOf(ManagerRejections.ReauthenticationRequired.class);
  }

  @Test
  @DisplayName(
      "Should validate the reason before the ceremony when an administrative removal is requested")
  void shouldValidateReasonBeforeCeremonyWhenAdministrativeRemovalIsRequested() {
    managers.tryGrantDirectManagement(recipient.getId(), orphan.getId());

    assertThat(
            rejectionOf(
                service.administrativelyRemoveProfileManager(
                    identity(), orphan.getId(), recipient.getId(), " ")))
        .isInstanceOf(ManagerRejections.ReasonRequired.class);
    assertThat(authorization.recordedIntents()).isEmpty();
  }

  @Test
  @DisplayName("Should require reauthentication when an administrative removal is requested")
  void shouldRequireReauthenticationWhenAdministrativeRemovalIsRequested() {
    managers.tryGrantDirectManagement(recipient.getId(), orphan.getId());
    authorization.decideUnitWith(
        intent ->
            intent instanceof Intent.AdministrativelyRemoveProfileManager
                ? new Decision.Denied<>(Decision.DenialReason.REAUTHENTICATION_REQUIRED)
                : new Decision.Allowed<>(AuthorizationUnit.INSTANCE));

    assertThat(
            rejectionOf(
                service.administrativelyRemoveProfileManager(
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
    var accepted = pendingInvitation(orphan.getId(), recipient.getId(), inviter.getId());
    accepted.setStatus(ProfileManagerInvitationStatus.ACCEPTED);
    accepted.setDecidedAt(NOW.minusSeconds(1));
    invitations.save(accepted);

    assertThat(
            service
                .pendingManagerInvitationsForProfile(
                    identity(), orphan.getId(), paginationOptions())
                .rows())
        .singleElement()
        .satisfies(
            item ->
                assertThat(item.item().getStatus())
                    .isEqualTo(ProfileManagerInvitationStatus.PENDING));
    assertThat(service.pendingManagerInvitations(recipientIdentity(), paginationOptions()).rows())
        .singleElement()
        .satisfies(
            item ->
                assertThat(item.item().getStatus())
                    .isEqualTo(ProfileManagerInvitationStatus.PENDING));

    authorization.denyAll();
    assertThat(
            service
                .pendingManagerInvitationsForProfile(
                    identity(), orphan.getId(), paginationOptions())
                .rows())
        .isEmpty();
  }

  @Test
  @DisplayName("Should surface authorization unavailable when invitation listing cannot decide")
  void shouldSurfaceAuthorizationUnavailableWhenInvitationListingCannotDecide() {
    authorization.failWith(Decision.FailureCause.ENGINE_FAILURE);
    var caller = identity();
    var profileId = orphan.getId();
    var options = paginationOptions();

    assertThatThrownBy(
            () -> service.pendingManagerInvitationsForProfile(caller, profileId, options))
        .isInstanceOf(AuthorizationUnavailableException.class);
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

  private static PageRequest paginationOptions() {
    return PaginationFixture.firstRows(100);
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

  private void inviteTenAddresses() {
    for (var person = 0; person < 10; person++) {
      issued(
          service.inviteProfileManager(
              identity(), orphan.getId(), "person.%d@example.com".formatted(person)));
    }
  }

  private List<ProfileManagerInvitation> waitingFor(Profile profile) {
    return invitations.findAll().stream()
        .filter(invitation -> profile.getId().equals(invitation.getProfileId()))
        .filter(invitation -> invitation.statusAt(NOW) == ProfileManagerInvitationStatus.PENDING)
        .toList();
  }

  private AuthenticatedIdentity identity() {
    return authorization.currentIdentity();
  }

  private AuthenticatedIdentity recipientIdentity() {
    return AuthenticatedIdentityFixture.accountScopedBuilder().accountId(recipient.getId()).build();
  }

  private static ProfileManagerAdministrationService.ManagerInvitationCodeCommand code(
      String rawCode) {
    return ProfileManagerAdministrationService.ManagerInvitationCodeCommand.builder()
        .code(rawCode)
        .ipAddress("192.0.2.30")
        .build();
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

  // Its Account can no longer answer it; to the Profile's managers it still waits (ADR 0024).
  private static void invalidatedForItsAccountAlone(ProfileManagerInvitation invitation) {
    assertThat(invitation.statusToItsAccountAt(NOW))
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(invitation.statusAt(NOW)).isEqualTo(ProfileManagerInvitationStatus.PENDING);
  }
}
