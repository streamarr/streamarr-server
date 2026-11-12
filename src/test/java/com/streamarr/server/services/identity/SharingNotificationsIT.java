package com.streamarr.server.services.identity;

import static com.streamarr.server.jooq.generated.tables.Notification.NOTIFICATION;
import static com.streamarr.server.support.StoredNotificationRows.document;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.properties.CredentialCodeProperties;
import com.streamarr.server.repositories.auth.AccountInvitationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.auth.AccountInvitationService;
import com.streamarr.server.services.auth.ProfileShareLifecycle;
import com.streamarr.server.services.events.notification.ProfileShareOfferedSnapshot;
import com.streamarr.server.services.events.notification.RecipientReference;
import com.streamarr.server.services.events.notification.SubjectReference;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

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

  @Autowired private ProfileSharingService sharing;
  @Autowired private ProfileShareLifecycle shareLifecycle;
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

  private ProfileHouseholdShare stored(ProfileHouseholdShare share) {
    return shareRepository.findById(share.getId()).orElseThrow();
  }

  private List<Row> requestsAbout(ProfileHouseholdShare share) {
    return rows.about(SubjectReference.profileShare(share.getId())).stream()
        .filter(row -> row.kind().equals(OFFERED))
        .toList();
  }

  private UUID connect(Profile profile, TestIdentity reofferedTo) {
    var connected =
        connections.connect(profile, track(authTestSupport.createAdminIdentity()), reofferedTo);
    connectedAccountIds.add(connected);
    return connected;
  }

  private static RecipientReference account(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
