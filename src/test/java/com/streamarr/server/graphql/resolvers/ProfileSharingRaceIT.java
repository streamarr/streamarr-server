package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileKind;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.AuthSessionRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.awaitility.core.ConditionTimeoutException;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The two row-lock races around a share's end, driven deterministically: a side connection holds
 * the row the racing request needs, {@code pg_stat_activity} confirms the request is blocked, the
 * competitor runs, the row is released, and the final state is asserted — no sleeps, no polling for
 * the outcome.
 */
@Tag("IntegrationTest")
@DisplayName("Profile Sharing Race Integration Tests")
class ProfileSharingRaceIT extends AbstractIntegrationTest {

  private static final String LOCK_SHARE_ROW =
      "SELECT id FROM profile_household_share WHERE id = ? FOR UPDATE";
  private static final String LOCK_SESSION_ROW =
      "SELECT id FROM auth_session WHERE id = ? FOR UPDATE";
  private static final String WAITING_SHARE_TRANSITION = "update%profile_household_share";
  private static final String WAITING_SESSION_LOCK = "auth_session%for update";
  private static final Duration BOUND = Duration.ofSeconds(10);

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private AuthSessionRepository authSessionRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private DataSource dataSource;

  private AuthTestSupport.TestIdentity owner;
  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void setUp() {
    owner = authTestSupport.createIdentity();
    host = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    authTestSupport.deleteIdentity(host);
    authTestSupport.deleteIdentity(owner);
  }

  // ---- Selection versus share termination.

  /**
   * Selection pins the Profile's ACTIVE share before it locks the session, in the order termination
   * takes, so an end that commits first refuses the selection and one that waits clears it.
   */
  @Test
  @DisplayName(
      "Should leave no selection of an ended Profile when selection races share termination")
  void shouldLeaveNoSelectionOfEndedProfileWhenSelectionRacesShareTermination() throws Exception {
    var orphan = managedOrphan();
    var shareId = offerAsOwner(orphan, host.household().getId());
    accept(host, shareId);
    var sessionId = host.session().getId();
    var hostBearer = authTestSupport.accountBearer(host);

    var outcome =
        whileRowLocked(
            LOCK_SESSION_ROW,
            sessionId,
            held -> {
              var selection =
                  held.executor().submit(() -> selectProfile(hostBearer, orphan.getId()));
              awaitWaitingOn(WAITING_SESSION_LOCK, "selection should wait on the session row");

              var end =
                  held.executor()
                      .submit(
                          () ->
                              graphql(
                                      hostBearer,
                                      """
                                      mutation { endProfileShare(input: {shareId: "%s"}) {
                                        share { status } userErrors { __typename } } }
                                      """
                                          .formatted(shareId))
                                  .andExpect(status().isOk())
                                  .andReturn()
                                  .getResponse()
                                  .getContentAsString());
              // Today the end completes at once; after the fix it waits behind the selection's
              // share lock. Either way the row is released only once the end has made its move.
              await()
                  .atMost(BOUND)
                  .until(() -> end.isDone() || waitingOn(WAITING_SHARE_TRANSITION) >= 1);

              held.release().run();
              var selectionStatus = outcomeOf(selection, "selection after release");
              var endResponse = outcomeOf(end, "end after release");
              return new SelectionRace(selectionStatus, endResponse);
            });

    assertThat(
            objectMapper
                .readTree(outcome.endResponse())
                .path("data")
                .path("endProfileShare")
                .path("share")
                .path("status")
                .asString())
        .isEqualTo("ENDED");
    assertThat(authSessionRepository.findById(sessionId).orElseThrow().getSelectedProfileId())
        .as(
            "a session must never end up selecting an ended Profile (selection answered %s)",
            outcome.selectionStatus())
        .isNotEqualTo(orphan.getId());
  }

  // ---- Acceptance versus the offerer losing authority.

  /**
   * Acceptance re-decides the offerer inside its own transaction, and the authority facts are read
   * {@code FOR SHARE}, so a disablement waits behind the acceptance: an offer never activates after
   * its offerer lost authority, and the ordering invariant holds by construction.
   */
  @Test
  @DisplayName(
      "Should not activate an offer when its offerer lost authority before activation committed")
  void shouldNotActivateOfferWhenOffererLostAuthorityBeforeActivationCommitted() throws Exception {
    var orphan = managedOrphan();
    var offerer = authTestSupport.createAdminIdentity();
    var disabler = authTestSupport.createAdminIdentity();
    try {
      var shareId = offer(authTestSupport.accountBearer(offerer), orphan, host.household().getId());
      var hostBearer = authTestSupport.accountBearer(host);
      var disablerBearer = authTestSupport.freshAccountBearer(disabler);

      var outcome =
          whileRowLocked(
              LOCK_SHARE_ROW,
              shareId,
              held -> {
                var acceptance =
                    held.executor()
                        .submit(
                            () ->
                                graphql(
                                        hostBearer,
                                        """
                                        mutation { acceptProfileShare(input: {shareId: "%s"}) {
                                          share { status } userErrors { __typename } } }
                                        """
                                            .formatted(shareId))
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
                awaitWaitingOn(
                    WAITING_SHARE_TRANSITION, "acceptance should wait on the share transition");

                var disablement =
                    held.executor()
                        .submit(
                            () ->
                                graphql(
                                        disablerBearer,
                                        """
                                        mutation { disableAccount(input: {accountId: "%s"}) {
                                          account { enabled } userErrors { __typename } } }
                                        """
                                            .formatted(offerer.account().getId()))
                                    .andExpect(status().isOk())
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
                var revokedBeforeRelease = completedWithin(disablement, Duration.ofSeconds(2));
                if (revokedBeforeRelease) {
                  assertThat(
                          objectMapper
                              .readTree(disablement.get())
                              .path("data")
                              .path("disableAccount")
                              .path("account")
                              .path("enabled")
                              .asBoolean(true))
                      .as("the offerer must be disabled for the race to mean anything")
                      .isFalse();
                }

                held.release().run();
                var acceptResponse = outcomeOf(acceptance, "acceptance after release");
                outcomeOf(disablement, "disablement after release");
                return new AcceptanceRace(revokedBeforeRelease, acceptResponse);
              });

      assertThat(outcome.revokedBeforeRelease())
          .as("disablement must wait behind the acceptance's FOR SHARE read of the offerer")
          .isFalse();
      assertThat(shareRepository.findById(shareId).orElseThrow().getStatus())
          .as(
              "the acceptance won, so the share is ACTIVE (acceptance answered %s)",
              outcome.acceptResponse())
          .isEqualTo(ProfileShareStatus.ACTIVE);
    } finally {
      authTestSupport.deleteIdentity(offerer);
      authTestSupport.deleteIdentity(disabler);
    }
  }

  private int selectProfile(String bearer, UUID profileId) throws Exception {
    return mockMvc
        .perform(
            post("/api/auth/select-profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
                .content("{\"profileId\": \"%s\", \"cookieMode\": false}".formatted(profileId)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private void accept(AuthTestSupport.TestIdentity decider, UUID shareId) throws Exception {
    graphql(
            authTestSupport.accountBearer(decider),
            """
            mutation { acceptProfileShare(input: {shareId: "%s"}) {
              share { status } userErrors { __typename } } }
            """
                .formatted(shareId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.acceptProfileShare.share.status").value("ACTIVE"));
  }

  /** An unlinked Adult Profile the owner solely manages, with no share at all. */
  private Profile unsharedManagedProfile() {
    return transactionTemplate.execute(
        _ -> {
          var orphan =
              profileRepository.saveAndFlush(
                  ProfileFixture.defaultProfileBuilder()
                      .householdId(owner.household().getId())
                      .kind(ProfileKind.ADULT)
                      .build());
          profileManagerRepository.saveAndFlush(
              ProfileManager.builder()
                  .accountId(owner.account().getId())
                  .profileId(orphan.getId())
                  .build());
          return orphan;
        });
  }

  private <T> T whileRowLocked(String lockStatement, UUID rowId, RaceBody<T> body)
      throws Exception {
    var rowLocked = new CountDownLatch(1);
    var releaseRow = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var blocker =
          executor.submit(
              () -> {
                holdRowLock(lockStatement, rowId, rowLocked, releaseRow);
                return null;
              });
      try {
        assertThat(rowLocked.await(BOUND.toSeconds(), TimeUnit.SECONDS))
            .as("row should be locked before racing")
            .isTrue();
        return body.run(new HeldRow(executor, releaseRow::countDown));
      } finally {
        releaseRow.countDown();
        blocker.get(BOUND.toSeconds(), TimeUnit.SECONDS);
      }
    }
  }

  private void holdRowLock(
      String lockStatement, UUID rowId, CountDownLatch rowLocked, CountDownLatch releaseRow)
      throws Exception {
    try (var connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (var statement = connection.prepareStatement(lockStatement)) {
        statement.setObject(1, rowId);
        statement.executeQuery();
      }

      rowLocked.countDown();
      // Outlasts every wait inside the race body so a body failure is what surfaces.
      assertThat(releaseRow.await(BOUND.multipliedBy(3).toSeconds(), TimeUnit.SECONDS))
          .as("row should be released by the race")
          .isTrue();
      connection.rollback();
    }
  }

  private void awaitWaitingOn(String queryFragment, String description) {
    try {
      await()
          .atMost(BOUND)
          .untilAsserted(() -> assertThat(waitingOn(queryFragment)).as(description).isPositive());
    } catch (ConditionTimeoutException timeout) {
      throw new AssertionError(
          description
              + " — no backend waiting on '"
              + queryFragment
              + "'. Activity:\n"
              + activity(),
          timeout);
    }
  }

  /** Bounded wait that, on timeout, reports what every other backend is doing. */
  private <T> T outcomeOf(Future<T> future, String description) throws Exception {
    try {
      return future.get(BOUND.toSeconds(), TimeUnit.SECONDS);
    } catch (TimeoutException timeout) {
      throw new AssertionError(description + " timed out. Activity:\n" + activity(), timeout);
    }
  }

  private String activity() {
    return dsl.fetch(
            """
            SELECT pid, state, wait_event_type, wait_event, left(query, 160) AS query
            FROM pg_stat_activity
            WHERE datname = current_database() AND pid <> pg_backend_pid()
            """)
        .format();
  }

  private int waitingOn(String queryFragment) {
    return dsl.fetchOne(
            """
            SELECT count(*)
            FROM pg_stat_activity
            WHERE wait_event_type = 'Lock'
              AND query ILIKE ?
            """,
            "%" + queryFragment + "%")
        .get(0, int.class);
  }

  private static boolean completedWithin(Future<?> future, Duration timeout) throws Exception {
    try {
      future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      return true;
    } catch (TimeoutException _) {
      return false;
    }
  }

  private UUID offerAsOwner(Profile profile, UUID householdId) throws Exception {
    return offer(authTestSupport.accountBearer(owner), profile, householdId);
  }

  private UUID offer(String bearer, Profile profile, UUID householdId) throws Exception {
    var response =
        graphql(
                bearer,
                """
                mutation { offerProfileShare(input: {profileId: "%s", householdId: "%s"}) {
                  share { id status } userErrors { __typename } } }
                """
                    .formatted(profile.getId(), householdId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andExpect(jsonPath("$.data.offerProfileShare.share.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(
        objectMapper
            .readTree(response)
            .path("data")
            .path("offerProfileShare")
            .path("share")
            .path("id")
            .asString());
  }

  /** An unlinked Adult Profile the owner solely manages, available at home. */
  private Profile managedOrphan() {
    return transactionTemplate.execute(
        _ -> {
          var profile = unsharedManagedProfile();
          shareRepository.saveAndFlush(
              ProfileHouseholdShare.builder()
                  .profileId(profile.getId())
                  .householdId(owner.household().getId())
                  .status(ProfileShareStatus.ACTIVE)
                  .build());
          return profile;
        });
  }

  private ResultActions graphql(String bearer, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }

  @FunctionalInterface
  private interface RaceBody<T> {
    T run(HeldRow held) throws Exception;
  }

  private record HeldRow(ExecutorService executor, Runnable release) {}

  private record SelectionRace(int selectionStatus, String endResponse) {}

  private record AcceptanceRace(boolean revokedBeforeRelease, String acceptResponse) {}
}
