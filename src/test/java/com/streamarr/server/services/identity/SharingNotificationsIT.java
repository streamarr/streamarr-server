package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.Notification.NOTIFICATION;
import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static com.streamarr.server.support.StoredNotificationRows.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.auth.SourceHouseholdAccess;
import com.streamarr.server.properties.CredentialCodeProperties;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccountInvitationService;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.auth.ProfileShareExpirySweeper;
import com.streamarr.server.services.auth.ProfileShareLifecycle;
import com.streamarr.server.services.events.notification.ProfileShareAnswer;
import com.streamarr.server.services.events.notification.ProfileShareAnsweredSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareEndedBy;
import com.streamarr.server.services.events.notification.ProfileShareEndedSnapshot;
import com.streamarr.server.services.events.notification.ProfileShareOfferedSnapshot;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.SubjectReference;
import com.streamarr.server.services.identity.AccountLifecycleService.TransferAccountCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.TransferLastAccountAndDeleteHouseholdCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ProfileConnections;
import com.streamarr.server.support.ShareSteps;
import com.streamarr.server.support.StoredNotificationRows;
import com.streamarr.server.support.StoredNotificationRows.Row;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Who the identity domain tells about a Profile share, and what (ADR 0063 §The sharing kinds). Each
 * share write is made as a person makes it, and the Notifications it made are read as they are
 * stored.
 */
@Tag("IntegrationTest")
@DisplayName("Sharing Notifications Integration Tests")
class SharingNotificationsIT extends AbstractIntegrationTest {

  private static final String CONNECTED_EMAIL = "ollie@example.com";
  private static final String OFFERED = "PROFILE_SHARE_OFFERED";
  private static final String ANSWERED = "PROFILE_SHARE_ANSWERED";
  private static final String ENDED = "PROFILE_SHARE_ENDED";

  @Autowired private ProfileSharingService sharing;
  @Autowired private ProfileShareLifecycle shareLifecycle;
  @Autowired private ProfileShareExpirySweeper expirySweeper;
  @Autowired private AccountAdministrationService accountAdministration;
  @Autowired private AccountLifecycleService accountLifecycle;
  @Autowired private HouseholdDeletionService householdDeletion;
  @Autowired private ProfileManagerAdministrationService profileManagement;
  @Autowired private ProfileAdministrationService profileAdministration;
  @Autowired private ProfileLifecycleService profileLifecycle;
  @Autowired private CredentialIssuanceService credentialIssuance;
  @Autowired private AccountInvitationService accountInvitations;
  @Autowired private AccountInvitationRepository invitationRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private CredentialCodeProperties credentialCodeProperties;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private MockMvc mockMvc;

  private final List<TestIdentity> identities = new ArrayList<>();
  private final List<UUID> connectedAccountIds = new ArrayList<>();
  private TestIdentity sam;
  private TestIdentity riverside;
  private TestIdentity riversideAdmin;
  private ShareSteps steps;
  private ProfileConnections connections;
  private StoredNotificationRows rows;

  @BeforeEach
  void setUp() {
    steps =
        ShareSteps.builder()
            .sharing(sharing)
            .auth(authTestSupport)
            .profiles(profileRepository)
            .managers(profileManagerRepository)
            .shares(shareRepository)
            .accounts(userAccountRepository)
            .transactions(transactionTemplate)
            .build();
    connections =
        ProfileConnections.builder()
            .credentialIssuance(credentialIssuance)
            .accountInvitations(accountInvitations)
            .invitations(invitationRepository)
            .accounts(userAccountRepository)
            .auth(authTestSupport)
            .recipientEmail(CONNECTED_EMAIL)
            .build();
    rows = new StoredNotificationRows(dsl);
    sam = track(authTestSupport.createIdentity());
    riverside = track(authTestSupport.createIdentity());
    riversideAdmin =
        steps.promoted(track(authTestSupport.createHouseholdMemberIdentity(riverside)));
  }

  @AfterEach
  void tearDown() {
    var recipientIds = new ArrayList<>(connectedAccountIds);
    identities.forEach(identity -> recipientIds.add(identity.account().getId()));
    dsl.deleteFrom(NOTIFICATION).where(NOTIFICATION.RECIPIENT_ID.in(recipientIds)).execute();
    connections.deleteConnected();
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should give each HouseholdAdmin of the target Household a request when a Profile is offered")
  void shouldGiveEachHouseholdAdminOfTheTargetHouseholdARequestWhenAProfileIsOffered() {
    var offer = steps.offer(sam, sam.profile(), riverside);

    var stored = stored(offer);
    var offeredAt = stored.getExpiresAt().minus(credentialCodeProperties.invitationTtl());
    assertThat(requestsAbout(offer))
        .allSatisfy(
            request -> {
              assertThat(request.form()).isEqualTo("REQUEST");
              assertThat(request.snapshot())
                  .isEqualTo(
                      document(
                          new ProfileShareOfferedSnapshot(
                              sam.profile().getName(), Optional.empty())));
              assertThat(request.expiresAt()).contains(stored.getExpiresAt());
              assertThat(request.latestEventAt()).isEqualTo(offeredAt);
              assertThat(request.resolution()).isEmpty();
            })
        .extracting(Row::recipient)
        .containsExactlyInAnyOrder(account(riverside), account(riversideAdmin));
  }

  @Test
  @DisplayName(
      "Should tell the Account that made the offer nothing when it administers the target"
          + " Household")
  void shouldTellTheAccountThatMadeTheOfferNothingWhenItAdministersTheTargetHousehold() {
    var kai = track(authTestSupport.createIdentity());
    var ollie = steps.profileManagedBy("Ollie", kai, kai, riverside);

    var offer = steps.offer(riverside, ollie, riverside);

    assertThat(requestsAbout(offer))
        .extracting(Row::recipient)
        .containsExactly(account(riversideAdmin));
  }

  @Test
  @DisplayName("Should give no request to a HouseholdAdmin when it was promoted after the offer")
  void shouldGiveNoRequestToAHouseholdAdminWhenItWasPromotedAfterTheOffer() {
    var member = track(authTestSupport.createHouseholdMemberIdentity(riverside));
    var offer = steps.offer(sam, sam.profile(), riverside);

    steps.promoted(member);

    assertThat(rows.of(account(member))).isEmpty();
    assertThat(requestsAbout(offer)).hasSize(2);
  }

  @Test
  @DisplayName("Should still give a request to a HouseholdAdmin when its Account is disabled")
  void shouldStillGiveARequestToAHouseholdAdminWhenItsAccountIsDisabled() {
    transactionTemplate.executeWithoutResult(
        _ -> userAccountRepository.tryDisable(riversideAdmin.account().getId()));

    var offer = steps.offer(sam, sam.profile(), riverside);

    assertThat(requestsAbout(offer)).extracting(Row::recipient).contains(account(riversideAdmin));
  }

  @Test
  @DisplayName(
      "Should give no request to a ServerAdmin who does not administer the target Household")
  void shouldGiveNoRequestToAServerAdminWhoDoesNotAdministerTheTargetHousehold() {
    var serverAdmin = track(authTestSupport.createAdminIdentity());

    var offer = steps.offer(sam, sam.profile(), riverside);

    assertThat(rows.of(account(serverAdmin))).isEmpty();
    assertThat(requestsAbout(offer))
        .extracting(Row::recipient)
        .containsExactlyInAnyOrder(account(riverside), account(riversideAdmin));
  }

  @Test
  @DisplayName(
      "Should give each HouseholdAdmin a request when connecting a Profile offers it to them again")
  void shouldGiveEachHouseholdAdminARequestWhenConnectingAProfileOffersItToThemAgain() {
    var ollie = steps.profileManagedBy("Ollie", sam, sam);
    steps.accept(riverside, steps.offer(sam, ollie, riverside));

    connect(ollie, riverside);

    var reoffer =
        shareRepository.findByProfileId(ollie.getId()).stream()
            .filter(share -> share.getStatus() == ProfileShareStatus.PENDING)
            .filter(share -> share.getHouseholdId().equals(riverside.household().getId()))
            .findFirst()
            .orElseThrow();
    assertThat(requestsAbout(reoffer))
        .allSatisfy(
            request ->
                assertThat(request.snapshot())
                    .isEqualTo(
                        document(new ProfileShareOfferedSnapshot("Ollie", Optional.empty()))))
        .extracting(Row::recipient)
        .containsExactlyInAnyOrder(account(riverside), account(riversideAdmin));
  }

  @Test
  @DisplayName(
      "Should keep nothing of the Accounts that acted when an offer and its rejection tell both"
          + " sides")
  void shouldKeepNothingOfTheAccountsThatActedWhenAnOfferAndItsRejectionTellBothSides()
      throws Exception {
    var offer =
        UUID.fromString(
            graphql(
                sam,
                "$.data.offerProfileShare.share.id",
                """
                mutation { offerProfileShare(input: {profileId: "%s", shareCode: "%s"}) {
                  share { id } userErrors { __typename } } }
                """
                    .formatted(sam.profile().getId(), authTestSupport.shareCodeFor(riverside))));

    graphql(
        riversideAdmin,
        "$.data.rejectProfileShare.share.status",
        """
        mutation { rejectProfileShare(input: {shareId: "%s"}) {
          share { status } userErrors { __typename } } }
        """
            .formatted(offer));

    var actors =
        new String[] {
          sam.account().getId().toString(), riversideAdmin.account().getId().toString()
        };
    assertThat(storedCopiesAbout(offer))
        .hasSize(3)
        .allSatisfy(stored -> assertThat(stored).doesNotContain(actors));
  }

  @Test
  @DisplayName("Should make no Notification when the offer that would make it rolls back")
  void shouldMakeNoNotificationWhenTheOfferThatWouldMakeItRollsBack() {
    var now = Instant.now();

    var offer =
        transactionTemplate.execute(
            status -> {
              var made =
                  shareLifecycle.create(
                      ProfileHouseholdShare.builder()
                          .profileId(sam.profile().getId())
                          .householdId(riverside.household().getId())
                          .status(ProfileShareStatus.PENDING)
                          .offeredByAccountId(sam.account().getId())
                          .expiresAt(now.plus(credentialCodeProperties.invitationTtl()))
                          .build(),
                      now);
              status.setRollbackOnly();
              return made;
            });

    assertThat(rows.about(SubjectReference.profileShare(offer.getId()))).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(ProfileShareAnswer.class)
  @DisplayName(
      "Should resolve the requests as decided with the answer when the offer is accepted or"
          + " rejected")
  void shouldResolveTheRequestsAsDecidedWithTheAnswerWhenTheOfferIsAcceptedOrRejected(
      ProfileShareAnswer answer) {
    var offer = steps.offer(sam, sam.profile(), riverside);

    Outcome<?, ?> outcome =
        switch (answer) {
          case ACCEPTED -> sharing.acceptProfileShare(identityOf(riverside), offer.getId());
          case REJECTED -> sharing.rejectProfileShare(identityOf(riverside), offer.getId());
        };

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var decidedAt = stored(offer).getDecidedAt();
    assertThat(requestsAbout(offer))
        .hasSize(2)
        .allSatisfy(
            request -> {
              assertThat(request.resolution()).contains("DECIDED");
              assertThat(request.resolvedAt()).contains(decidedAt);
              assertThat(request.snapshot())
                  .isEqualTo(
                      document(
                          new ProfileShareOfferedSnapshot(
                              sam.profile().getName(), Optional.of(answer))));
            });
  }

  @ParameterizedTest
  @EnumSource(Withdrawal.class)
  @DisplayName(
      "Should resolve the requests as withdrawn when the offer is canceled, replaced or"
          + " invalidated")
  void shouldResolveTheRequestsAsWithdrawnWhenTheOfferIsCanceledReplacedOrInvalidated(
      Withdrawal withdrawal) {
    var theo = track(authTestSupport.createHouseholdMemberIdentity(sam));
    var kai = steps.profileManagedBy("Kai", sam, sam, theo);
    var offer = steps.offer(theo, kai, riverside);

    switch (withdrawal) {
      case CANCELED -> sharing.cancelProfileShare(identityOf(theo), offer.getId());
      case REPLACED -> steps.offer(theo, kai, riverside);
      case OFFERER_NO_LONGER_AUTHORIZED -> {
        // Disabled in the database alone, so the offer still waits when the Household accepts it.
        var offerer = userAccountRepository.findById(theo.account().getId()).orElseThrow();
        offerer.setEnabled(false);
        userAccountRepository.saveAndFlush(offerer);
        sharing.acceptProfileShare(identityOf(riverside), offer.getId());
      }
    }

    assertThat(stored(offer).getStatus()).isEqualTo(withdrawal.status);
    assertWithdrawn(offer);
  }

  /** How an offer stops waiting with nobody deciding it. */
  enum Withdrawal {
    CANCELED(ProfileShareStatus.CANCELED),
    REPLACED(ProfileShareStatus.CANCELED),
    OFFERER_NO_LONGER_AUTHORIZED(ProfileShareStatus.INVALIDATED);

    private final ProfileShareStatus status;

    Withdrawal(ProfileShareStatus status) {
      this.status = status;
    }
  }

  @Test
  @DisplayName(
      "Should give new requests and withdraw the older ones when an offer replaces an older offer")
  void shouldGiveNewRequestsAndWithdrawTheOlderOnesWhenAnOfferReplacesAnOlderOffer() {
    var older = steps.offer(sam, sam.profile(), riverside);

    var newer = steps.offer(sam, sam.profile(), riverside);

    assertWithdrawn(older);
    assertThat(requestsAbout(newer))
        .allSatisfy(request -> assertThat(request.resolution()).isEmpty())
        .extracting(Row::recipient)
        .containsExactlyInAnyOrder(account(riverside), account(riversideAdmin));
  }

  @Test
  @DisplayName(
      "Should resolve the requests as expired when the expiry sweep stores the offer's expiry")
  void shouldResolveTheRequestsAsExpiredWhenTheExpirySweepStoresTheOffersExpiry() {
    var offer = steps.offer(sam, sam.profile(), riverside);
    steps.lapse(offer);

    expirySweeper.sweep();

    assertThat(stored(offer).getStatus()).isEqualTo(ProfileShareStatus.EXPIRED);
    assertExpired(offer);
  }

  @Test
  @DisplayName(
      "Should resolve the requests as expired when a rejection comes after the offer's time ran"
          + " out")
  void shouldResolveTheRequestsAsExpiredWhenARejectionComesAfterTheOffersTimeRanOut() {
    var offer = steps.offer(sam, sam.profile(), riverside);
    steps.lapse(offer);
    // A ServerAdmin still reads an offer whose time ran out, so its rejection reaches the offer.
    var serverAdmin = track(authTestSupport.createAdminIdentity());

    var outcome = sharing.rejectProfileShare(identityOf(serverAdmin), offer.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(stored(offer).getStatus()).isEqualTo(ProfileShareStatus.EXPIRED);
    assertExpired(offer);
  }

  @Test
  @DisplayName(
      "Should resolve the request as withdrawn when an Account transfer makes the offer its"
          + " Profile's home share")
  void shouldResolveTheRequestAsWithdrawnWhenAnAccountTransferMakesTheOfferItsProfilesHomeShare() {
    var ben = track(authTestSupport.createHouseholdMemberIdentity(riverside));
    var offer = steps.offer(ben, ben.profile(), sam);
    var serverAdmin = track(authTestSupport.createAdminIdentity());

    var outcome =
        accountLifecycle.transferAccount(
            authTestSupport.freshIdentityOf(serverAdmin),
            TransferAccountCommand.builder()
                .accountId(ben.account().getId())
                .destinationHouseholdId(sam.household().getId())
                .sourceHouseholdAccess(SourceHouseholdAccess.END)
                .reason("moved in with Sam")
                .build());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var home = stored(offer);
    assertThat(home.getStatus()).isEqualTo(ProfileShareStatus.ACTIVE);
    assertThat(requestsAbout(offer))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.recipient()).isEqualTo(account(sam));
              assertThat(request.resolution()).contains("WITHDRAWN");
              assertThat(request.resolvedAt()).contains(home.getDecidedAt());
              assertThat(request.snapshot())
                  .isEqualTo(
                      document(
                          new ProfileShareOfferedSnapshot(
                              ben.profile().getName(), Optional.empty())));
            });
    assertThat(answersAbout(offer)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(OffererChange.class)
  @DisplayName(
      "Should withdraw the requests when the Account that made the offer is disabled, deleted or"
          + " loses ServerAdmin")
  void shouldWithdrawTheRequestsWhenTheAccountThatMadeTheOfferIsDisabledDeletedOrLosesServerAdmin(
      OffererChange change) {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var offerer =
        switch (change) {
          case DISABLED, DELETED -> track(authTestSupport.createHouseholdMemberIdentity(sam));
          case SERVER_ADMIN_REVOKED -> track(authTestSupport.createAdminIdentity());
        };
    var kai =
        switch (change) {
          case DISABLED, DELETED -> steps.profileManagedBy("Kai", sam, sam, offerer);
          case SERVER_ADMIN_REVOKED -> steps.profileManagedBy("Kai", sam, sam);
        };
    var offer = steps.offer(offerer, kai, riverside);

    Outcome<?, ?> outcome =
        switch (change) {
          case DISABLED ->
              accountAdministration.disableAccount(
                  authTestSupport.freshIdentityOf(serverAdmin), offerer.account().getId());
          case DELETED ->
              accountLifecycle.deleteMyAccount(authTestSupport.freshIdentityOf(offerer), "DELETE");
          case SERVER_ADMIN_REVOKED ->
              accountAdministration.revokeServerAdmin(
                  authTestSupport.freshIdentityOf(serverAdmin),
                  offerer.account().getId(),
                  "rotation");
        };

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(stored(offer).getStatus()).isEqualTo(ProfileShareStatus.INVALIDATED);
    assertWithdrawn(offer);
  }

  /** What becomes of the Account that made an offer while the offer waits. */
  enum OffererChange {
    DISABLED,
    DELETED,
    SERVER_ADMIN_REVOKED
  }

  @Test
  @DisplayName(
      "Should withdraw the requests when the manager that made the offer stops managing the"
          + " Profile")
  void shouldWithdrawTheRequestsWhenTheManagerThatMadeTheOfferStopsManagingTheProfile() {
    var theo = track(authTestSupport.createHouseholdMemberIdentity(sam));
    var kai = steps.profileManagedBy("Kai", sam, sam, theo);
    var offer = steps.offer(theo, kai, riverside);

    var outcome = profileManagement.relinquishProfileManagement(identityOf(theo), kai.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(stored(offer).getStatus()).isEqualTo(ProfileShareStatus.INVALIDATED);
    assertWithdrawn(offer);
  }

  @Test
  @DisplayName(
      "Should tell the offering side, except the Account that answered, when an offer is accepted")
  void shouldTellTheOfferingSideExceptTheAccountThatAnsweredWhenAnOfferIsAccepted() {
    var theo = track(authTestSupport.createHouseholdMemberIdentity(sam));
    var ollie = steps.profileManagedBy("Ollie", sam, sam, theo, riversideAdmin);
    var offer = steps.offer(sam, ollie, riverside);

    var outcome = sharing.acceptProfileShare(identityOf(riversideAdmin), offer.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var answeredAt = stored(offer).getDecidedAt();
    assertThat(answersAbout(offer))
        .allSatisfy(
            news -> {
              assertThat(news.form()).isEqualTo("NEWS");
              assertThat(news.snapshot())
                  .isEqualTo(
                      document(
                          new ProfileShareAnsweredSnapshot("Ollie", ProfileShareAnswer.ACCEPTED)));
              assertThat(news.latestEventAt()).isEqualTo(answeredAt);
              assertThat(news.readAt()).isEmpty();
            })
        .extracting(Row::recipient)
        .containsExactlyInAnyOrder(account(sam), account(theo));
  }

  @Test
  @DisplayName(
      "Should tell the Account that made the offer when a ServerAdmin offered for a Profile")
  void shouldTellTheAccountThatMadeTheOfferWhenAServerAdminOfferedForAProfile() {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var ollie = steps.profileManagedBy("Ollie", sam, sam);
    var offer = steps.offer(serverAdmin, ollie, riverside);

    var outcome = sharing.rejectProfileShare(identityOf(riverside), offer.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(answersAbout(offer))
        .allSatisfy(
            news ->
                assertThat(news.snapshot())
                    .isEqualTo(
                        document(
                            new ProfileShareAnsweredSnapshot(
                                "Ollie", ProfileShareAnswer.REJECTED))))
        .extracting(Row::recipient)
        .containsExactlyInAnyOrder(account(sam), account(serverAdmin));
  }

  @ParameterizedTest
  @EnumSource(Unanswered.class)
  @DisplayName("Should tell nobody anything new when the offer is canceled or expires")
  void shouldTellNobodyAnythingNewWhenTheOfferIsCanceledOrExpires(Unanswered how) {
    var offer = steps.offer(sam, sam.profile(), riverside);

    switch (how) {
      case CANCELED -> sharing.cancelProfileShare(identityOf(sam), offer.getId());
      case EXPIRED -> {
        steps.lapse(offer);
        expirySweeper.sweep();
      }
    }

    assertThat(stored(offer).getStatus()).isEqualTo(how.status);
    assertThat(rows.about(SubjectReference.profileShare(offer.getId())))
        .extracting(Row::kind)
        .containsOnly(OFFERED);
  }

  /** How an offer stops waiting with nobody answering it. */
  enum Unanswered {
    CANCELED(ProfileShareStatus.CANCELED),
    EXPIRED(ProfileShareStatus.EXPIRED);

    private final ProfileShareStatus status;

    Unanswered(ProfileShareStatus status) {
      this.status = status;
    }
  }

  @Test
  @DisplayName(
      "Should tell nobody anything new when a rejection comes after the offer's time ran out")
  void shouldTellNobodyAnythingNewWhenARejectionComesAfterTheOffersTimeRanOut() {
    var offer = steps.offer(sam, sam.profile(), riverside);
    steps.lapse(offer);
    var serverAdmin = track(authTestSupport.createAdminIdentity());

    sharing.rejectProfileShare(identityOf(serverAdmin), offer.getId());

    assertThat(stored(offer).getStatus()).isEqualTo(ProfileShareStatus.EXPIRED);
    assertThat(answersAbout(offer)).isEmpty();
  }

  @Test
  @DisplayName("Should make no Notification when the acceptance that would make it rolls back")
  void shouldMakeNoNotificationWhenTheAcceptanceThatWouldMakeItRollsBack() {
    var ollie = steps.profileManagedBy("Ollie", sam, sam);
    steps.profileManagedBy("Ollie", riverside, riverside);
    var offer = steps.offer(sam, ollie, riverside);

    var outcome = sharing.acceptProfileShare(identityOf(riverside), offer.getId());

    assertThat(outcome).isEqualTo(Outcome.rejected(new ShareRejections.NameConflict()));
    assertThat(answersAbout(offer)).isEmpty();
    assertThat(requestsAbout(offer))
        .hasSize(2)
        .allSatisfy(request -> assertThat(request.resolution()).isEmpty());
  }

  @Test
  @DisplayName(
      "Should tell the offering side when a HouseholdAdmin of the Household ends the share")
  void shouldTellTheOfferingSideWhenAHouseholdAdminOfTheHouseholdEndsTheShare() {
    var share = steps.accept(riverside, steps.offer(sam, sam.profile(), riverside));

    var outcome = sharing.endProfileShare(identityOf(riversideAdmin), share.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    var endedAt = stored(share).getEndedAt();
    assertThat(endingsAbout(share))
        .singleElement()
        .satisfies(
            news -> {
              assertThat(news.recipient()).isEqualTo(account(sam));
              assertThat(news.form()).isEqualTo("NEWS");
              assertThat(news.snapshot())
                  .isEqualTo(ended(sam.profile(), ProfileShareEndedBy.HOUSEHOLD_SIDE));
              assertThat(news.latestEventAt()).isEqualTo(endedAt);
              assertThat(news.readAt()).isEmpty();
            });
  }

  @Test
  @DisplayName("Should tell the Household's side when the Profile's own Account ends the share")
  void shouldTellTheHouseholdsSideWhenTheProfilesOwnAccountEndsTheShare() {
    var share = steps.accept(riverside, steps.offer(sam, sam.profile(), riverside));

    sharing.endProfileShare(identityOf(sam), share.getId());

    assertThat(endingsAbout(share))
        .allSatisfy(
            news ->
                assertThat(news.snapshot())
                    .isEqualTo(ended(sam.profile(), ProfileShareEndedBy.OFFERING_SIDE)))
        .extracting(Row::recipient)
        .containsExactlyInAnyOrder(account(riverside), account(riversideAdmin));
  }

  @Test
  @DisplayName(
      "Should tell the Household's side and no other ProfileManager when a ProfileManager ends the"
          + " share")
  void shouldTellTheHouseholdsSideAndNoOtherProfileManagerWhenAProfileManagerEndsTheShare() {
    var theo = track(authTestSupport.createHouseholdMemberIdentity(sam));
    var rowan = track(authTestSupport.createHouseholdMemberIdentity(riverside));
    var ollie = steps.profileManagedBy("Ollie", sam, sam, theo, rowan);
    var share = steps.accept(riverside, steps.offer(sam, ollie, riverside));

    var outcome = sharing.endProfileShare(identityOf(rowan), share.getId());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(endingsAbout(share))
        .allSatisfy(
            news ->
                assertThat(news.snapshot())
                    .isEqualTo(ended(ollie, ProfileShareEndedBy.OFFERING_SIDE)))
        .extracting(Row::recipient)
        .containsExactlyInAnyOrder(account(riverside), account(riversideAdmin));
  }

  @Test
  @DisplayName(
      "Should end the share for the Household's side when its ender also manages the Profile")
  void shouldEndTheShareForTheHouseholdsSideWhenItsEnderAlsoManagesTheProfile() {
    var ollie = steps.profileManagedBy("Ollie", sam, sam, riversideAdmin);
    var share = steps.accept(riverside, steps.offer(sam, ollie, riverside));

    sharing.endProfileShare(identityOf(riversideAdmin), share.getId());

    assertThat(endingsAbout(share))
        .allSatisfy(
            news ->
                assertThat(news.snapshot())
                    .isEqualTo(ended(ollie, ProfileShareEndedBy.HOUSEHOLD_SIDE)))
        .extracting(Row::recipient)
        .containsExactly(account(sam));
  }

  @Test
  @DisplayName("Should tell both sides, except the ServerAdmin, when a ServerAdmin ends the share")
  void shouldTellBothSidesExceptTheServerAdminWhenAServerAdminEndsTheShare() {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var ollie = steps.profileManagedBy("Ollie", sam, sam, serverAdmin);
    var share = steps.accept(riverside, steps.offer(sam, ollie, riverside));

    var outcome =
        sharing.administrativelyEndProfileShare(
            authTestSupport.freshIdentityOf(serverAdmin), share.getId(), "requested by Sam");

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(endingsAbout(share))
        .allSatisfy(
            news -> assertThat(news.snapshot()).isEqualTo(ended(ollie, ProfileShareEndedBy.SERVER)))
        .extracting(Row::recipient)
        .containsExactlyInAnyOrder(account(sam), account(riverside), account(riversideAdmin));
  }

  @Test
  @DisplayName(
      "Should give an Account on both sides one Share ended news when a ServerAdmin ends the share")
  void shouldGiveAnAccountOnBothSidesOneShareEndedNewsWhenAServerAdminEndsTheShare() {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var ollie = steps.profileManagedBy("Ollie", sam, sam, riversideAdmin);
    var share = steps.accept(riverside, steps.offer(sam, ollie, riverside));

    sharing.administrativelyEndProfileShare(
        authTestSupport.freshIdentityOf(serverAdmin), share.getId(), "requested by Sam");

    assertThat(endingsAbout(share))
        .extracting(Row::recipient)
        .containsExactlyInAnyOrder(account(sam), account(riverside), account(riversideAdmin));
  }

  @Test
  @DisplayName(
      "Should tell the former offerer nothing when it stopped managing the Profile before the share"
          + " ended")
  void shouldTellTheFormerOffererNothingWhenItStoppedManagingTheProfileBeforeTheShareEnded() {
    var theo = track(authTestSupport.createHouseholdMemberIdentity(sam));
    var kai = steps.profileManagedBy("Kai", sam, sam, theo);
    var share = steps.accept(riverside, steps.offer(theo, kai, riverside));
    var relinquished = profileManagement.relinquishProfileManagement(identityOf(theo), kai.getId());

    sharing.endProfileShare(identityOf(riverside), share.getId());

    assertThat(relinquished).isInstanceOf(Outcome.Accepted.class);
    assertThat(endingsAbout(share)).extracting(Row::recipient).containsExactly(account(sam));
  }

  @Test
  @DisplayName("Should keep the answer and the end side by side when an accepted share later ends")
  void shouldKeepTheAnswerAndTheEndSideBySideWhenAnAcceptedShareLaterEnds() {
    var ollie = steps.profileManagedBy("Ollie", sam, sam);
    var share = steps.accept(riverside, steps.offer(sam, ollie, riverside));

    sharing.endProfileShare(identityOf(riverside), share.getId());

    assertThat(rows.of(account(sam)))
        .extracting(Row::kind)
        .containsExactlyInAnyOrder(ANSWERED, ENDED);
  }

  @Test
  @DisplayName(
      "Should tell both sides of each visit, except the new Account, when a Profile is connected to"
          + " an Account")
  void shouldTellBothSidesOfEachVisitExceptTheNewAccountWhenAProfileIsConnectedToAnAccount() {
    var kai = track(authTestSupport.createIdentity());
    var ollie = steps.profileManagedBy("Ollie", sam, sam);
    var visit = steps.accept(riverside, steps.offer(sam, ollie, riverside));
    var waiting = steps.offer(sam, ollie, kai);

    var connected = connect(ollie, riverside);

    assertThat(endingsAbout(visit))
        .allSatisfy(
            news -> assertThat(news.snapshot()).isEqualTo(ended(ollie, ProfileShareEndedBy.SERVER)))
        .extracting(Row::recipient)
        .containsExactlyInAnyOrder(account(sam), account(riverside), account(riversideAdmin));
    assertThat(rows.of(RecipientReference.account(connected))).isEmpty();
    assertThat(rows.about(SubjectReference.profileShare(waiting.getId())))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.kind()).isEqualTo(OFFERED);
              assertThat(request.resolution()).contains("WITHDRAWN");
            });
  }

  @Test
  @DisplayName(
      "Should tell both sides when an Account transfer ends the share into the Household it leaves")
  void shouldTellBothSidesWhenAnAccountTransferEndsTheShareIntoTheHouseholdItLeaves() {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var ben = track(authTestSupport.createHouseholdMemberIdentity(riverside));
    var home =
        shareRepository
            .findByProfileIdAndHouseholdIdAndStatus(
                ben.profile().getId(), riverside.household().getId(), ProfileShareStatus.ACTIVE)
            .orElseThrow();

    var outcome =
        accountLifecycle.transferAccount(
            authTestSupport.freshIdentityOf(serverAdmin),
            TransferAccountCommand.builder()
                .accountId(ben.account().getId())
                .destinationHouseholdId(sam.household().getId())
                .sourceHouseholdAccess(SourceHouseholdAccess.END)
                .reason("moved in with Sam")
                .build());

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(endingsAbout(home))
        .allSatisfy(
            news ->
                assertThat(news.snapshot())
                    .isEqualTo(ended(ben.profile(), ProfileShareEndedBy.SERVER)))
        .extracting(Row::recipient)
        .containsExactlyInAnyOrder(account(ben), account(riverside), account(riversideAdmin));
  }

  @Test
  @DisplayName("Should tell nobody when deleting a Household ends its shares")
  void shouldTellNobodyWhenDeletingAHouseholdEndsItsShares() {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var lakeside = track(authTestSupport.createIdentity());
    var share = steps.accept(lakeside, steps.offer(sam, sam.profile(), lakeside));

    var outcome =
        householdDeletion.transferLastAccountAndDeleteHousehold(
            authTestSupport.freshIdentityOf(serverAdmin),
            TransferLastAccountAndDeleteHouseholdCommand.builder()
                .householdId(lakeside.household().getId())
                .destinationHouseholdId(serverAdmin.household().getId())
                .reason("closing")
                .build());

    assertThat(outcome).isEqualTo(Outcome.accepted(lakeside.household().getId()));
    assertThat(endingsAbout(share)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(ProfileDeletion.class)
  @DisplayName(
      "Should dismiss every Notification about a share for every recipient when its Profile is"
          + " deleted")
  void shouldDismissEveryNotificationAboutAShareForEveryRecipientWhenItsProfileIsDeleted(
      ProfileDeletion deletion) {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var ollie = steps.unsharedProfileManagedBy("Ollie", sam, sam);
    var share = steps.accept(riverside, steps.offer(sam, ollie, riverside));
    sharing.endProfileShare(identityOf(riverside), share.getId());
    var subject = SubjectReference.profileShare(share.getId());
    assertThat(rows.about(subject))
        .extracting(Row::kind)
        .containsExactlyInAnyOrder(OFFERED, OFFERED, ANSWERED, ENDED);

    Outcome<?, ?> outcome =
        switch (deletion) {
          case BY_ITS_SOLE_MANAGER ->
              profileAdministration.deleteProfile(
                  authTestSupport.freshIdentityOf(sam), ollie.getId());
          case BY_A_SERVER_ADMIN ->
              profileLifecycle.administrativelyDeleteProfile(
                  authTestSupport.freshIdentityOf(serverAdmin), ollie.getId(), "a duplicate");
          case WITH_ITS_HOUSEHOLD ->
              householdDeletion.deleteLastAccountAndHousehold(
                  authTestSupport.freshIdentityOf(serverAdmin),
                  DeleteLastAccountAndHouseholdCommand.builder()
                      .householdId(sam.household().getId())
                      .reason("closing")
                      .build());
        };

    assertThat(outcome).isInstanceOf(Outcome.Accepted.class);
    assertThat(profileRepository.findById(ollie.getId())).isEmpty();
    assertThat(rows.about(subject)).isEmpty();
  }

  /** How a Profile is deleted: each path deletes its shares with it. */
  enum ProfileDeletion {
    BY_ITS_SOLE_MANAGER,
    BY_A_SERVER_ADMIN,
    WITH_ITS_HOUSEHOLD
  }

  @Test
  @DisplayName(
      "Should dismiss every Notification about the shares into a Household when the Household is"
          + " deleted")
  void shouldDismissEveryNotificationAboutTheSharesIntoAHouseholdWhenTheHouseholdIsDeleted() {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    var lakeside = track(authTestSupport.createIdentity());
    var accepted =
        steps.accept(
            lakeside, steps.offer(sam, steps.profileManagedBy("Ollie", sam, sam), lakeside));
    var waiting = steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), lakeside);
    var elsewhere = steps.offer(sam, sam.profile(), riverside);

    var outcome =
        householdDeletion.transferLastAccountAndDeleteHousehold(
            authTestSupport.freshIdentityOf(serverAdmin),
            TransferLastAccountAndDeleteHouseholdCommand.builder()
                .householdId(lakeside.household().getId())
                .destinationHouseholdId(serverAdmin.household().getId())
                .reason("closing")
                .build());

    assertThat(outcome).isEqualTo(Outcome.accepted(lakeside.household().getId()));
    assertThat(rows.about(SubjectReference.profileShare(accepted.getId()))).isEmpty();
    assertThat(rows.about(SubjectReference.profileShare(waiting.getId()))).isEmpty();
    assertThat(requestsAbout(elsewhere)).hasSize(2);
  }

  private void assertWithdrawn(ProfileHouseholdShare offer) {
    assertResolvedWithoutAnAnswer(offer, "WITHDRAWN");
  }

  private void assertExpired(ProfileHouseholdShare offer) {
    assertResolvedWithoutAnAnswer(offer, "EXPIRED");
  }

  // Every recipient's request resolves at once, at the instant the share records.
  private void assertResolvedWithoutAnAnswer(ProfileHouseholdShare offer, String resolution) {
    var stored = stored(offer);
    var profileName = profileRepository.findById(stored.getProfileId()).orElseThrow().getName();
    assertThat(requestsAbout(offer))
        .hasSize(2)
        .allSatisfy(
            request -> {
              assertThat(request.resolution()).contains(resolution);
              assertThat(request.resolvedAt()).contains(stored.getDecidedAt());
              assertThat(request.snapshot())
                  .isEqualTo(
                      document(new ProfileShareOfferedSnapshot(profileName, Optional.empty())));
            });
  }

  /** Every column of each copy about the share, as text, without the copy's own recipient. */
  private List<String> storedCopiesAbout(UUID shareId) {
    return dsl.fetch(
            "SELECT recipient_id::text, row_to_json(n)::text FROM notification n WHERE subject_id = ?",
            shareId)
        .map(copy -> copy.get(1, String.class).replace(copy.get(0, String.class), ""));
  }

  /** Runs the operation as the Account, as its client sends it, and answers the value at path. */
  private String graphql(TestIdentity account, String path, String operation) throws Exception {
    var body =
        mockMvc
            .perform(graphqlRequest(authTestSupport.accountBearer(account), operation))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(body, path);
  }

  private ProfileHouseholdShare stored(ProfileHouseholdShare share) {
    return shareRepository.findById(share.getId()).orElseThrow();
  }

  private List<Row> requestsAbout(ProfileHouseholdShare share) {
    return rows.about(SubjectReference.profileShare(share.getId())).stream()
        .filter(row -> row.kind().equals(OFFERED))
        .toList();
  }

  private List<Row> answersAbout(ProfileHouseholdShare share) {
    return rows.about(SubjectReference.profileShare(share.getId())).stream()
        .filter(row -> row.kind().equals(ANSWERED))
        .toList();
  }

  private List<Row> endingsAbout(ProfileHouseholdShare share) {
    return rows.about(SubjectReference.profileShare(share.getId())).stream()
        .filter(row -> row.kind().equals(ENDED))
        .toList();
  }

  private static JsonNode ended(Profile profile, ProfileShareEndedBy endedBy) {
    return document(new ProfileShareEndedSnapshot(profile.getName(), endedBy));
  }

  private UUID connect(Profile profile, TestIdentity reofferedTo) {
    var connected =
        connections.connect(profile, track(authTestSupport.createAdminIdentity()), reofferedTo);
    connectedAccountIds.add(connected);
    return connected;
  }

  private AuthenticatedIdentity identityOf(TestIdentity identity) {
    return authTestSupport.identityOf(identity);
  }

  private static RecipientReference account(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
