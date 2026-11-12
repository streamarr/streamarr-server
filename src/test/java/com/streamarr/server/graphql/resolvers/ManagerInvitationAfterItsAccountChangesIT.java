package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.domain.auth.ProfileManagerInvitation;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.identity.ProfileManagerAdministrationService;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ManagerInvitationSteps;
import com.streamarr.server.support.ProfileCastSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
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
import tools.jackson.databind.ObjectMapper;

/**
 * An invitation that the server invalidates because of what became of its invited Account still
 * waits, to the Profile's managers, exactly as an invitation that nobody can answer does (ADR 0024
 * §Invitations): otherwise the inviter could watch it and learn that the address had an Account.
 * Only the invited Account and a ServerAdmin read that it was invalidated.
 */
@Tag("IntegrationTest")
@DisplayName("Manager Invitation After Its Account Changes Integration Tests")
class ManagerInvitationAfterItsAccountChangesIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private ProfileManagerAdministrationService managerService;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private UserAccountRepository userAccountRepository;

  private final List<TestIdentity> identities = new ArrayList<>();
  private ManagerInvitationSteps steps;
  private TestIdentity serverAdmin;
  private TestIdentity sam;
  private TestIdentity mara;
  private Profile alex;

  /** What becomes of the Account that the invitation names. */
  enum Change {
    DELETED,
    RESTRICTED,
    GRANTED_MANAGEMENT
  }

  @BeforeEach
  void setUp() {
    invitationRepository.deleteAll();
    steps =
        ManagerInvitationSteps.builder()
            .management(managerService)
            .auth(authTestSupport)
            .invitations(invitationRepository)
            .transactions(transactionTemplate)
            .build();
    serverAdmin = track(authTestSupport.createAdminIdentity());
    sam = track(authTestSupport.createIdentity());
    mara = track(authTestSupport.createHouseholdMemberIdentity(sam));
    alex = cast.profileManagedBy(sam, ProfileFixture.defaultProfileBuilder().name("Alex"));
  }

  @AfterEach
  void tearDown() {
    invitationRepository.deleteAll();
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @ParameterizedTest
  @EnumSource(Change.class)
  @DisplayName(
      "Should read as waiting to the Profile's managers, as an invitation nobody can answer, when"
          + " its Account changes")
  void shouldReadAsWaitingToTheProfilesManagersWhenItsAccountChanges(Change change)
      throws Exception {
    var toNobody = invite(nobody());
    var toMara = invite(mara.account().getEmail());

    change(change);

    assertThat(pendingFor(sam, alex)).containsExactlyInAnyOrder(toNobody, toMara);
    assertThat(statusAs(sam, toMara)).isEqualTo(statusAs(sam, toNobody)).isEqualTo("PENDING");
  }

  @ParameterizedTest
  @EnumSource(Change.class)
  @DisplayName(
      "Should answer its inviter's lookups of its code as for an invitation nobody can answer when"
          + " its Account changes")
  void shouldAnswerItsInvitersLookupsOfItsCodeAsForAnInvitationNobodyCanAnswerWhenItsAccountChanges(
      Change change) throws Exception {
    var toNobody = steps.invite(sam, alex, nobody()).code();
    var toMara = steps.invite(sam, alex, mara.account().getEmail()).code();
    change(change);

    // One lookup more than the wrong codes that a code's budget allows.
    for (var lookup = 0; lookup < 6; lookup++) {
      var answer = lookUp(sam, toMara);
      assertThat(answer).isEqualTo(lookUp(sam, toNobody));
      assertThat(answer.at("/data/lookUpManagerInvitationCode/userErrors/0/__typename").asString())
          .isEqualTo("ManagerInvitationNotFoundError");
    }
  }

  @ParameterizedTest
  @EnumSource(Change.class)
  @DisplayName(
      "Should let its inviter cancel it as any waiting invitation when its Account changes")
  void shouldLetItsInviterCancelItAsAnyWaitingInvitationWhenItsAccountChanges(Change change)
      throws Exception {
    var toMara = invite(mara.account().getEmail());
    change(change);

    var answer =
        graphql(
                authTestSupport.accountBearer(sam),
                """
                mutation { cancelManagerInvitation(input: {invitationId: "%s"}) {
                  invitation { status } userErrors { __typename } } }
                """
                    .formatted(toMara))
            .at("/data/cancelManagerInvitation");

    assertThat(answer.at("/userErrors")).isEmpty();
    assertThat(answer.at("/invitation/status").asString()).isEqualTo("CANCELED");
  }

  @ParameterizedTest
  @EnumSource(Change.class)
  @DisplayName("Should count it toward the limit of ten when its Account changes")
  void shouldCountItTowardTheLimitOfTenWhenItsAccountChanges(Change change) throws Exception {
    invite(mara.account().getEmail());
    IntStream.range(0, 9).forEach(_ -> invite(nobody()));
    change(change);

    var eleventh =
        graphql(
                authTestSupport.accountBearer(sam),
                """
                mutation { inviteProfileManager(input: {profileId: "%s", email: "%s"}) {
                  userErrors { __typename } } }
                """
                    .formatted(alex.getId(), nobody()))
            .at("/data/inviteProfileManager/userErrors/0/__typename");

    assertThat(eleventh.asString()).isEqualTo("ManagerInvitationLimitReachedError");
  }

  @ParameterizedTest
  @EnumSource(Change.class)
  @DisplayName("Should replace it when the same address is invited again after its Account changes")
  void shouldReplaceItWhenTheSameAddressIsInvitedAgainAfterItsAccountChanges(Change change)
      throws Exception {
    var toMara = invite(mara.account().getEmail());
    change(change);

    var newer = invite(mara.account().getEmail());

    assertThat(pendingFor(sam, alex)).containsExactly(newer);
    assertThat(statusAs(sam, toMara)).isEqualTo("INVALIDATED");
  }

  @ParameterizedTest
  @EnumSource(Change.class)
  @DisplayName(
      "Should read as expired to the Profile's managers when its expiry passes after its Account"
          + " changes")
  void shouldReadAsExpiredToTheProfilesManagersWhenItsExpiryPassesAfterItsAccountChanges(
      Change change) throws Exception {
    var toMara = invite(mara.account().getEmail());
    change(change);

    steps.lapse(invitationRepository.findById(toMara).orElseThrow());

    assertThat(pendingFor(sam, alex)).isEmpty();
    assertThat(statusAs(sam, toMara)).isEqualTo("EXPIRED");
  }

  @ParameterizedTest
  @EnumSource(Change.class)
  @DisplayName("Should read as invalidated when a ServerAdmin reads it after its Account changes")
  void shouldReadAsInvalidatedWhenAServerAdminReadsItAfterItsAccountChanges(Change change)
      throws Exception {
    var toMara = invite(mara.account().getEmail());
    change(change);

    assertThat(statusAs(serverAdmin, toMara)).isEqualTo("INVALIDATED");
    assertThat(pendingFor(serverAdmin, alex)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = Change.class,
      names = {"RESTRICTED", "GRANTED_MANAGEMENT"})
  @DisplayName(
      "Should read as invalidated and refuse an answer when its Account reads it after it changed")
  void shouldReadAsInvalidatedAndRefuseAnAnswerWhenItsAccountReadsItAfterItChanged(Change change)
      throws Exception {
    var toMara = invite(mara.account().getEmail());
    change(change);

    var acceptance =
        graphql(
                authTestSupport.accountBearer(mara),
                """
                mutation { acceptManagerInvitation(input: {invitationId: "%s"}) {
                  userErrors { __typename } } }
                """
                    .formatted(toMara))
            .at("/data/acceptManagerInvitation/userErrors/0/__typename");

    assertThat(statusAs(mara, toMara)).isEqualTo("INVALIDATED");
    assertThat(acceptance.asString()).isEqualTo("InvitationNotPendingError");
    assertThat(
            graphql(
                    authTestSupport.accountBearer(mara),
                    "query { pendingManagerInvitations { edges { node { id } } } }")
                .at("/data/pendingManagerInvitations/edges"))
        .isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = Change.class,
      names = {"DELETED", "RESTRICTED"})
  @DisplayName(
      "Should block deleting its Profile, as any waiting invitation, when its Account changes")
  void shouldBlockDeletingItsProfileAsAnyWaitingInvitationWhenItsAccountChanges(Change change)
      throws Exception {
    var kit =
        cast.unavailableProfileManagedBy(sam, ProfileFixture.defaultProfileBuilder().name("Kit"));
    steps.invite(sam, kit, mara.account().getEmail());
    change(change);

    var refusal =
        graphql(
                authTestSupport.freshAccountBearer(sam),
                """
                mutation { deleteProfile(input: {profileId: "%s"}) {
                  deletedProfileId userErrors { __typename } } }
                """
                    .formatted(kit.getId()))
            .at("/data/deleteProfile/userErrors/0/__typename");

    assertThat(refusal.asString()).isEqualTo("ProfileNotDeletableError");
  }

  @Test
  @DisplayName(
      "Should invalidate the other invitation for its Account when that Account accepts one sent"
          + " to its new address")
  void shouldInvalidateTheOtherInvitationForItsAccountWhenThatAccountAcceptsOneSentToItsNewAddress()
      throws Exception {
    var toOldAddress = invite(mara.account().getEmail());
    var newAddress = "mara.%s@example.com".formatted(UUID.randomUUID());
    changeEmail(mara, newAddress);
    var toNewAddress = invite(newAddress);
    assertThat(received(mara)).containsExactlyInAnyOrder(toOldAddress, toNewAddress);

    act(
        authTestSupport.accountBearer(mara),
        """
        mutation { acceptManagerInvitation(input: {invitationId: "%s"}) {
          userErrors { __typename } } }
        """
            .formatted(toNewAddress));

    assertThat(statusAs(mara, toOldAddress)).isEqualTo("INVALIDATED");
    assertThat(statusAs(sam, toOldAddress)).isEqualTo("PENDING");
  }

  @Test
  @DisplayName(
      "Should invalidate an invitation for its Account when that Account gives up managing the"
          + " Profile")
  void shouldInvalidateAnInvitationForItsAccountWhenThatAccountGivesUpManagingTheProfile()
      throws Exception {
    cast.grant(mara, alex);
    // No invite can name a manager; a write that raced in before the grant could.
    var raced = savePending(alex, sam, mara);

    act(
        authTestSupport.accountBearer(mara),
        """
        mutation { relinquishProfileManagement(input: {profileId: "%s"}) {
          userErrors { __typename } } }
        """
            .formatted(alex.getId()));

    assertThat(invitationRepository.findById(raced).orElseThrow().getRecipientInvalidatedAt())
        .isNotNull();
  }

  @Test
  @DisplayName(
      "Should invalidate an invitation for its Account when that Account becomes the replacement"
          + " manager of a preserved Personal Profile")
  void
      shouldInvalidateAnInvitationForItsAccountWhenThatAccountBecomesTheReplacementManagerOfAPreservedPersonalProfile()
          throws Exception {
    var cam = track(authTestSupport.createHouseholdMemberIdentity(sam));
    cast.grant(sam, cam.profile());
    var toMara = steps.invite(sam, cam.profile(), mara.account().getEmail()).invitation().getId();

    act(
        authTestSupport.freshAccountBearer(serverAdmin),
        """
        mutation { administrativelyDeleteAccount(input: {accountId: "%s",
          profileCleanup: PRESERVE_PROFILE, replacementManagerAccountId: "%s",
          reason: "moved away"}) { userErrors { __typename } } }
        """
            .formatted(cam.account().getId(), mara.account().getId()));

    assertThat(statusAs(mara, toMara)).isEqualTo("INVALIDATED");
    assertThat(statusAs(sam, toMara)).isEqualTo("PENDING");
  }

  private void change(Change change) throws Exception {
    switch (change) {
      case DELETED ->
          act(
              authTestSupport.freshAccountBearer(serverAdmin),
              """
              mutation { administrativelyDeleteAccount(input: {accountId: "%s",
                profileCleanup: ERASE_PROFILE, reason: "retired"}) { userErrors { __typename } } }
              """
                  .formatted(mara.account().getId()));
      case RESTRICTED -> {
        // A restricted Profile needs an eligible manager besides its own Account.
        cast.grant(sam, mara.profile());
        act(
            authTestSupport.freshAccountBearer(serverAdmin),
            """
            mutation { setProfileMaximumAllowedRatingAge(input: {profileId: "%s",
              maximumAllowedRatingAge: 12}) { userErrors { __typename } } }
            """
                .formatted(mara.profile().getId()));
      }
      case GRANTED_MANAGEMENT ->
          act(
              authTestSupport.freshAccountBearer(serverAdmin),
              """
              mutation { administrativelyGrantProfileManager(input: {profileId: "%s",
                accountId: "%s", reason: "support"}) { userErrors { __typename } } }
              """
                  .formatted(alex.getId(), mara.account().getId()));
    }
  }

  /** Runs the mutation, which must succeed. */
  private void act(String bearer, String mutation) throws Exception {
    assertThat(graphql(bearer, mutation).findValue("userErrors")).isEmpty();
  }

  private void changeEmail(TestIdentity identity, String email) {
    transactionTemplate.executeWithoutResult(
        _ -> {
          var account = userAccountRepository.findById(identity.account().getId()).orElseThrow();
          account.setEmail(email);
          userAccountRepository.saveAndFlush(account);
        });
  }

  private UUID savePending(Profile profile, TestIdentity inviter, TestIdentity invitee) {
    return transactionTemplate.execute(
        _ ->
            invitationRepository
                .saveAndFlush(
                    ProfileManagerInvitation.builder()
                        .profileId(profile.getId())
                        .profileName(profile.getName())
                        .inviterAccountId(inviter.account().getId())
                        .inviterDisplayName("Sam")
                        .recipientAccountId(invitee.account().getId())
                        .recipientEmail(invitee.account().getEmail())
                        .expiresAt(Instant.now().plusSeconds(3600))
                        .publicId(UUID.randomUUID().toString())
                        .secretDigest(new byte[] {1})
                        .build())
                .getId());
  }

  private List<UUID> received(TestIdentity identity) throws Exception {
    return graphql(
            authTestSupport.accountBearer(identity),
            "query { pendingManagerInvitations { edges { node { id } } } }")
        .at("/data/pendingManagerInvitations/edges")
        .valueStream()
        .map(edge -> UUID.fromString(edge.at("/node/id").asString()))
        .toList();
  }

  private UUID invite(String address) {
    return steps.invite(sam, alex, address).invitation().getId();
  }

  private static String nobody() {
    return "nobody.%s@example.com".formatted(UUID.randomUUID());
  }

  private List<UUID> pendingFor(TestIdentity reader, Profile profile) throws Exception {
    return graphql(
            authTestSupport.accountBearer(reader),
            """
            query { pendingManagerInvitationsForProfile(profileId: "%s") {
              edges { node { id } } } }
            """
                .formatted(profile.getId()))
        .at("/data/pendingManagerInvitationsForProfile/edges")
        .valueStream()
        .map(edge -> UUID.fromString(edge.at("/node/id").asString()))
        .toList();
  }

  private String statusAs(TestIdentity reader, UUID invitationId) throws Exception {
    return graphql(
            authTestSupport.accountBearer(reader),
            """
            query { managerInvitation(invitationId: "%s") { status } }
            """
                .formatted(invitationId))
        .at("/data/managerInvitation/status")
        .asString(null);
  }

  /** The whole answer to the lookup, a refusal of the attempt included. */
  private JsonNode lookUp(TestIdentity reader, String code) throws Exception {
    var response =
        mockMvc
            .perform(
                graphqlRequest(
                    authTestSupport.accountBearer(reader),
                    """
                    mutation { lookUpManagerInvitationCode(input: {code: "%s"}) {
                      invitation { id } userErrors { __typename } } }
                    """
                        .formatted(code)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response);
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
