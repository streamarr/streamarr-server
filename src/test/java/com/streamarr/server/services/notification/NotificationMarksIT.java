package com.streamarr.server.services.notification;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.notification.Notification;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.repositories.notification.NotificationRepository;
import com.streamarr.server.services.identity.ProfileSharingService;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Notification Marks Integration Tests")
class NotificationMarksIT extends AbstractIntegrationTest {

  private static final String MARK_ONE =
      """
      mutation { markNotificationRead(input: {notificationId: "%s"}) {
        notification { id unread ... on RequestNotification { resolution } }
        unreadNotificationCount
        userErrors { __typename ... on InputMutationError { message inputPath } } } }
      """;

  private static final String MARK_ALL =
      """
      mutation { markAllNotificationsRead(input: {through: "%s"}) {
        unreadNotificationCount
        userErrors { __typename ... on InputMutationError { message inputPath } } } }
      """;

  @Autowired private MockMvc mockMvc;
  @Autowired private ProfileSharingService sharing;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private NotificationRepository notificationRepository;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private TransactionTemplate transactionTemplate;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity sam;
  private TestIdentity theo;
  private TestIdentity riverside;
  private TestIdentity riversideAdmin;
  private ShareSteps steps;

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
    sam = track(authTestSupport.createIdentity());
    theo = track(authTestSupport.createHouseholdMemberIdentity(sam));
    riverside = track(authTestSupport.createIdentity());
    riversideAdmin =
        steps.promoted(track(authTestSupport.createHouseholdMemberIdentity(riverside)));
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName("Should mark the news read and lower the count when its recipient marks it")
  void shouldMarkTheNewsReadAndLowerTheCountWhenItsRecipientMarksIt() throws Exception {
    var news = acceptedNewsFor(theo);

    markOne(theo, news.getId())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.markNotificationRead.notification.id").value(news.getId().toString()))
        .andExpect(jsonPath("$.data.markNotificationRead.notification.unread").value(false))
        .andExpect(jsonPath("$.data.markNotificationRead.unreadNotificationCount").value(0))
        .andExpect(jsonPath("$.data.markNotificationRead.userErrors").isEmpty());

    assertThat(stored(news).getReadAt()).isPresent();
  }

  @Test
  @DisplayName("Should return the read news unchanged when its recipient marks it again")
  void shouldReturnTheReadNewsUnchangedWhenItsRecipientMarksItAgain() throws Exception {
    var news = acceptedNewsFor(theo);
    markOne(theo, news.getId());
    var firstRead = stored(news).getReadAt();

    markOne(theo, news.getId())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markNotificationRead.notification.unread").value(false))
        .andExpect(jsonPath("$.data.markNotificationRead.userErrors").isEmpty());

    assertThat(stored(news).getReadAt()).isEqualTo(firstRead);
  }

  @Test
  @DisplayName(
      "Should mark a waiting request read and leave it waiting when its recipient marks it")
  void shouldMarkAWaitingRequestReadAndLeaveItWaitingWhenItsRecipientMarksIt() throws Exception {
    var request = requestFor(riversideAdmin, steps.offer(sam, kai(), riverside));

    markOne(riversideAdmin, request.getId())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markNotificationRead.notification.unread").value(false))
        .andExpect(jsonPath("$.data.markNotificationRead.notification.resolution").doesNotExist())
        .andExpect(jsonPath("$.data.markNotificationRead.unreadNotificationCount").value(0));
  }

  @Test
  @DisplayName(
      "Should answer as missing and change nothing when the Notification is another Account's")
  void shouldAnswerAsMissingAndChangeNothingWhenTheNotificationIsAnotherAccounts()
      throws Exception {
    var news = acceptedNewsFor(theo);

    markOne(sam, news.getId())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markNotificationRead.notification").doesNotExist())
        .andExpect(jsonPath("$.data.markNotificationRead.unreadNotificationCount").doesNotExist())
        .andExpect(
            jsonPath("$.data.markNotificationRead.userErrors[0].__typename")
                .value("NotificationNotFoundError"))
        .andExpect(
            jsonPath("$.data.markNotificationRead.userErrors[0].inputPath[0]")
                .value("notificationId"));

    assertThat(stored(news).getReadAt()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should answer as missing and leave it unread when the guard hides the Notification from its"
          + " recipient")
  void shouldAnswerAsMissingAndLeaveItUnreadWhenTheGuardHidesTheNotification() throws Exception {
    var request = requestFor(riversideAdmin, steps.offer(sam, kai(), riverside));
    demote(riversideAdmin);

    markOne(riversideAdmin, request.getId())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.markNotificationRead.userErrors[0].__typename")
                .value("NotificationNotFoundError"));

    assertThat(stored(request).getReadAt()).isEmpty();
  }

  @Test
  @DisplayName("Should answer that the ID is invalid when it is not one")
  void shouldAnswerThatTheIdIsInvalidWhenItIsNotOne() throws Exception {
    mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(theo), MARK_ONE.formatted("Kai")))
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.markNotificationRead.userErrors[0].__typename")
                .value("InvalidIdError"))
        .andExpect(
            jsonPath("$.data.markNotificationRead.userErrors[0].inputPath[0]")
                .value("notificationId"));
  }

  @Test
  @DisplayName("Should refuse both marks when the session is device-bound")
  void shouldRefuseBothMarksWhenTheSessionIsDeviceBound() throws Exception {
    var news = acceptedNewsFor(theo);
    var bearer = authTestSupport.deviceBoundProfileBearer(theo);

    mockMvc
        .perform(graphqlRequest(bearer, MARK_ONE.formatted(news.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));
    mockMvc
        .perform(graphqlRequest(bearer, MARK_ALL.formatted("anything")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

    assertThat(stored(news).getReadAt()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should mark read every Notification at or below the cursor and leave a newer one unread")
  void shouldMarkReadEveryNotificationAtOrBelowTheCursorAndLeaveANewerOneUnread() throws Exception {
    var oldest = requestFor(riversideAdmin, steps.offer(sam, kai(), riverside));
    var through = requestFor(riversideAdmin, steps.offer(sam, ollie(), riverside));
    var cursor = cursorOf(riversideAdmin, through);
    var newer =
        requestFor(
            riversideAdmin, steps.offer(sam, steps.profileManagedBy("Ash", sam, sam), riverside));

    markAll(riversideAdmin, cursor)
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markAllNotificationsRead.unreadNotificationCount").value(1))
        .andExpect(jsonPath("$.data.markAllNotificationsRead.userErrors").isEmpty());

    assertThat(stored(oldest).getReadAt()).isPresent();
    assertThat(stored(through).getReadAt()).isPresent();
    assertThat(stored(newer).getReadAt()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should leave unread the Notifications that the guard hides when its recipient marks all"
          + " read")
  void shouldLeaveUnreadTheNotificationsThatTheGuardHidesWhenItsRecipientMarksAllRead()
      throws Exception {
    var hidden = requestFor(riversideAdmin, steps.offer(sam, kai(), riverside));
    var cursor = cursorOf(riversideAdmin, hidden);
    demote(riversideAdmin);

    markAll(riversideAdmin, cursor)
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.markAllNotificationsRead.unreadNotificationCount").value(0));

    assertThat(stored(hidden).getReadAt()).isEmpty();
  }

  @Test
  @DisplayName("Should answer that the cursor is invalid when the server cannot read it")
  void shouldAnswerThatTheCursorIsInvalidWhenTheServerCannotReadIt() throws Exception {
    markAll(theo, "not-a-cursor")
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.markAllNotificationsRead.unreadNotificationCount").doesNotExist())
        .andExpect(
            jsonPath("$.data.markAllNotificationsRead.userErrors[0].__typename")
                .value("InvalidCursorError"))
        .andExpect(
            jsonPath("$.data.markAllNotificationsRead.userErrors[0].inputPath[0]")
                .value("through"));
  }

  private ResultActions markOne(TestIdentity reader, UUID notificationId) throws Exception {
    return mockMvc
        .perform(
            graphqlRequest(
                authTestSupport.accountBearer(reader), MARK_ONE.formatted(notificationId)))
        .andExpect(status().isOk());
  }

  private ResultActions markAll(TestIdentity reader, String through) throws Exception {
    return mockMvc
        .perform(graphqlRequest(authTestSupport.accountBearer(reader), MARK_ALL.formatted(through)))
        .andExpect(status().isOk());
  }

  private String cursorOf(TestIdentity reader, Notification notification) throws Exception {
    var response =
        mockMvc
            .perform(
                graphqlRequest(
                    authTestSupport.accountBearer(reader),
                    "query { notifications(first: 50) { edges { cursor node { id } } } }"))
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> cursors =
        JsonPath.read(
            response,
            "$.data.notifications.edges[?(@.node.id == '%s')].cursor"
                .formatted(notification.getId()));
    return cursors.getFirst();
  }

  private Notification acceptedNewsFor(TestIdentity recipient) {
    var offer = steps.offer(sam, steps.profileManagedBy("Kai", sam, sam, theo), riverside);
    steps.accept(riverside, offer);
    return notificationRepository.findAll().stream()
        .filter(notification -> notification.getShareId().equals(offer.getId()))
        .filter(
            notification ->
                notification.getRecipientAccountId().equals(recipient.account().getId()))
        .findFirst()
        .orElseThrow();
  }

  private Notification requestFor(TestIdentity recipient, ProfileHouseholdShare offer) {
    return notificationRepository.findAll().stream()
        .filter(notification -> notification.getShareId().equals(offer.getId()))
        .filter(
            notification ->
                notification.getRecipientAccountId().equals(recipient.account().getId()))
        .findFirst()
        .orElseThrow();
  }

  private Notification stored(Notification notification) {
    return notificationRepository.findById(notification.getId()).orElseThrow();
  }

  private Profile kai() {
    return steps.profileManagedBy("Kai", sam, sam);
  }

  private Profile ollie() {
    return steps.profileManagedBy("Ollie", sam, sam);
  }

  private void demote(TestIdentity householdAdmin) {
    transactionTemplate.executeWithoutResult(
        _ -> userAccountRepository.tryDemoteToHouseholdMember(householdAdmin.account().getId()));
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
