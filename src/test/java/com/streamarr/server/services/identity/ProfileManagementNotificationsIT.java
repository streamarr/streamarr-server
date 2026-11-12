package com.streamarr.server.services.identity;

import static com.streamarr.server.support.StoredNotificationRows.document;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.SubjectReference;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccountInvitationService;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.InvitationLifetime;
import com.streamarr.server.services.auth.ManagerInvitationExpirySweeper;
import com.streamarr.server.services.auth.ProfileManagerInvitationLifecycle;
import com.streamarr.server.services.events.notification.ProfileManagementRemovedSnapshot;
import com.streamarr.server.services.events.notification.ProfileManagerInvitationAnswer;
import com.streamarr.server.services.events.notification.ProfileManagerInvitationAnsweredSnapshot;
import com.streamarr.server.services.events.notification.ProfileManagerInvitationReceivedSnapshot;
import com.streamarr.server.services.identity.ProfileLifecycleService.TransferProfileCommand;
import com.streamarr.server.services.identity.ProfileManagerAdministrationService.IssuedManagerInvitation;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.notification.NotificationService;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ManagerInvitationSteps;
import com.streamarr.server.support.ProfileCastSupport;
import com.streamarr.server.support.ProfileConnections;
import com.streamarr.server.support.StoredNotificationRows;
import com.streamarr.server.support.StoredNotificationRows.Row;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Who the identity domain tells about Profile management, and what (ADR 0063 §The Profile
 * management kinds). Each invitation is sent as a manager sends it, and the Notifications it made
 * are read as they are stored.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Management Notifications Integration Tests")
class ProfileManagementNotificationsIT extends AbstractIntegrationTest {

  private static final String RECEIVED = "PROFILE_MANAGER_INVITATION_RECEIVED";
  private static final String ANSWERED = "PROFILE_MANAGER_INVITATION_ANSWERED";
  private static final String REMOVED = "PROFILE_MANAGEMENT_REMOVED";
  private static final String INVITER_NAME = "Sam Rivers";

  @Autowired private ProfileManagerAdministrationService profileManagement;
  @Autowired private ProfileAdministrationService profileAdministration;
  @Autowired private ProfileLifecycleService profileLifecycle;
  @Autowired private AccountLifecycleService accountLifecycle;
  @Autowired private CredentialIssuanceService credentialIssuance;
  @Autowired private AccountInvitationService accountInvitations;
  @Autowired private AccountInvitationRepository accountInvitationRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private ManagerInvitationExpirySweeper expirySweeper;
  @Autowired private ProfileManagerInvitationLifecycle invitationLifecycle;
  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private InvitationLifetime invitationLifetime;
  @Autowired private NotificationService notificationService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private final List<TestIdentity> identities = new ArrayList<>();
  private ManagerInvitationSteps steps;
  private ProfileConnections connections;
  private StoredNotificationRows rows;
  private TestIdentity sam;
  private TestIdentity mara;
  private Profile alex;

  /** An address whose invitation no Account may accept. */
  enum Unanswerable {
    NO_ACCOUNT,
    /** A supervised Account, which may not manage a Profile. */
    INELIGIBLE_ACCOUNT,
    MANAGING_ACCOUNT
  }

  /** How an invitation stops waiting with nobody answering it, and the status that leaves. */
  enum Withdrawal {
    CANCELED_BY_ITS_INVITER(ProfileManagerInvitationStatus.CANCELED),
    CANCELED_BY_A_SERVER_ADMIN(ProfileManagerInvitationStatus.CANCELED),
    REPLACED_BY_A_NEWER_INVITATION(ProfileManagerInvitationStatus.INVALIDATED),
    INVITER_STOPPED_MANAGING(ProfileManagerInvitationStatus.INVALIDATED),
    PROFILE_TRANSFERRED(ProfileManagerInvitationStatus.INVALIDATED),
    PROFILE_CONNECTED(ProfileManagerInvitationStatus.INVALIDATED),
    INVITER_DELETED(ProfileManagerInvitationStatus.INVALIDATED);

    private final ProfileManagerInvitationStatus status;

    Withdrawal(ProfileManagerInvitationStatus status) {
      this.status = status;
    }
  }

  /** What becomes of the invited Account that ends the invitation for it alone. */
  enum AccountChange {
    BECAME_INELIGIBLE,
    GRANTED_MANAGEMENT,
    RELINQUISHED_MANAGEMENT
  }

  /**
   * How an invitation ends without an answer, and the status that leaves; one invalidated for its
   * Account still waits to the Profile's managers.
   */
  enum Ending {
    CANCELED(ProfileManagerInvitationStatus.CANCELED),
    INVALIDATED(ProfileManagerInvitationStatus.INVALIDATED),
    INVALIDATED_FOR_ITS_ACCOUNT(ProfileManagerInvitationStatus.PENDING),
    EXPIRED(ProfileManagerInvitationStatus.EXPIRED);

    private final ProfileManagerInvitationStatus status;

    Ending(ProfileManagerInvitationStatus status) {
      this.status = status;
    }
  }

  /** Who removes another Account's direct management of a Profile. */
  enum Remover {
    PROFILES_OWN_ACCOUNT,
    SERVER_ADMIN
  }

  @BeforeEach
  void setUp() {
    steps =
        ManagerInvitationSteps.builder()
            .management(profileManagement)
            .auth(authTestSupport)
            .invitations(invitationRepository)
            .transactions(transactionTemplate)
            .build();
    connections =
        ProfileConnections.builder()
            .credentialIssuance(credentialIssuance)
            .accountInvitations(accountInvitations)
            .invitations(accountInvitationRepository)
            .accounts(userAccountRepository)
            .auth(authTestSupport)
            .recipientEmail("connected.%s@example.com".formatted(UUID.randomUUID()))
            .build();
    rows = new StoredNotificationRows(dsl);
    sam = track(authTestSupport.createIdentity());
    cast.rename(sam, INVITER_NAME);
    mara = track(authTestSupport.createIdentity());
    alex = cast.profileManagedBy(sam, ProfileFixture.defaultProfileBuilder().name("Alex"));
  }

  @AfterEach
  void tearDown() {
    rows.deleteOf(identities.stream().map(ProfileManagementNotificationsIT::accountOf).toList());
    invitationRepository.deleteAll();
    connections.deleteConnected();
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName("Should give the invited Account a request when a manager invites its address")
  void shouldGiveTheInvitedAccountARequestWhenAManagerInvitesItsAddress() {
    var issued = steps.invite(sam, alex, mara.account().getEmail());

    assertThat(requestsAbout(issued))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.recipient()).isEqualTo(accountOf(mara));
              assertThat(request.kind()).isEqualTo(RECEIVED);
              assertThat(request.form()).isEqualTo("REQUEST");
              assertThat(request.snapshot())
                  .isEqualTo(
                      document(
                          new ProfileManagerInvitationReceivedSnapshot("Alex", Optional.empty())));
              assertThat(request.resolution()).isEmpty();
              assertThat(request.readAt()).isEmpty();
            });
    assertThat(rows.of(accountOf(sam))).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(Unanswerable.class)
  @DisplayName("Should make no request when no Account may accept the invitation")
  void shouldMakeNoRequestWhenNoAccountMayAcceptTheInvitation(Unanswerable invitee) {
    var issued = steps.invite(sam, alex, addressOf(invitee));

    assertThat(requestsAbout(issued)).isEmpty();
  }

  @Test
  @DisplayName("Should still give a request when the invited Account is disabled")
  void shouldStillGiveARequestWhenTheInvitedAccountIsDisabled() {
    cast.enable(mara, false);

    var issued = steps.invite(sam, alex, mara.account().getEmail());

    assertThat(requestsAbout(issued)).extracting(Row::recipient).containsExactly(accountOf(mara));
  }

  @Test
  @DisplayName("Should give no request when an Account takes the address after the invitation")
  void shouldGiveNoRequestWhenAnAccountTakesTheAddressAfterTheInvitation() {
    var address = nobody();
    var issued = steps.invite(sam, alex, address);

    var late = track(authTestSupport.createIdentityWithEmail(address));

    assertThat(requestsAbout(issued)).isEmpty();
    assertThat(rows.of(accountOf(late))).isEmpty();
  }

  @Test
  @DisplayName("Should store the invitation's expiry on the request when it is made")
  void shouldStoreTheInvitationsExpiryOnTheRequestWhenItIsMade() {
    var issued = steps.invite(sam, alex, mara.account().getEmail());

    var expiresAt = stored(issued).getExpiresAt();
    assertThat(requestsAbout(issued))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.expiresAt()).contains(expiresAt);
              assertThat(request.latestEventAt())
                  .isEqualTo(expiresAt.minus(invitationLifetime.invitationLifetime()));
            });
  }

  @Test
  @DisplayName("Should keep neither the code nor the inviter's name when the request is made")
  void shouldKeepNeitherTheCodeNorTheInvitersNameWhenTheRequestIsMade() {
    var issued = steps.invite(sam, alex, mara.account().getEmail());

    assertThat(requestsAbout(issued))
        .singleElement()
        .satisfies(
            request ->
                assertThat(request.snapshot().toString())
                    .contains("Alex")
                    .doesNotContain(issued.code())
                    .doesNotContain(INVITER_NAME));
  }

  @Test
  @DisplayName("Should make no Notification when the invitation that would make it rolls back")
  void shouldMakeNoNotificationWhenTheInvitationThatWouldMakeItRollsBack() {
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    var invitation =
        transactionTemplate.execute(
            status -> {
              var issued =
                  invitationLifecycle.issue(
                      ProfileManagerInvitation.builder()
                          .profileId(alex.getId())
                          .profileName(alex.getName())
                          .inviterAccountId(sam.account().getId())
                          .inviterDisplayName(INVITER_NAME)
                          .recipientAccountId(mara.account().getId())
                          .recipientEmail(mara.account().getEmail())
                          .expiresAt(now.plus(invitationLifetime.invitationLifetime()))
                          .publicId(UUID.randomUUID().toString())
                          .secretDigest(new byte[] {1})
                          .build(),
                      now);
              status.setRollbackOnly();
              return issued;
            });

    assertThat(rows.about(SubjectReference.profileManagerInvitation(invitation.getId()))).isEmpty();
    assertThat(rows.of(accountOf(mara))).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(ProfileManagerInvitationAnswer.class)
  @DisplayName(
      "Should resolve the request as decided with the answer when the invited Account accepts or"
          + " declines")
  void shouldResolveTheRequestAsDecidedWithTheAnswerWhenTheInvitedAccountAcceptsOrDeclines(
      ProfileManagerInvitationAnswer answer) {
    var issued = steps.invite(sam, alex, mara.account().getEmail());

    var outcome = answer(mara, issued, answer);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var decidedAt = stored(issued).getDecidedAt();
    assertThat(requestsAbout(issued))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.resolution()).contains("DECIDED");
              assertThat(request.resolvedAt()).contains(decidedAt);
              assertThat(request.snapshot())
                  .isEqualTo(
                      document(
                          new ProfileManagerInvitationReceivedSnapshot(
                              "Alex", Optional.of(answer))));
            });
  }

  @ParameterizedTest
  @EnumSource(Withdrawal.class)
  @DisplayName(
      "Should resolve the request as withdrawn when the invitation is canceled, replaced or"
          + " invalidated")
  void shouldResolveTheRequestAsWithdrawnWhenTheInvitationIsCanceledReplacedOrInvalidated(
      Withdrawal withdrawal) {
    var cam = coManagerOf(alex);
    var issued = steps.invite(cam, alex, mara.account().getEmail());

    withdraw(withdrawal, cam, issued);

    var withdrawn = stored(issued);
    assertThat(withdrawn.getStatus()).isEqualTo(withdrawal.status);
    assertWithdrawnAt(issued, withdrawn.getDecidedAt());
  }

  @ParameterizedTest
  @EnumSource(AccountChange.class)
  @DisplayName(
      "Should resolve the request as withdrawn when the invitation is invalidated for its Account")
  void shouldResolveTheRequestAsWithdrawnWhenTheInvitationIsInvalidatedForItsAccount(
      AccountChange change) {
    var ben = track(authTestSupport.createHouseholdMemberIdentity(sam));
    var issued = steps.invite(sam, alex, ben.account().getEmail());

    change(change, ben);

    var invalidated = stored(issued);
    assertThat(invalidated.getStatus()).isEqualTo(ProfileManagerInvitationStatus.PENDING);
    assertWithdrawnAt(issued, invalidated.getRecipientInvalidatedAt());
  }

  @Test
  @DisplayName(
      "Should keep both requests waiting until the Account accepts one when it is invited at two"
          + " addresses it has had")
  void
      shouldKeepBothRequestsWaitingUntilTheAccountAcceptsOneWhenItIsInvitedAtTwoAddressesItHasHad() {
    var toOldAddress = steps.invite(sam, alex, mara.account().getEmail());
    var newAddress = "mara.%s@example.com".formatted(UUID.randomUUID());
    changeEmail(mara, newAddress);
    var toNewAddress = steps.invite(sam, alex, newAddress);
    assertThat(List.of(requestsAbout(toOldAddress), requestsAbout(toNewAddress)))
        .hasSize(2)
        .allSatisfy(
            requests ->
                assertThat(requests)
                    .singleElement()
                    .satisfies(request -> assertThat(request.resolution()).isEmpty()));

    var outcome = answer(mara, toNewAddress, ProfileManagerInvitationAnswer.ACCEPTED);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(requestsAbout(toNewAddress))
        .singleElement()
        .satisfies(request -> assertThat(request.resolution()).contains("DECIDED"));
    assertWithdrawnAt(toOldAddress, stored(toOldAddress).getRecipientInvalidatedAt());
  }

  @ParameterizedTest
  @EnumSource(Ending.class)
  @DisplayName(
      "Should tell nobody anything new when an invitation is canceled, invalidated or expires")
  void shouldTellNobodyAnythingNewWhenAnInvitationIsCanceledInvalidatedOrExpires(Ending ending) {
    var cam = coManagerOf(alex);
    var issued = steps.invite(sam, alex, mara.account().getEmail());

    end(ending, issued);

    assertThat(stored(issued).getStatus()).isEqualTo(ending.status);
    assertThat(rows.about(SubjectReference.profileManagerInvitation(issued.invitation().getId())))
        .extracting(Row::kind)
        .containsExactly(RECEIVED);
    assertThat(rows.of(accountOf(mara))).extracting(Row::kind).containsExactly(RECEIVED);
    assertThat(rows.of(accountOf(sam))).isEmpty();
    assertThat(rows.of(accountOf(cam))).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(ProfileManagerInvitationAnswer.class)
  @DisplayName("Should tell the inviter when the invited Account accepts or declines")
  void shouldTellTheInviterWhenTheInvitedAccountAcceptsOrDeclines(
      ProfileManagerInvitationAnswer answer) {
    var cam = coManagerOf(alex);
    var issued = steps.invite(sam, alex, mara.account().getEmail());

    var outcome = answer(mara, issued, answer);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var decidedAt = stored(issued).getDecidedAt();
    assertThat(answersAbout(issued))
        .singleElement()
        .satisfies(
            told -> {
              assertThat(told.recipient()).isEqualTo(accountOf(sam));
              assertThat(told.form()).isEqualTo("NEWS");
              assertThat(told.snapshot())
                  .isEqualTo(
                      document(new ProfileManagerInvitationAnsweredSnapshot("Alex", answer)));
              assertThat(told.latestEventAt()).isEqualTo(decidedAt);
              assertThat(told.readAt()).isEmpty();
            });
    assertThat(rows.of(accountOf(mara))).extracting(Row::kind).containsExactly(RECEIVED);
    assertThat(rows.of(accountOf(cam))).isEmpty();
  }

  @Test
  @DisplayName("Should tell nobody when the acceptance finds the inviter no longer managing")
  void shouldTellNobodyWhenTheAcceptanceFindsTheInviterNoLongerManaging() {
    var cam = coManagerOf(alex);
    var issued = steps.invite(cam, alex, mara.account().getEmail());
    revokeLeavingItsInvitationsWaiting(cam, alex);

    var outcome = answer(mara, issued, ProfileManagerInvitationAnswer.ACCEPTED);

    assertThat(outcome).isEqualTo(Outcome.rejected(new ManagerRejections.InvitationNotPending()));
    var invalidated = stored(issued);
    assertThat(invalidated.getStatus()).isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertWithdrawnAt(issued, invalidated.getDecidedAt());
    assertThat(answersAbout(issued)).isEmpty();
    assertThat(rows.of(accountOf(cam))).isEmpty();
    assertThat(rows.of(accountOf(sam))).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep the answer and its Profile's name when the inviter stops managing the Profile")
  void shouldKeepTheAnswerAndItsProfileNameWhenTheInviterStopsManagingTheProfile() {
    var cam = coManagerOf(alex);
    var issued = steps.invite(cam, alex, mara.account().getEmail());
    assertThat(answer(mara, issued, ProfileManagerInvitationAnswer.DECLINED))
        .isInstanceOf(Outcome.Accepted.class);

    var outcome = profileManagement.relinquishProfileManagement(identityOf(cam), alex.getId());
    cast.rename(alex, "Alexandra");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(rows.of(accountOf(cam)))
        .singleElement()
        .satisfies(
            told -> {
              assertThat(told.kind()).isEqualTo(ANSWERED);
              assertThat(told.snapshot())
                  .isEqualTo(
                      document(
                          new ProfileManagerInvitationAnsweredSnapshot(
                              "Alex", ProfileManagerInvitationAnswer.DECLINED)));
              assertThat(told.readAt()).isEmpty();
            });
  }

  @Test
  @DisplayName("Should make no Notification when the acceptance that would make it rolls back")
  void shouldMakeNoNotificationWhenTheAcceptanceThatWouldMakeItRollsBack() {
    var issued = steps.invite(sam, alex, mara.account().getEmail());

    var accepted =
        transactionTemplate.execute(
            status -> {
              var changed =
                  invitationLifecycle.tryAcceptPending(
                      issued.invitation().getId(), mara.account().getId(), Instant.now());
              status.setRollbackOnly();
              return changed;
            });

    assertThat(accepted).isTrue();
    assertThat(stored(issued).getStatus()).isEqualTo(ProfileManagerInvitationStatus.PENDING);
    assertThat(rows.of(accountOf(sam))).isEmpty();
    assertThat(requestsAbout(issued))
        .singleElement()
        .satisfies(request -> assertThat(request.resolution()).isEmpty());
  }

  @ParameterizedTest
  @EnumSource(Remover.class)
  @DisplayName(
      "Should tell the removed manager the Profile's name when the Profile's own Account or a"
          + " ServerAdmin removes it")
  void shouldTellTheRemovedManagerTheProfilesNameWhenTheProfilesOwnAccountOrAServerAdminRemovesIt(
      Remover remover) {
    var olive = track(authTestSupport.createHouseholdMemberIdentity(sam));
    cast.rename(olive.profile(), "Olive");
    cast.grant(mara, olive.profile());
    var sol = serverAdmin();
    var before = Instant.now().truncatedTo(ChronoUnit.MICROS);

    var outcome =
        switch (remover) {
          case PROFILES_OWN_ACCOUNT ->
              profileManagement.removeProfileManager(
                  identityOf(olive), olive.profile().getId(), mara.account().getId());
          case SERVER_ADMIN -> removeAdministratively(sol, olive.profile(), mara);
        };

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(rows.about(SubjectReference.profile(olive.profile().getId())))
        .singleElement()
        .satisfies(
            told -> {
              assertThat(told.recipient()).isEqualTo(accountOf(mara));
              assertThat(told.kind()).isEqualTo(REMOVED);
              assertThat(told.form()).isEqualTo("NEWS");
              assertThat(told.snapshot())
                  .isEqualTo(document(new ProfileManagementRemovedSnapshot("Olive")));
              assertThat(told.latestEventAt()).isBetween(before, Instant.now());
              assertThat(told.readAt()).isEmpty();
            });
    assertThat(rows.of(accountOf(olive))).isEmpty();
    assertThat(rows.of(accountOf(sol))).isEmpty();
  }

  @Test
  @DisplayName("Should tell nobody when a manager relinquishes its own management")
  void shouldTellNobodyWhenAManagerRelinquishesItsOwnManagement() {
    cast.grant(mara, alex);

    var outcome = profileManagement.relinquishProfileManagement(identityOf(mara), alex.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(rows.about(SubjectReference.profile(alex.getId()))).isEmpty();
    assertThat(rows.of(accountOf(mara))).isEmpty();
  }

  @Test
  @DisplayName("Should tell nobody when a ServerAdmin removes its own management")
  void shouldTellNobodyWhenAServerAdminRemovesItsOwnManagement() {
    var sol = serverAdmin();
    cast.grant(sol, alex);

    var outcome = removeAdministratively(sol, alex, sol);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(rows.about(SubjectReference.profile(alex.getId()))).isEmpty();
    assertThat(rows.of(accountOf(sol))).isEmpty();
  }

  @Test
  @DisplayName("Should tell nobody when the removal that would tell it rolls back")
  void shouldTellNobodyWhenTheRemovalThatWouldTellItRollsBack() {
    // Mara is the Kid Profile's only eligible manager, which its Household's rules check at commit.
    var kit = cast.profileManagedBy(mara, ProfileFixture.kidProfileBuilder().name("Kit"));

    var outcome = removeAdministratively(serverAdmin(), kit, mara);

    assertThat(outcome)
        .isEqualTo(Outcome.rejected(new ManagerRejections.EligibleManagerRequired()));
    assertThat(rows.about(SubjectReference.profile(kit.getId()))).isEmpty();
    assertThat(rows.of(accountOf(mara))).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep one unread Management removed with the second removal's time and name when it"
          + " is removed, granted and removed again before it is read")
  void
      shouldKeepOneUnreadManagementRemovedWithTheSecondRemovalsTimeAndNameWhenItIsRemovedGrantedAndRemovedAgainBeforeItIsRead() {
    var sol = serverAdmin();
    cast.grant(mara, alex);
    assertThat(removeAdministratively(sol, alex, mara)).isInstanceOf(Outcome.Accepted.class);
    var first = onlyNotificationOf(mara);
    cast.grant(mara, alex);
    cast.rename(alex, "Alexandra");
    var before = Instant.now().truncatedTo(ChronoUnit.MICROS);

    var outcome = removeAdministratively(sol, alex, mara);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(rows.of(accountOf(mara)))
        .singleElement()
        .satisfies(
            told -> {
              assertThat(told.id()).isEqualTo(first.id());
              assertThat(told.kind()).isEqualTo(REMOVED);
              assertThat(told.snapshot())
                  .isEqualTo(document(new ProfileManagementRemovedSnapshot("Alexandra")));
              assertThat(told.createdAt()).isEqualTo(first.createdAt());
              assertThat(told.latestEventAt()).isBetween(before, Instant.now());
              assertThat(told.readAt()).isEmpty();
            });
  }

  @Test
  @DisplayName(
      "Should make a new Management removed when the Account read the first before the next"
          + " removal")
  void shouldMakeANewManagementRemovedWhenTheAccountReadTheFirstBeforeTheNextRemoval() {
    var sol = serverAdmin();
    cast.grant(mara, alex);
    assertThat(removeAdministratively(sol, alex, mara)).isInstanceOf(Outcome.Accepted.class);
    var first = onlyNotificationOf(mara);
    assertThat(notificationService.markNotificationsRead(identityOf(mara), List.of(first.id())))
        .isEqualTo(Outcome.accepted(0));
    cast.grant(mara, alex);

    var outcome = removeAdministratively(sol, alex, mara);

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(rows.of(accountOf(mara)))
        .satisfiesExactly(
            read -> {
              assertThat(read.id()).isEqualTo(first.id());
              assertThat(read.readAt()).isPresent();
            },
            unread -> {
              assertThat(unread.id()).isNotEqualTo(first.id());
              assertThat(unread.kind()).isEqualTo(REMOVED);
              assertThat(unread.readAt()).isEmpty();
            });
  }

  @Test
  @DisplayName("Should keep the name it was told when the Profile is renamed after the removal")
  void shouldKeepTheNameItWasToldWhenTheProfileIsRenamedAfterTheRemoval() {
    cast.grant(mara, alex);
    assertThat(removeAdministratively(serverAdmin(), alex, mara))
        .isInstanceOf(Outcome.Accepted.class);

    cast.rename(alex, "Alexandra");

    assertThat(onlyNotificationOf(mara).snapshot())
        .isEqualTo(document(new ProfileManagementRemovedSnapshot("Alex")));
  }

  private Outcome<?, ?> answer(
      TestIdentity invitee, IssuedManagerInvitation issued, ProfileManagerInvitationAnswer answer) {
    var invitationId = issued.invitation().getId();
    return switch (answer) {
      case ACCEPTED -> profileManagement.acceptManagerInvitation(identityOf(invitee), invitationId);
      case DECLINED ->
          profileManagement.declineManagerInvitation(identityOf(invitee), invitationId);
    };
  }

  private void withdraw(
      Withdrawal withdrawal, TestIdentity inviter, IssuedManagerInvitation issued) {
    var invitationId = issued.invitation().getId();
    Outcome<?, ?> outcome =
        switch (withdrawal) {
          case CANCELED_BY_ITS_INVITER ->
              profileManagement.cancelManagerInvitation(identityOf(inviter), invitationId);
          case CANCELED_BY_A_SERVER_ADMIN ->
              profileManagement.cancelManagerInvitation(identityOf(serverAdmin()), invitationId);
          case REPLACED_BY_A_NEWER_INVITATION ->
              Outcome.accepted(steps.invite(inviter, alex, mara.account().getEmail()));
          case INVITER_STOPPED_MANAGING ->
              profileManagement.relinquishProfileManagement(identityOf(inviter), alex.getId());
          case PROFILE_TRANSFERRED -> transfer(alex);
          case PROFILE_CONNECTED -> Outcome.accepted(connections.connect(alex, serverAdmin()));
          case INVITER_DELETED ->
              accountLifecycle.deleteMyAccount(authTestSupport.freshIdentityOf(inviter), "DELETE");
        };
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
  }

  private Outcome<?, ?> transfer(Profile profile) {
    var host = track(authTestSupport.createIdentity());
    return profileLifecycle.transferProfile(
        authTestSupport.freshIdentityOf(serverAdmin()),
        TransferProfileCommand.builder()
            .profileId(profile.getId())
            .destinationHouseholdId(host.household().getId())
            .localManagerAccountId(host.account().getId())
            .reason("moved out")
            .build());
  }

  private void change(AccountChange change, TestIdentity invitee) {
    Outcome<?, ?> outcome =
        switch (change) {
          case BECAME_INELIGIBLE -> {
            // A restricted Profile needs an eligible manager besides its own Account.
            cast.grant(sam, invitee.profile());
            yield profileAdministration.setProfileContentCeiling(
                authTestSupport.freshIdentityOf(serverAdmin()), invitee.profile().getId(), 12);
          }
          case GRANTED_MANAGEMENT ->
              profileManagement.administrativelyGrantProfileManager(
                  authTestSupport.freshIdentityOf(serverAdmin()),
                  alex.getId(),
                  invitee.account().getId(),
                  "support");
          case RELINQUISHED_MANAGEMENT -> {
            // No invite names a manager; a grant that raced in after the invite leaves it waiting.
            cast.grant(invitee, alex);
            yield profileManagement.relinquishProfileManagement(identityOf(invitee), alex.getId());
          }
        };
    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
  }

  private void end(Ending ending, IssuedManagerInvitation issued) {
    switch (ending) {
      case CANCELED ->
          assertThat(
                  profileManagement.cancelManagerInvitation(
                      identityOf(sam), issued.invitation().getId()))
              .isInstanceOf(Outcome.Accepted.class);
      case INVALIDATED ->
          assertThat(profileManagement.relinquishProfileManagement(identityOf(sam), alex.getId()))
              .isInstanceOf(Outcome.Accepted.class);
      case INVALIDATED_FOR_ITS_ACCOUNT -> change(AccountChange.GRANTED_MANAGEMENT, mara);
      case EXPIRED -> {
        steps.lapse(issued.invitation());
        expirySweeper.sweep();
      }
    }
  }

  // Another manager of the Profile, which the Profile keeps when this one leaves it.
  private TestIdentity coManagerOf(Profile profile) {
    var coManager = track(authTestSupport.createHouseholdMemberIdentity(sam));
    cast.grant(coManager, profile);
    return coManager;
  }

  private TestIdentity serverAdmin() {
    return track(authTestSupport.createAdminIdentity());
  }

  private void assertWithdrawnAt(IssuedManagerInvitation issued, Instant withdrawnAt) {
    assertThat(requestsAbout(issued))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.resolution()).contains("WITHDRAWN");
              assertThat(request.resolvedAt()).contains(withdrawnAt);
              assertThat(request.snapshot())
                  .isEqualTo(
                      document(
                          new ProfileManagerInvitationReceivedSnapshot("Alex", Optional.empty())));
            });
  }

  private void changeEmail(TestIdentity identity, String email) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
          account.setEmail(email);
          userAccountRepository.saveAndFlush(account);
        });
  }

  private String addressOf(Unanswerable invitee) {
    return switch (invitee) {
      case NO_ACCOUNT -> nobody();
      case INELIGIBLE_ACCOUNT -> track(cast.supervisedMemberOf(sam)).account().getEmail();
      case MANAGING_ACCOUNT -> {
        var manager = track(authTestSupport.createIdentity());
        cast.grant(manager, alex);
        yield manager.account().getEmail();
      }
    };
  }

  private Outcome<?, ?> removeAdministratively(
      TestIdentity serverAdmin, Profile profile, TestIdentity manager) {
    return profileManagement.administrativelyRemoveProfileManager(
        authTestSupport.freshIdentityOf(serverAdmin),
        profile.getId(),
        manager.account().getId(),
        "no longer looks after " + profile.getName());
  }

  private Row onlyNotificationOf(TestIdentity recipient) {
    var told = rows.of(accountOf(recipient));
    assertThat(told).hasSize(1);
    return told.getFirst();
  }

  // In the database alone, so the invitations the manager sent still wait when they are answered.
  private void revokeLeavingItsInvitationsWaiting(TestIdentity manager, Profile profile) {
    transactionTemplate.executeWithoutResult(
        _ ->
            assertThat(
                    profileManagerRepository.tryRevokeDirectManagement(
                        manager.account().getId(), profile.getId()))
                .isTrue());
  }

  private List<Row> requestsAbout(IssuedManagerInvitation issued) {
    return aboutOfKind(issued, RECEIVED);
  }

  private List<Row> answersAbout(IssuedManagerInvitation issued) {
    return aboutOfKind(issued, ANSWERED);
  }

  private List<Row> aboutOfKind(IssuedManagerInvitation issued, String kind) {
    return rows
        .about(SubjectReference.profileManagerInvitation(issued.invitation().getId()))
        .stream()
        .filter(row -> row.kind().equals(kind))
        .toList();
  }

  private ProfileManagerInvitation stored(IssuedManagerInvitation issued) {
    return invitationRepository.findById(issued.invitation().getId()).orElseThrow();
  }

  private static String nobody() {
    return "nobody.%s@example.com".formatted(UUID.randomUUID());
  }

  private AuthenticatedIdentity identityOf(TestIdentity identity) {
    return authTestSupport.identityOf(identity);
  }

  private static RecipientReference accountOf(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
