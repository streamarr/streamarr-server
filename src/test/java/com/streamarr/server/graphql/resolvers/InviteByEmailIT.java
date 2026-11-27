package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.support.GraphQlTestSupport.graphqlRequest;
import static com.streamarr.server.support.ServerSettingsTestSupport.resetServerSettings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.Profile;
import com.streamarr.server.repositories.auth.ProfileManagerInvitationRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.identity.ProfileManagerAdministrationService;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.AuthTestSupport.TestIdentity;
import com.streamarr.server.support.ManagerInvitationSteps;
import com.streamarr.server.support.ProfileCastSupport;
import com.streamarr.server.support.StatementCount;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("IntegrationTest")
@DisplayName("Invite By Email Integration Tests")
class InviteByEmailIT extends AbstractIntegrationTest {

  private static final String INVITATION_FIELDS =
      "id profileId profileName inviterAccountId inviterDisplayName recipientEmail status"
          + " expiresAt";

  private static final String INVITE =
      """
      mutation { inviteProfileManager(input: {profileId: "%s", email: "%s"}) {
        issued { invitation { %s } code }
        userErrors { __typename ... on MutationError { message } } } }
      """;

  private static final String PENDING_FOR_PROFILE =
      """
      query { pendingManagerInvitationsForProfile(profileId: "%s") {
        edges { node { %s } } } }
      """;

  private static final String READ =
      """
      query { managerInvitation(invitationId: "%s") { %s } }
      """;

  private static final String RECEIVED =
      "query { pendingManagerInvitations { edges { node { id } } } }";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private ProfileCastSupport cast;
  @Autowired private StatementCount statementCount;
  @Autowired private ProfileManagerInvitationRepository invitationRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;
  @Autowired private ProfileManagerAdministrationService managerAdministration;

  private final List<TestIdentity> identities = new ArrayList<>();
  private TestIdentity sam;
  private TestIdentity alexsAccount;
  private Profile alex;

  /** Who, if anyone, has the address that Sam types. */
  enum Invitee {
    NO_ACCOUNT,
    ELIGIBLE_ACCOUNT,
    SUPERVISED_ACCOUNT,
    MANAGING_ACCOUNT,
    THE_PROFILES_OWN_ACCOUNT,
    THE_INVITERS_OWN_ADDRESS,
    DISABLED_ACCOUNT
  }

  @BeforeEach
  void setUp() {
    sam = track(authTestSupport.createIdentity());
    cast.rename(sam, "Sam");
    // Alex is a Personal Profile, so the Account that manages it as its own is an address too.
    alexsAccount = track(authTestSupport.createHouseholdMemberIdentity(sam));
    alex = alexsAccount.profile();
    cast.rename(alex, "Alex");
    cast.grant(sam, alex);
  }

  @AfterEach
  void tearDown() {
    invitationRepository.deleteAll();
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @ParameterizedTest
  @EnumSource(Invitee.class)
  @DisplayName(
      "Should give the same answer and the same list when the address has no Account, an"
          + " ineligible Account, a manager or an eligible Account")
  void
      shouldGiveTheSameAnswerAndTheSameListWhenTheAddressHasNoAccountAnIneligibleAccountAManagerOrAnEligibleAccount(
          Invitee invitee) throws Exception {
    var typed = "  " + addressOf(invitee).toUpperCase(Locale.ROOT) + " ";

    var answer = invite(typed);
    var list = pendingForAlex();
    var read = readAsSam(answer.at(invitationPath("id")).asString());

    assertThat(masked(answer.at("/data/inviteProfileManager"), typed.strip()))
        .isEqualTo(
            objectMapper.readTree(
                """
                {"issued": {"invitation": %s, "code": "CODE"}, "userErrors": []}
                """
                    .formatted(maskedInvitation())));
    assertThat(list.at("/data/pendingManagerInvitationsForProfile/edges"))
        .singleElement()
        .satisfies(
            edge ->
                assertThat(maskedInvitation(edge.path("node"), typed.strip()))
                    .isEqualTo(objectMapper.readTree(maskedInvitation())));
    assertThat(maskedInvitation(read.at("/data/managerInvitation"), typed.strip()))
        .isEqualTo(objectMapper.readTree(maskedInvitation()));
  }

  @ParameterizedTest
  @EnumSource(
      value = Invitee.class,
      names = {
        "SUPERVISED_ACCOUNT",
        "MANAGING_ACCOUNT",
        "THE_PROFILES_OWN_ACCOUNT",
        "THE_INVITERS_OWN_ADDRESS"
      })
  @DisplayName(
      "Should name no Account when the Account with the address may not accept the invitation")
  void shouldNameNoAccountWhenTheAccountWithTheAddressMayNotAcceptTheInvitation(Invitee invitee)
      throws Exception {
    var address = addressOf(invitee);

    invite(address);

    assertThat(received(holderOf(address))).isEmpty();
  }

  @Test
  @DisplayName(
      "Should bind the invitation to the Account when the address belongs to an eligible Account"
          + " that does not manage the Profile")
  void
      shouldBindTheInvitationToTheAccountWhenTheAddressBelongsToAnEligibleAccountThatDoesNotManageTheProfile()
          throws Exception {
    var address = addressOf(Invitee.ELIGIBLE_ACCOUNT);

    var invitationId = invite(address.toUpperCase(Locale.ROOT)).at(invitationPath("id"));

    assertThat(received(holderOf(address))).containsExactly(invitationId.asString());
  }

  @ParameterizedTest
  @EnumSource(Invitee.class)
  @DisplayName(
      "Should invite in as many statements as to an address nobody has when the address is any"
          + " other")
  void shouldInviteInAsManyStatementsAsToAnAddressNobodyHasWhenTheAddressIsAnyOther(Invitee invitee)
      throws Exception {
    var forNobody =
        statementCount.statementsWhile(() -> uninvited(Invitee.NO_ACCOUNT), this::invite);
    var forAddress = statementCount.statementsWhile(() -> uninvited(invitee), this::invite);

    assertThat(forAddress).isEqualTo(forNobody);
  }

  @ParameterizedTest
  @EnumSource(Invitee.class)
  @DisplayName(
      "Should replace an invitation in as many statements as for an address nobody has when the"
          + " address is any other")
  void shouldReplaceAnInvitationInAsManyStatementsAsForAnAddressNobodyHasWhenTheAddressIsAnyOther(
      Invitee invitee) throws Exception {
    var forNobody =
        statementCount.statementsWhile(() -> invitedOnce(Invitee.NO_ACCOUNT), this::invite);
    var forAddress = statementCount.statementsWhile(() -> invitedOnce(invitee), this::invite);

    assertThat(forAddress).isEqualTo(forNobody);
  }

  @Test
  @DisplayName(
      "Should replace the pending invitation when the same address is invited again in another"
          + " case")
  void shouldReplaceThePendingInvitationWhenTheSameAddressIsInvitedAgainInAnotherCase()
      throws Exception {
    invite("mara.smith@example.com");

    var newer = invite("Mara.Smith@Example.com").at(invitationPath("id")).asString();

    assertThat(pendingForAlex().at("/data/pendingManagerInvitationsForProfile/edges"))
        .singleElement()
        .satisfies(
            edge -> {
              assertThat(edge.at("/node/id").asString()).isEqualTo(newer);
              assertThat(edge.at("/node/recipientEmail").asString())
                  .isEqualTo("Mara.Smith@Example.com");
            });
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {"'   '|EmailRequiredError", "mara.smith|EmailInvalidError"})
  @DisplayName("Should refuse the address when it is blank or not the shape of an email address")
  void shouldRefuseTheAddressWhenItIsBlankOrNotTheShapeOfAnEmailAddress(
      String address, String error) throws Exception {
    var answer = invite(address).at("/data/inviteProfileManager");

    assertThat(answer.path("issued").isNull()).isTrue();
    assertThat(answer.at("/userErrors/0/__typename").asString()).isEqualTo(error);
    assertThat(pendingForAlex().at("/data/pendingManagerInvitationsForProfile/edges")).isEmpty();
  }

  @Test
  @DisplayName(
      "Should refuse the code and the invitation when an Account takes the address after the"
          + " invitation was made")
  void shouldRefuseTheCodeAndTheInvitationWhenAnAccountTakesTheAddressAfterTheInvitationWasMade()
      throws Exception {
    var address = "late.%s@example.com".formatted(UUID.randomUUID());
    var issued = invite(address).at("/data/inviteProfileManager/issued");
    var late = track(authTestSupport.createIdentityWithEmail(address));
    var bearer = authTestSupport.accountBearer(late);

    var lookup =
        graphql(
                bearer,
                """
                mutation { lookUpManagerInvitationCode(input: {code: "%s"}) {
                  invitation { id } userErrors { __typename } } }
                """
                    .formatted(issued.at("/code").asString()))
            .at("/data/lookUpManagerInvitationCode");
    var acceptance =
        graphql(
                bearer,
                """
                mutation { acceptManagerInvitation(input: {invitationId: "%s"}) {
                  invitation { status } userErrors { __typename } } }
                """
                    .formatted(issued.at("/invitation/id").asString()))
            .at("/data/acceptManagerInvitation");

    assertThat(lookup.at("/userErrors/0/__typename").asString())
        .isEqualTo("ManagerInvitationNotFoundError");
    assertThat(acceptance.at("/userErrors/0/__typename").asString())
        .isEqualTo("ManagerInvitationNotFoundError");
    assertThat(received(late)).isEmpty();
  }

  @Test
  @DisplayName("Should wait for the invitation lifetime when a ServerAdmin has set one")
  void shouldWaitForTheInvitationLifetimeWhenAServerAdminHasSetOne() throws Exception {
    var serverAdmin = track(authTestSupport.createAdminIdentity());
    graphql(
        authTestSupport.accountBearer(serverAdmin),
        """
        mutation { changeInvitationLifetime(input: {hours: 48}) {
          userErrors { __typename } } }
        """);

    try {
      var expiresAt =
          Instant.parse(
              invite(addressOf(Invitee.NO_ACCOUNT)).at(invitationPath("expiresAt")).asString());

      assertThat(expiresAt)
          .isBetween(
              Instant.now().plus(Duration.ofHours(48)).minusSeconds(60),
              Instant.now().plus(Duration.ofHours(48)));
    } finally {
      resetServerSettings(dsl);
    }
  }

  @Test
  @DisplayName("Should let the inviter cancel it when nobody can answer the invitation")
  void shouldLetTheInviterCancelItWhenNobodyCanAnswerTheInvitation() throws Exception {
    var invitationId = invite(addressOf(Invitee.NO_ACCOUNT)).at(invitationPath("id")).asString();

    var answer =
        graphql(
                authTestSupport.accountBearer(sam),
                """
                mutation { cancelManagerInvitation(input: {invitationId: "%s"}) {
                  invitation { status } userErrors { __typename } } }
                """
                    .formatted(invitationId))
            .at("/data/cancelManagerInvitation");

    assertThat(answer.at("/invitation/status").asString()).isEqualTo("CANCELED");
    assertThat(pendingForAlex().at("/data/pendingManagerInvitationsForProfile/edges")).isEmpty();
  }

  @Test
  @DisplayName(
      "Should refuse with ManagerInvitationLimitReachedError when ten invitations wait for the"
          + " Profile")
  void shouldRefuseWithManagerInvitationLimitReachedErrorWhenTenWaitForTheProfile()
      throws Exception {
    inviteTen();

    var answer = invite(addressOf(Invitee.NO_ACCOUNT));

    assertThat(answer.at("/data/inviteProfileManager/userErrors/0/__typename").asString())
        .isEqualTo("ManagerInvitationLimitReachedError");
    assertThat(answer.at("/data/inviteProfileManager/issued").isNull()).isTrue();
    assertThat(pendingForAlex().at("/data/pendingManagerInvitationsForProfile/edges").size())
        .isEqualTo(10);
  }

  @Test
  @DisplayName(
      "Should invite again when ten wait and one has expired before the sweep stores its expiry")
  void shouldInviteAgainWhenTenWaitAndOneHasExpiredBeforeTheSweep() throws Exception {
    var first = inviteTen().getFirst();
    ManagerInvitationSteps.builder()
        .management(managerAdministration)
        .auth(authTestSupport)
        .invitations(invitationRepository)
        .transactions(transactionTemplate)
        .build()
        .lapse(invitationRepository.findById(UUID.fromString(first)).orElseThrow());

    var answer = invite(addressOf(Invitee.NO_ACCOUNT));

    assertThat(answer.at("/data/inviteProfileManager/userErrors")).isEmpty();
    assertThat(answer.at(invitationPath("status")).asString()).isEqualTo("PENDING");
  }

  @Test
  @DisplayName(
      "Should replace rather than refuse when ten wait and the same address is invited in another"
          + " case")
  void shouldReplaceRatherThanRefuseWhenTenWaitAndTheSameAddressIsInvitedInAnotherCase()
      throws Exception {
    var address = "again.%s@example.com".formatted(UUID.randomUUID());
    var replaced = invite(address).at(invitationPath("id")).asString();
    for (var i = 0; i < 9; i++) {
      invite(addressOf(Invitee.NO_ACCOUNT));
    }

    var answer = invite(address.toUpperCase(Locale.ROOT));

    assertThat(answer.at("/data/inviteProfileManager/userErrors")).isEmpty();
    var waiting =
        pendingForAlex()
            .at("/data/pendingManagerInvitationsForProfile/edges")
            .valueStream()
            .map(edge -> edge.at("/node/id").asString())
            .toList();
    assertThat(waiting)
        .hasSize(10)
        .doesNotContain(replaced)
        .contains(answer.at(invitationPath("id")).asString());
  }

  /** Ten invitations to Alex, to addresses nobody has, by their ids in the order sent. */
  private List<String> inviteTen() throws Exception {
    var ids = new ArrayList<String>();
    for (var i = 0; i < 10; i++) {
      ids.add(invite(addressOf(Invitee.NO_ACCOUNT)).at(invitationPath("id")).asString());
    }

    return ids;
  }

  private String addressOf(Invitee invitee) {
    return switch (invitee) {
      case NO_ACCOUNT -> "nobody.%s@example.com".formatted(UUID.randomUUID());
      case ELIGIBLE_ACCOUNT -> track(authTestSupport.createIdentity()).account().getEmail();
      case SUPERVISED_ACCOUNT -> track(cast.supervisedMemberOf(sam)).account().getEmail();
      case MANAGING_ACCOUNT -> {
        var manager = track(authTestSupport.createIdentity());
        cast.grant(manager, alex);
        yield manager.account().getEmail();
      }
      case THE_PROFILES_OWN_ACCOUNT -> alexsAccount.account().getEmail();
      case THE_INVITERS_OWN_ADDRESS -> sam.account().getEmail();
      case DISABLED_ACCOUNT -> {
        var disabled = track(authTestSupport.createIdentity());
        transactionTemplate.executeWithoutResult(
            _ -> userAccountRepository.tryDisable(disabled.account().getId()));
        yield disabled.account().getEmail();
      }
    };
  }

  /** An address of the invitee's kind, while no invitation is stored. */
  private String uninvited(Invitee invitee) {
    invitationRepository.deleteAll();
    return addressOf(invitee);
  }

  /** An address of the invitee's kind, while the one stored invitation asks it to manage Alex. */
  private String invitedOnce(Invitee invitee) throws Exception {
    var address = uninvited(invitee);
    assertThat(invite(address).at(invitationPath("status")).asString()).isEqualTo("PENDING");
    return address;
  }

  private TestIdentity holderOf(String address) {
    return identities.stream()
        .filter(identity -> identity.account().getEmail().equalsIgnoreCase(address))
        .findFirst()
        .orElseThrow();
  }

  private JsonNode invite(String address) throws Exception {
    return graphql(
        authTestSupport.accountBearer(sam),
        INVITE.formatted(alex.getId(), address, INVITATION_FIELDS));
  }

  private JsonNode pendingForAlex() throws Exception {
    return graphql(
        authTestSupport.accountBearer(sam),
        PENDING_FOR_PROFILE.formatted(alex.getId(), INVITATION_FIELDS));
  }

  private JsonNode readAsSam(String invitationId) throws Exception {
    return graphql(
        authTestSupport.accountBearer(sam), READ.formatted(invitationId, INVITATION_FIELDS));
  }

  private List<String> received(TestIdentity identity) throws Exception {
    return graphql(authTestSupport.accountBearer(identity), RECEIVED)
        .at("/data/pendingManagerInvitations/edges")
        .valueStream()
        .map(edge -> edge.at("/node/id").asString())
        .toList();
  }

  /** The answer with what differs between invitations replaced by placeholders, once checked. */
  private JsonNode masked(JsonNode payload, String typed) {
    var copy = (ObjectNode) payload.deepCopy();
    var issued = (ObjectNode) copy.path("issued");
    assertThat(issued.path("code").asString()).isNotBlank();
    issued.put("code", "CODE");
    issued.set("invitation", maskedInvitation(issued.path("invitation"), typed));
    return copy;
  }

  private JsonNode maskedInvitation(JsonNode invitation, String typed) {
    var copy = (ObjectNode) invitation.deepCopy();
    assertThat(copy.path("profileId").asString()).isEqualTo(alex.getId().toString());
    assertThat(copy.path("inviterAccountId").asString())
        .isEqualTo(sam.account().getId().toString());
    assertThat(copy.path("recipientEmail").asString()).isEqualTo(typed);
    assertThat(Instant.parse(copy.path("expiresAt").asString()))
        .isBetween(
            Instant.now().plus(Duration.ofDays(7)).minusSeconds(60),
            Instant.now().plus(Duration.ofDays(7)));
    copy.put("id", "ID");
    copy.put("profileId", "ALEX");
    copy.put("inviterAccountId", "SAM");
    copy.put("recipientEmail", "TYPED");
    copy.put("expiresAt", "IN_SEVEN_DAYS");
    return copy;
  }

  private static String maskedInvitation() {
    return """
        {"id": "ID", "profileId": "ALEX", "profileName": "Alex", "inviterAccountId": "SAM",
         "inviterDisplayName": "Sam", "recipientEmail": "TYPED", "status": "PENDING",
         "expiresAt": "IN_SEVEN_DAYS"}
        """;
  }

  private static String invitationPath(String field) {
    return "/data/inviteProfileManager/issued/invitation/" + field;
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
