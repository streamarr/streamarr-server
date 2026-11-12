package com.streamarr.server.services.notification;

import static com.streamarr.server.jooq.generated.tables.Notification.NOTIFICATION;
import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static com.streamarr.server.support.PostgresLockTestSupport.awaitLatch;
import static com.streamarr.server.support.PostgresLockTestSupport.waitersBehind;
import static com.streamarr.server.support.StoredNotificationRows.document;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.domain.notification.RecipientReference;
import com.streamarr.server.domain.notification.SubjectReference;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.events.notification.ProfileShareAnswer;
import com.streamarr.server.services.events.notification.ProfileShareAnsweredSnapshot;
import com.streamarr.server.services.events.notification.SharingNotificationKinds;
import com.streamarr.server.services.events.notification.TellNotificationEvent;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ShareSteps;
import com.streamarr.server.support.StoredNotificationRows;
import com.streamarr.server.support.StoredNotificationRows.Row;
import com.streamarr.server.support.TestFixtureWork;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Notification Marks Integration Tests")
class NotificationMarksIT extends AbstractIntegrationTest {

  private static final String MARK =
      """
      mutation { markNotificationsRead(input: {notificationIds: [%s]}) {
        unreadNotificationCount
        userErrors { __typename ... on InputMutationError { message inputPath } } } }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ProfileSharingService sharing;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private NotificationService notificationService;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private DSLContext dsl;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity sam;
  private TestIdentity theo;
  private TestIdentity riverside;
  private TestIdentity riversideAdmin;
  private ShareSteps steps;
  private StoredNotificationRows rows;

  @BeforeEach
  void setUp() {
    rows = new StoredNotificationRows(dsl);
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
    sam = track(authTestSupport.createIdentity());
    theo = track(authTestSupport.createHouseholdMemberIdentity(sam));
    riverside = track(authTestSupport.createIdentity());
    riversideAdmin =
        steps.promoted(track(authTestSupport.createHouseholdMemberIdentity(riverside)));
  }

  @AfterEach
  void tearDown() {
    rows.deleteOf(identities.stream().map(NotificationMarksIT::accountOf).toList());
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should mark read exactly the Notifications given and leave unread one the client did not"
          + " show when its recipient marks them")
  void shouldMarkReadExactlyTheNotificationsGivenAndLeaveUnreadOneTheClientDidNotShow()
      throws Exception {
    var shown = acceptedNewsFor(theo, "Kai");
    var alsoShown = acceptedNewsFor(theo, "Ollie");
    var notShown = acceptedNewsFor(theo, "Ash");
    happenedBefore(notShown, shown);

    mark(theo, shown.id(), alsoShown.id())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markNotificationsRead.unreadNotificationCount").value(1))
        .andExpect(jsonPath("$.data.markNotificationsRead.userErrors").isEmpty());

    assertThat(stored(shown).readAt()).isPresent();
    assertThat(stored(alsoShown).readAt()).isPresent();
    assertThat(stored(notShown).readAt()).isEmpty();
  }

  @ParameterizedTest(name = "{0} ids")
  @ValueSource(ints = {1, 100})
  @DisplayName("Should mark the Notification read when the ids number one or a hundred")
  void shouldMarkTheNotificationReadWhenTheIdsNumberOneOrAHundred(int count) throws Exception {
    var news = acceptedNewsFor(theo, "Kai");

    mark(theo, idsWith(news, count))
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markNotificationsRead.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.markNotificationsRead.userErrors").isEmpty());

    assertThat(stored(news).readAt()).isPresent();
  }

  @ParameterizedTest(name = "{0} ids")
  @ValueSource(ints = {0, 101})
  @DisplayName("Should refuse the mark and mark nothing when the ids number none or over a hundred")
  void shouldRefuseTheMarkAndMarkNothingWhenTheIdsNumberNoneOrOverAHundred(int count)
      throws Exception {
    var news = acceptedNewsFor(theo, "Kai");

    mark(theo, idsWith(news, count))
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markNotificationsRead.unreadNotificationCount").doesNotExist())
        .andExpect(
            jsonPath("$.data.markNotificationsRead.userErrors[0].__typename")
                .value("NotificationIdCountOutOfRangeError"))
        .andExpect(
            jsonPath("$.data.markNotificationsRead.userErrors[0].inputPath[0]")
                .value("notificationIds"));

    assertThat(stored(news).readAt()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should skip another Account's id and an unknown id alike and mark neither when they are"
          + " given")
  void shouldSkipAnotherAccountsIdAndAnUnknownIdAlikeWhenTheyAreGiven() throws Exception {
    var theos = acceptedNewsFor(theo, "Kai");
    var sams = newsAboutTheSameShare(sam, theos);

    var forAnotherAccounts = bodyOf(mark(theo, sams.id()));
    var forAnUnknownId = bodyOf(mark(theo, UUID.randomUUID()));

    assertThat(forAnotherAccounts).isEqualTo(forAnUnknownId);
    assertThat(
            JsonPath.<Integer>read(
                forAnUnknownId, "$.data.markNotificationsRead.unreadNotificationCount"))
        .isOne();
    assertThat(JsonPath.<List<?>>read(forAnUnknownId, "$.data.markNotificationsRead.userErrors"))
        .isEmpty();
    assertThat(stored(sams).readAt()).isEmpty();
    assertThat(stored(theos).readAt()).isEmpty();
  }

  @Test
  @DisplayName("Should keep when it was read when an id given is already read")
  void shouldKeepWhenItWasReadWhenAnIdGivenIsAlreadyRead() throws Exception {
    var news = acceptedNewsFor(theo, "Kai");
    mark(theo, news.id());
    var firstRead = stored(news).readAt();

    mark(theo, news.id())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markNotificationsRead.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.markNotificationsRead.userErrors").isEmpty());

    assertThat(stored(news).readAt()).isEqualTo(firstRead);
  }

  @Test
  @DisplayName(
      "Should mark a waiting request read and leave it waiting when its recipient marks it")
  void shouldMarkAWaitingRequestReadAndLeaveItWaitingWhenItsRecipientMarksIt() throws Exception {
    var offer = steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);
    var request = notificationFor(riversideAdmin, offer);

    mark(riversideAdmin, request.id())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markNotificationsRead.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.markNotificationsRead.userErrors").isEmpty());

    assertThat(stored(request).readAt()).isPresent();
    assertThat(shareRepository.findById(offer.getId()).orElseThrow().getStatus())
        .isEqualTo(ProfileShareStatus.PENDING);
  }

  @ParameterizedTest
  @EnumSource(Settled.class)
  @DisplayName(
      "Should skip a request when its recipient marks it after it was decided or its time ran out")
  void shouldSkipARequestWhenItsRecipientMarksItAfterItWasDecidedOrItsTimeRanOut(Settled settled)
      throws Exception {
    var offer = steps.offer(sam, steps.profileManagedBy("Kai", sam, sam), riverside);
    var request = notificationFor(riversideAdmin, offer);
    switch (settled) {
      case DECIDED ->
          sharing.rejectProfileShare(authTestSupport.identityOf(riverside), offer.getId());
      case TIME_RAN_OUT -> runOut(request);
    }

    mark(riversideAdmin, request.id())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markNotificationsRead.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.markNotificationsRead.userErrors").isEmpty());

    assertThat(stored(request).readAt()).isEmpty();
  }

  /** How a request stopped waiting before its recipient marked it. */
  enum Settled {
    DECIDED,
    TIME_RAN_OUT
  }

  @Test
  @DisplayName("Should answer that the id is invalid and mark nothing when one given is not an id")
  void shouldAnswerThatTheIdIsInvalidAndMarkNothingWhenOneGivenIsNotAnId() throws Exception {
    var news = acceptedNewsFor(theo, "Kai");

    mark(theo, news.id(), "Kai")
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markNotificationsRead.unreadNotificationCount").doesNotExist())
        .andExpect(
            jsonPath("$.data.markNotificationsRead.userErrors[0].__typename")
                .value("InvalidIdError"))
        .andExpect(
            jsonPath("$.data.markNotificationsRead.userErrors[0].inputPath[0]")
                .value("notificationIds"))
        .andExpect(jsonPath("$.data.markNotificationsRead.userErrors[0].inputPath[1]").value("1"));

    assertThat(stored(news).readAt()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should keep what an update in place stored when the mark of the Notification waited behind"
          + " it")
  void shouldKeepWhatAnUpdateInPlaceStoredWhenTheMarkOfTheNotificationWaitedBehindIt()
      throws Exception {
    var news = acceptedNewsFor(theo, "Kai");
    var later = news.latestEventAt().plusSeconds(60);
    var told = new ProfileShareAnsweredSnapshot("Kai Rose", ProfileShareAnswer.ACCEPTED);
    var updateHolds = new CountDownLatch(1);
    var releaseUpdate = new CountDownLatch(1);
    var updatePid = new AtomicInteger();

    try (var executor = TestFixtureWork.newExecutorRunningAsTestFixtureActor()) {
      var update =
          executor.submit(
              () ->
                  transactionTemplate.executeWithoutResult(
                      _ -> {
                        eventPublisher.publishEvent(answeredAgain(news, told, later));
                        updatePid.set(
                            dsl.select(DSL.field("pg_backend_pid()", SQLDataType.INTEGER))
                                .fetchSingle()
                                .value1());
                        updateHolds.countDown();
                        awaitLatch(releaseUpdate);
                      }));
      awaitLatch(updateHolds);
      var mark =
          executor.submit(
              () ->
                  notificationService.markNotificationsRead(
                      authTestSupport.identityOf(theo), List.of(news.id())));
      await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> waitersBehind(jdbcTemplate, updatePid.get(), "update%notification%") == 1);

      releaseUpdate.countDown();
      update.get(10, TimeUnit.SECONDS);
      mark.get(10, TimeUnit.SECONDS);
    }

    var stored = stored(news);
    assertThat(stored.readAt()).isPresent();
    assertThat(stored.latestEventAt()).isEqualTo(later);
    assertThat(stored.snapshot()).isEqualTo(document(told));
  }

  @Test
  @DisplayName("Should refuse the mark whatever its ids when the session is device-bound")
  void shouldRefuseTheMarkWhateverItsIdsWhenTheSessionIsDeviceBound() throws Exception {
    var news = acceptedNewsFor(theo, "Kai");
    var bearer = authTestSupport.deviceBoundProfileBearer(theo);

    for (var ids : List.of(quoted(news.id()), quoted("not-an-id"), "")) {
      mockMvc
          .perform(graphqlRequest(bearer, MARK.formatted(ids)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
    }

    assertThat(stored(news).readAt()).isEmpty();
  }

  private ResultActions mark(TestIdentity reader, Object... ids) throws Exception {
    var listed = Stream.of(ids).map(NotificationMarksIT::quoted).collect(Collectors.joining(", "));
    return mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(reader), MARK.formatted(listed)))
        .andExpect(status().isOk());
  }

  private static String bodyOf(ResultActions response) throws Exception {
    return response.andReturn().getResponse().getContentAsString();
  }

  // The news's id, and unknown ids after it until there are as many ids as the count.
  private static Object[] idsWith(Row news, int count) {
    return Stream.concat(Stream.of(news.id()), Stream.generate(UUID::randomUUID))
        .limit(count)
        .toArray();
  }

  private static String quoted(Object id) {
    return "\"%s\"".formatted(id);
  }

  private void happenedBefore(Row earlier, Row later) {
    var before = stored(later).latestEventAt().minusSeconds(60).atOffset(ZoneOffset.UTC);
    transactionTemplate.executeWithoutResult(
        _ ->
            dsl.update(NOTIFICATION)
                .set(NOTIFICATION.LATEST_EVENT_AT, before)
                .where(NOTIFICATION.ID.eq(earlier.id()))
                .execute());
  }

  /** Moves the request's stored expiry into the past, as when its offer's time runs out. */
  private void runOut(Row request) {
    var expired = Instant.now().minusSeconds(60).atOffset(ZoneOffset.UTC);
    transactionTemplate.executeWithoutResult(
        _ ->
            dsl.update(NOTIFICATION)
                .set(NOTIFICATION.EXPIRES_AT, expired)
                .where(NOTIFICATION.ID.eq(request.id()))
                .execute());
  }

  /** A later answer of the news's kind about its share, which updates the unread news in place. */
  private static TellNotificationEvent answeredAgain(
      Row news, ProfileShareAnsweredSnapshot told, Instant at) {
    return TellNotificationEvent.builder()
        .kind(SharingNotificationKinds.PROFILE_SHARE_ANSWERED)
        .subject(news.subject())
        .recipients(Set.of(news.recipient()))
        .snapshot(told)
        .at(at)
        .build();
  }

  /** The recipient's news that Riverside accepted an offer of a new Profile that Theo manages. */
  private Row acceptedNewsFor(TestIdentity recipient, String profileName) {
    var offer = steps.offer(sam, steps.profileManagedBy(profileName, sam, sam, theo), riverside);
    steps.accept(riverside, offer);
    return notificationFor(recipient, offer);
  }

  private Row newsAboutTheSameShare(TestIdentity recipient, Row news) {
    return notificationFor(recipient, shareRepository.findById(news.subject().id()).orElseThrow());
  }

  private Row notificationFor(TestIdentity recipient, ProfileHouseholdShare share) {
    return rows.about(SubjectReference.profileShare(share.getId())).stream()
        .filter(row -> row.recipient().equals(accountOf(recipient)))
        .findFirst()
        .orElseThrow();
  }

  private Row stored(Row notification) {
    return rows.of(notification.recipient()).stream()
        .filter(row -> row.id().equals(notification.id()))
        .findFirst()
        .orElseThrow();
  }

  private static RecipientReference accountOf(TestIdentity identity) {
    return RecipientReference.account(identity.account().getId());
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
