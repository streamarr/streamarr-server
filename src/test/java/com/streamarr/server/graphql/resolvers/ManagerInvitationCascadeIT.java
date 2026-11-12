package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.ManagerInvitationAction;
import com.streamarr.server.domain.auth.ProfileManagerInvitationStatus;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.services.events.auth.ProfileManagerInvitationChangedEvent;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ProfileCastSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * An invitation to manage a Profile stops waiting, and says so, whenever what it rests on goes (ADR
 * 0024 §Invitations): its Profile, its inviter, the Account it names, the inviter's management, or
 * the need for it once a ServerAdmin grants management directly.
 */
@Tag("IntegrationTest")
@DisplayName("Manager Invitation Cascade Integration Tests")
@RecordApplicationEvents
class ManagerInvitationCascadeIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private ApplicationEvents events;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity serverAdmin;
  private TestIdentity sam;
  private TestIdentity cam;
  private TestIdentity mara;

  @BeforeEach
  void setUp() {
    serverAdmin = track(authTestSupport.createAdminIdentity());
    sam = track(authTestSupport.createIdentity());
    cam = track(authTestSupport.createHouseholdMemberIdentity(sam));
    mara = track(authTestSupport.createHouseholdMemberIdentity(sam));
  }

  @AfterEach
  void tearDown() {
    invitationRepository.deleteAll();
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName("Should withdraw and announce the invitation when a ServerAdmin deletes its Profile")
  void shouldWithdrawAndAnnounceTheInvitationWhenAServerAdminDeletesItsProfile() throws Exception {
    var alex =
        cast.unavailableProfileManagedBy(sam, ProfileFixture.defaultProfileBuilder().name("Alex"));
    var invitationId = invite(sam, alex.getId(), mara);

    graphql(
        authTestSupport.freshAccountBearer(serverAdmin),
        """
        mutation { administrativelyDeleteProfile(input: {profileId: "%s", reason: "retired"}) {
          userErrors { __typename } } }
        """
            .formatted(alex.getId()),
        "/data/administrativelyDeleteProfile/userErrors");

    assertWithdrawnAndAnnounced(invitationId);
  }

  @Test
  @DisplayName("Should withdraw and announce the invitation when its inviter is deleted")
  void shouldWithdrawAndAnnounceTheInvitationWhenItsInviterIsDeleted() throws Exception {
    var invitationId = invite(cam, cam.profile().getId(), mara);

    deleteAccount(cam);

    assertWithdrawnAndAnnounced(invitationId);
  }

  @Test
  @DisplayName(
      "Should invalidate the invitation for its Account alone when the Account it names is"
          + " deleted")
  void shouldInvalidateTheInvitationForItsAccountAloneWhenTheAccountItNamesIsDeleted()
      throws Exception {
    var invitationId = invite(cam, cam.profile().getId(), mara);

    deleteAccount(mara);

    assertInvalidatedForItsAccountAlone(invitationId);
  }

  @Test
  @DisplayName("Should withdraw and announce the invitation when its inviter gives up management")
  void shouldWithdrawAndAnnounceTheInvitationWhenItsInviterGivesUpManagement() throws Exception {
    var alex = cast.profileManagedBy(sam, ProfileFixture.defaultProfileBuilder().name("Alex"));
    cast.grant(cam, alex);
    var invitationId = invite(cam, alex.getId(), mara);

    graphql(
        authTestSupport.accountBearer(cam),
        """
        mutation { relinquishProfileManagement(input: {profileId: "%s"}) {
          profileId userErrors { __typename } } }
        """
            .formatted(alex.getId()),
        "/data/relinquishProfileManagement/userErrors");

    assertWithdrawnAndAnnounced(invitationId);
  }

  @Test
  @DisplayName(
      "Should withdraw and announce the invitation when the inviter's own Personal Profile is"
          + " restricted")
  void shouldWithdrawTheInvitationWhenTheInvitersOwnPersonalProfileIsRestricted() throws Exception {
    // A restricted Profile needs an eligible manager besides its own Account.
    cast.grant(sam, cam.profile());
    var invitationId = invite(cam, cam.profile().getId(), mara);

    graphql(
        authTestSupport.freshAccountBearer(serverAdmin),
        """
        mutation { setProfileMaximumAllowedRatingAge(input: {profileId: "%s",
          maximumAllowedRatingAge: 12}) { profile { id } userErrors { __typename } } }
        """
            .formatted(cam.profile().getId()),
        "/data/setProfileMaximumAllowedRatingAge/userErrors");

    assertWithdrawnAndAnnounced(invitationId);
  }

  @Test
  @DisplayName(
      "Should invalidate the invitation for its Account alone when a ServerAdmin grants that"
          + " Account management")
  void shouldInvalidateTheInvitationForItsAccountAloneWhenAServerAdminGrantsThatAccountManagement()
      throws Exception {
    var alex = cast.profileManagedBy(sam, ProfileFixture.defaultProfileBuilder().name("Alex"));
    var invitationId = invite(sam, alex.getId(), mara);

    graphql(
        authTestSupport.freshAccountBearer(serverAdmin),
        """
        mutation { administrativelyGrantProfileManager(input: {profileId: "%s", accountId: "%s",
          reason: "support"}) { profileId userErrors { __typename } } }
        """
            .formatted(alex.getId(), mara.account().getId()),
        "/data/administrativelyGrantProfileManager/userErrors");

    assertInvalidatedForItsAccountAlone(invitationId);
  }

  @Test
  @DisplayName(
      "Should withdraw and announce the invitation when a ServerAdmin removes its inviter as a"
          + " manager")
  void shouldWithdrawAndAnnounceTheInvitationWhenAServerAdminRemovesItsInviterAsAManager()
      throws Exception {
    var alex = cast.profileManagedBy(sam, ProfileFixture.defaultProfileBuilder().name("Alex"));
    cast.grant(cam, alex);
    var invitationId = invite(cam, alex.getId(), mara);

    graphql(
        authTestSupport.freshAccountBearer(serverAdmin),
        """
        mutation { administrativelyRemoveProfileManager(input: {profileId: "%s", accountId: "%s",
          reason: "abuse report"}) { profileId userErrors { __typename } } }
        """
            .formatted(alex.getId(), cam.account().getId()),
        "/data/administrativelyRemoveProfileManager/userErrors");

    assertWithdrawnAndAnnounced(invitationId);
  }

  private void assertWithdrawnAndAnnounced(UUID invitationId) {
    assertThat(invitationRepository.findById(invitationId).orElseThrow().getStatus())
        .isEqualTo(ProfileManagerInvitationStatus.INVALIDATED);
    assertThat(events.stream(ProfileManagerInvitationChangedEvent.class))
        .filteredOn(event -> event.invitation().invitationId().equals(invitationId))
        .extracting(ProfileManagerInvitationChangedEvent::action)
        .containsExactly(
            new ManagerInvitationAction.Issued(), new ManagerInvitationAction.StoppedWaiting());
  }

  // To the Profile's managers it still waits (ADR 0024 §Invitations).
  private void assertInvalidatedForItsAccountAlone(UUID invitationId) {
    assertThat(invitationRepository.findById(invitationId).orElseThrow())
        .satisfies(
            invitation -> {
              assertThat(invitation.getStatus()).isEqualTo(ProfileManagerInvitationStatus.PENDING);
              assertThat(invitation.getRecipientInvalidatedAt()).isNotNull();
            });
    assertThat(events.stream(ProfileManagerInvitationChangedEvent.class))
        .filteredOn(event -> event.invitation().invitationId().equals(invitationId))
        .extracting(ProfileManagerInvitationChangedEvent::action)
        .containsExactly(
            new ManagerInvitationAction.Issued(),
            new ManagerInvitationAction.InvalidatedForItsAccount());
  }

  private UUID invite(TestIdentity inviter, UUID profileId, TestIdentity invitee) throws Exception {
    var answer =
        graphql(
            authTestSupport.accountBearer(inviter),
            """
            mutation { inviteProfileManager(input: {profileId: "%s", email: "%s"}) {
              issued { invitation { id } } userErrors { __typename } } }
            """
                .formatted(profileId, invitee.account().getEmail()),
            "/data/inviteProfileManager/userErrors");
    return UUID.fromString(answer.at("/data/inviteProfileManager/issued/invitation/id").asString());
  }

  private void deleteAccount(TestIdentity doomed) throws Exception {
    graphql(
        authTestSupport.freshAccountBearer(serverAdmin),
        """
        mutation { administrativelyDeleteAccount(input: {accountId: "%s",
          profileCleanup: ERASE_PROFILE, reason: "retired"}) {
          accountId userErrors { __typename } } }
        """
            .formatted(doomed.account().getId()),
        "/data/administrativelyDeleteAccount/userErrors");
  }

  /** Answers the response once it carries no errors and its user errors are empty. */
  private JsonNode graphql(String bearer, String query, String userErrors) throws Exception {
    var response =
        mockMvc
            .perform(graphqlRequest(bearer, query))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var answer = objectMapper.readTree(response);
    assertThat(answer.at(userErrors)).as(userErrors).isEmpty();
    return answer;
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
