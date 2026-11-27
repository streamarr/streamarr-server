package com.streamarr.server.services.identity;

import static com.streamarr.server.fixtures.PaginationFixture.firstRows;
import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static com.streamarr.server.support.StoredNotificationRows.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.SubjectReference;
import com.streamarr.server.fakes.MutableClock;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.auth.ManagerInvitationExpirySweeper;
import com.streamarr.server.services.events.notification.ProfileManagerInvitationReceivedSnapshot;
import com.streamarr.server.services.identity.ProfileManagerAdministrationService.IssuedManagerInvitation;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ControlledClockConfiguration;
import com.streamarr.server.support.ManagerInvitationSteps;
import com.streamarr.server.support.ProfileCastSupport;
import com.streamarr.server.support.StoredNotificationRows;
import java.time.Duration;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The request about an invitation to manage a Profile whose time runs out (ADR 0063 §Requests), on
 * a clock the tests move past the invitation's expiry: the request reads as expired from its expiry
 * on, and the sweep that stores the invitation's expiry resolves it as expired, at the expiry. The
 * Profile's pending list leaves the invitation out from its expiry on, before the sweep, because a
 * manager's open Managers list drops the invitation at its expiresAt and gets no live change.
 */
@Tag("IntegrationTest")
@DisplayName("Manager Invitation Expiry Notifications Integration Tests")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(ControlledClockConfiguration.class)
class ManagerInvitationExpiryNotificationsIT extends AbstractIntegrationTest {

  private static final String NOTIFICATIONS =
      """
      query {
        unreadNotificationCount
        notifications(first: 10) {
          edges {
            node {
              __typename unread message
              ... on RequestNotification { expiresAt resolution resolvedAt }
            }
          }
        }
      }
      """;

  @Autowired private MutableClock clock;
  @Autowired private MockMvc mockMvc;
  @Autowired private ProfileManagerAdministrationService profileManagement;
  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private ManagerInvitationExpirySweeper expirySweeper;
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

  @BeforeEach
  void setUp() {
    clock.advance(Duration.between(clock.instant(), Instant.now()));
    steps =
        ManagerInvitationSteps.builder()
            .management(profileManagement)
            .auth(authTestSupport)
            .invitations(invitationRepository)
            .transactions(transactionTemplate)
            .build();
    rows = new StoredNotificationRows(dsl);
    sam = track(authTestSupport.createIdentity());
    mara = track(authTestSupport.createIdentity());
    alex = cast.profileManagedBy(sam, ProfileFixture.defaultProfileBuilder().name("Alex"));
  }

  @AfterEach
  void tearDown() {
    rows.deleteOf(
        identities.stream().map(ManagerInvitationExpiryNotificationsIT::accountOf).toList());
    invitationRepository.deleteAll();
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName("Should read the request as expired when its expiry passes before the sweep")
  void shouldReadTheRequestAsExpiredWhenItsExpiryPassesBeforeTheSweep() throws Exception {
    var issued = steps.invite(sam, alex, mara.account().getEmail());
    runOut(issued);

    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(mara), NOTIFICATIONS))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.notifications.edges", hasSize(1)))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.unread").value(false))
        .andExpect(jsonPath("$.data.notifications.edges[0].node.resolution").value("EXPIRED"))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.resolvedAt")
                .value(stored(issued).getExpiresAt().toString()))
        .andExpect(
            jsonPath("$.data.notifications.edges[0].node.message")
                .value("The invitation to manage “Alex” expired."));
  }

  @Test
  @DisplayName(
      "Should resolve the request as expired at the invitation's expiry when the sweep stores it")
  void shouldResolveTheRequestAsExpiredAtTheInvitationsExpiryWhenTheSweepStoresIt() {
    var issued = steps.invite(sam, alex, mara.account().getEmail());
    runOut(issued);

    expirySweeper.sweep();

    var expired = stored(issued);
    assertThat(expired.getStatus()).isEqualTo(ProfileManagerInvitationStatus.EXPIRED);
    assertThat(rows.about(SubjectReference.profileManagerInvitation(issued.invitation().getId())))
        .singleElement()
        .satisfies(
            request -> {
              assertThat(request.recipient()).isEqualTo(accountOf(mara));
              assertThat(request.resolution()).contains("EXPIRED");
              assertThat(request.resolvedAt()).contains(expired.getExpiresAt());
              assertThat(request.snapshot())
                  .isEqualTo(
                      document(
                          new ProfileManagerInvitationReceivedSnapshot("Alex", Optional.empty())));
            });
  }

  @Test
  @DisplayName(
      "Should leave the invitation out of its Profile's pending list from its expiry when the sweep"
          + " has not run")
  void shouldLeaveTheInvitationOutOfItsProfilesPendingListFromItsExpiryWhenTheSweepHasNotRun() {
    var issued = steps.invite(sam, alex, mara.account().getEmail());
    var expiresAt = stored(issued).getExpiresAt();
    moveClockTo(expiresAt.minus(1, ChronoUnit.MICROS));
    assertThat(pendingForAlex()).containsExactly(issued.invitation().getId());

    moveClockTo(expiresAt);

    assertThat(pendingForAlex()).isEmpty();
    assertThat(stored(issued).getStatus()).isEqualTo(ProfileManagerInvitationStatus.PENDING);
  }

  /** Moves the clock a second past the invitation's expiry. */
  private void runOut(IssuedManagerInvitation issued) {
    moveClockTo(stored(issued).getExpiresAt().plusSeconds(1));
  }

  private void moveClockTo(Instant instant) {
    clock.advance(Duration.between(clock.instant(), instant));
  }

  /** The invitations that Sam, who manages Alex, reads in Alex's pending list. */
  private List<UUID> pendingForAlex() {
    return profileManagement
        .pendingManagerInvitationsForProfile(
            authTestSupport.identityOf(sam), alex.getId(), firstRows(10))
        .rows()
        .stream()
        .map(row -> row.item().getId())
        .toList();
  }

  private ProfileManagerInvitation stored(IssuedManagerInvitation issued) {
    return invitationRepository.findById(issued.invitation().getId()).orElseThrow();
  }

  private static RecipientReference accountOf(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
