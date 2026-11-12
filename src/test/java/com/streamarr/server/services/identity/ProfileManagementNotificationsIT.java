package com.streamarr.server.services.identity;

import static com.streamarr.server.support.StoredNotificationRows.document;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.SubjectReference;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.auth.InvitationLifetime;
import com.streamarr.server.services.auth.ProfileManagerInvitationLifecycle;
import com.streamarr.server.services.events.notification.ProfileManagerInvitationReceivedSnapshot;
import com.streamarr.server.services.identity.ProfileManagerAdministrationService.IssuedManagerInvitation;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ManagerInvitationSteps;
import com.streamarr.server.support.ProfileCastSupport;
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
  private static final String INVITER_NAME = "Sam Rivers";

  @Autowired private ProfileManagerAdministrationService profileManagement;
  @Autowired private ProfileManagerInvitationLifecycle invitationLifecycle;
  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private InvitationLifetime invitationLifetime;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private final List<TestIdentity> identities = new ArrayList<>();
  private ManagerInvitationSteps steps;
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

  @BeforeEach
  void setUp() {
    steps =
        ManagerInvitationSteps.builder()
            .management(profileManagement)
            .auth(authTestSupport)
            .invitations(invitationRepository)
            .transactions(transactionTemplate)
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

  private List<Row> requestsAbout(IssuedManagerInvitation issued) {
    return rows.about(SubjectReference.profileManagerInvitation(issued.invitation().getId()));
  }

  private ProfileManagerInvitation stored(IssuedManagerInvitation issued) {
    return invitationRepository.findById(issued.invitation().getId()).orElseThrow();
  }

  private static String nobody() {
    return "nobody.%s@example.com".formatted(UUID.randomUUID());
  }

  private static RecipientReference accountOf(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
