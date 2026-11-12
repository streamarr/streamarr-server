package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ProfileCastSupport;
import com.streamarr.server.support.StatementCount;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The page where the Account an invitation names accepts or declines it: the Profile's name, the
 * inviter's name and the expiry, read by id (streamarr-server#907).
 */
@Tag("IntegrationTest")
@DisplayName("Manager Invitation Page Integration Tests")
class ManagerInvitationPageIT extends AbstractIntegrationTest {

  private static final String FIELDS =
      "id profileId profileName inviterAccountId inviterDisplayName recipientEmail status"
          + " expiresAt addressedToReader";

  private static final String INVITATION =
      "query { managerInvitation(invitationId: \"%s\") { %s } }";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private StatementCount statementCount;
  @Autowired private ProfileManagerInvitationRepository invitationRepository;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity sam;
  private TestIdentity mara;
  private Profile alex;

  @BeforeEach
  void setUp() {
    sam = track(authTestSupport.createIdentity());
    cast.rename(sam, "Sam");
    mara = track(authTestSupport.createIdentity());
    alex = cast.profileManagedBy(sam, ProfileFixture.defaultProfileBuilder().name("Alex"));
  }

  @AfterEach
  void tearDown() {
    invitationRepository.deleteAll();
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName(
      "Should show the Profile's current name when the Profile was renamed after the invitation")
  void shouldShowTheProfilesCurrentNameWhenTheProfileWasRenamedAfterTheInvitation()
      throws Exception {
    var issued = invite(sam, alex, mara);
    cast.rename(alex, "Alex Smith");
    cast.rename(sam, "Sam Smith");

    var invitation = invitation(mara, issued.path("id").asString());

    assertThat(invitation.path("profileName").asString()).isEqualTo("Alex Smith");
    assertThat(invitation.path("inviterDisplayName").asString()).isEqualTo("Sam Smith");
    assertThat(invitation.path("status").asString()).isEqualTo("PENDING");
    assertThat(invitation.path("expiresAt").asString())
        .isEqualTo(issued.path("expiresAt").asString());
    var received = received(mara).at("/data/pendingManagerInvitations/edges/0/node");
    assertThat(received.path("profileName").asString()).isEqualTo("Alex Smith");
    assertThat(received.path("inviterDisplayName").asString()).isEqualTo("Sam Smith");
  }

  @Test
  @DisplayName("Should answer null when an Account that is not named reads the invitation")
  void shouldAnswerNullWhenAnAccountThatIsNotNamedReadsTheInvitation() throws Exception {
    var invitationId = invite(sam, alex, mara).path("id").asString();
    var stranger = track(authTestSupport.createIdentity());

    assertThat(invitation(stranger, invitationId).isNull()).isTrue();
    assertThat(invitation(mara, UUID.randomUUID().toString()).isNull()).isTrue();
    assertThat(
            graphql(
                    authTestSupport.deviceBoundProfileBearer(mara),
                    INVITATION.formatted(invitationId, "id"))
                .at("/data/managerInvitation")
                .isNull())
        .isTrue();
  }

  @Test
  @DisplayName("Should let the Profile's managers and a ServerAdmin read the invitation")
  void shouldLetTheProfilesManagersAndAServerAdminReadTheInvitation() throws Exception {
    var invitationId = invite(sam, alex, mara).path("id").asString();
    var serverAdmin = track(authTestSupport.createAdminIdentity());

    assertThat(invitation(sam, invitationId).path("id").asString()).isEqualTo(invitationId);
    assertThat(invitation(serverAdmin, invitationId).path("id").asString()).isEqualTo(invitationId);
  }

  @Test
  @DisplayName(
      "Should answer null when a HouseholdAdmin who only supervises the Profile reads the"
          + " invitation")
  void shouldAnswerNullWhenAHouseholdAdminWhoOnlySupervisesTheProfileReadsTheInvitation()
      throws Exception {
    var kit = cast.profileManagedBy(sam, ProfileFixture.kidProfileBuilder().name("Kit"));
    var invitationId = invite(sam, kit, mara).path("id").asString();
    var supervisor = cast.secondAdminOf(sam);

    assertThat(invitation(supervisor, invitationId).isNull()).isTrue();
  }

  @Test
  @DisplayName(
      "Should answer not found when a HouseholdAdmin who only supervises the Profile cancels the"
          + " invitation")
  void shouldAnswerNotFoundWhenAHouseholdAdminWhoOnlySupervisesTheProfileCancelsTheInvitation()
      throws Exception {
    var kit = cast.profileManagedBy(sam, ProfileFixture.kidProfileBuilder().name("Kit"));
    var invitationId = invite(sam, kit, mara).path("id").asString();
    var supervisor = cast.secondAdminOf(sam);

    var answer =
        graphql(
                authTestSupport.accountBearer(supervisor),
                """
                mutation { cancelManagerInvitation(input: {invitationId: "%s"}) {
                  invitation { id } userErrors { __typename } } }
                """
                    .formatted(invitationId))
            .at("/data/cancelManagerInvitation");

    assertThat(answer.at("/userErrors/0/__typename").asString())
        .isEqualTo("ManagerInvitationNotFoundError");
    assertThat(invitation(sam, invitationId).path("status").asString()).isEqualTo("PENDING");
  }

  @Test
  @DisplayName("Should answer null when the named Account is disabled")
  void shouldAnswerNullWhenTheNamedAccountIsDisabled() throws Exception {
    var invitationId = invite(sam, alex, mara).path("id").asString();
    cast.enable(mara, false);

    assertThat(invitation(mara, invitationId).isNull()).isTrue();
  }

  @Test
  @DisplayName("Should show the invitation when a manager who did not send it reads it")
  void shouldShowTheInvitationWhenAManagerWhoDidNotSendItReadsIt() throws Exception {
    var invitationId = invite(sam, alex, mara).path("id").asString();
    var kai = track(authTestSupport.createIdentity());
    cast.grant(kai, alex);

    assertThat(invitation(kai, invitationId).path("id").asString()).isEqualTo(invitationId);
  }

  @Test
  @DisplayName(
      "Should say the invitation is addressed to the reader only when its named Account reads it")
  void shouldSayTheInvitationIsAddressedToTheReaderOnlyWhenItsNamedAccountReadsIt()
      throws Exception {
    var issued = invite(sam, alex, mara);
    var invitationId = issued.path("id").asString();
    var serverAdmin = track(authTestSupport.createAdminIdentity());

    assertThat(issued.path("addressedToReader").asBoolean()).isFalse();
    assertThat(invitation(mara, invitationId).path("addressedToReader").asBoolean()).isTrue();
    assertThat(invitation(sam, invitationId).path("addressedToReader").asBoolean()).isFalse();
    assertThat(invitation(serverAdmin, invitationId).path("addressedToReader").asBoolean())
        .isFalse();
    assertThat(
            graphql(
                    authTestSupport.accountBearer(mara),
                    "query { pendingManagerInvitations { edges { node { addressedToReader } } } }")
                .at("/data/pendingManagerInvitations/edges/0/node/addressedToReader")
                .asBoolean())
        .isTrue();
    assertThat(
            graphql(
                    authTestSupport.accountBearer(sam),
                    "query { pendingManagerInvitationsForProfile(profileId: \"%s\") { edges { node {"
                            .formatted(alex.getId())
                        + " addressedToReader } } } }")
                .at("/data/pendingManagerInvitationsForProfile/edges/0/node/addressedToReader")
                .asBoolean())
        .isFalse();
  }

  @Test
  @DisplayName("Should show the invitation at any status when its named Account reads it")
  void shouldShowTheInvitationAtAnyStatusWhenItsNamedAccountReadsIt() throws Exception {
    var invitationId = invite(sam, alex, mara).path("id").asString();
    graphql(
        authTestSupport.accountBearer(mara),
        """
        mutation { declineManagerInvitation(input: {invitationId: "%s"}) {
          userErrors { __typename } } }
        """
            .formatted(invitationId));

    assertThat(invitation(mara, invitationId).path("status").asString()).isEqualTo("DECLINED");
  }

  @Test
  @DisplayName(
      "Should read the received invitations in as many statements when more Profiles and inviters"
          + " wait")
  void shouldReadTheReceivedInvitationsInAsManyStatementsWhenMoreProfilesAndInvitersWait()
      throws Exception {
    invite(sam, alex, mara);
    var one = statementCount.statementsWhile(() -> received(mara));

    for (var name : List.of("Cam", "Kai")) {
      var inviter = track(authTestSupport.createIdentity());
      cast.rename(inviter, name);
      invite(inviter, cast.profileManagedBy(inviter, ProfileFixture.defaultProfileBuilder()), mara);
    }

    var three = statementCount.statementsWhile(() -> received(mara));

    assertThat(received(mara).at("/data/pendingManagerInvitations/edges").size()).isEqualTo(3);
    assertThat(three).isEqualTo(one);
  }

  private JsonNode invite(TestIdentity inviter, Profile profile, TestIdentity invitee)
      throws Exception {
    return graphql(
            authTestSupport.accountBearer(inviter),
            """
            mutation { inviteProfileManager(input: {profileId: "%s", email: "%s"}) {
              issued { invitation { %s } } userErrors { __typename } } }
            """
                .formatted(profile.getId(), invitee.account().getEmail(), FIELDS))
        .at("/data/inviteProfileManager/issued/invitation");
  }

  private JsonNode invitation(TestIdentity reader, String invitationId) throws Exception {
    return graphql(
            authTestSupport.accountBearer(reader), INVITATION.formatted(invitationId, FIELDS))
        .at("/data/managerInvitation");
  }

  private JsonNode received(TestIdentity identity) throws Exception {
    return graphql(
        authTestSupport.accountBearer(identity),
        "query { pendingManagerInvitations { edges { node { id profileName inviterDisplayName }"
            + " } } }");
  }

  private JsonNode graphql(String bearer, String query) throws Exception {
    var response =
        mockMvc
            .perform(graphqlRequest(bearer, query))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response);
  }

  private TestIdentity track(TestIdentity identity) {
    identities.add(identity);
    return identity;
  }
}
