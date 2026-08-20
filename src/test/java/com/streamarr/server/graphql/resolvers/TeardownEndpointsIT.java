package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.jooq.generated.tables.SecurityAuditEvent.SECURITY_AUDIT_EVENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.DeviceRegistrationStatus;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileManager;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.fixtures.ProfileFixture;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.repositories.auth.HouseholdRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.ProfileManagerRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import java.util.Map;
import java.util.UUID;
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
 * Household teardown and the audit surfaces against real PostgreSQL and Cedar: the final Account
 * leaves by its chosen disposition, nothing outlives the Household, and only ServerAdmin reads the
 * audit trail.
 */
@Tag("IntegrationTest")
@DisplayName("Teardown Endpoints Integration Tests")
class TeardownEndpointsIT extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private HouseholdRepository householdRepository;
  @Autowired private UserAccountRepository userAccountRepository;
  @Autowired private ProfileRepository profileRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private ProfileManagerRepository profileManagerRepository;
  @Autowired private DeviceRegistrationRepository registrationRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private DSLContext dsl;

  private AuthTestSupport.TestIdentity admin;
  private AuthTestSupport.TestIdentity doomed;

  @BeforeEach
  void setUp() {
    admin = authTestSupport.createAdminIdentity();
    doomed = authTestSupport.createIdentity();
  }

  @AfterEach
  void tearDown() {
    dsl.deleteFrom(SECURITY_AUDIT_EVENT).execute();
    registrationRepository.deleteAll();
    if (householdRepository.findById(doomed.household().getId()).isPresent()) {
      authTestSupport.deleteIdentity(doomed);
    }
    authTestSupport.deleteIdentity(admin);
  }

  @Test
  @DisplayName("Should tear the Household down leaving nothing stranded")
  void shouldTearHouseholdDownLeavingNothingStranded() throws Exception {
    // A resident orphan Profile, a hosted visit, and a registered TV all fall with it.
    var state =
        transactionTemplate.execute(
            _ -> {
              var orphan =
                  profileRepository.saveAndFlush(
                      ProfileFixture.defaultProfileBuilder()
                          .householdId(doomed.household().getId())
                          .name("Orphan")
                          .build());
              profileManagerRepository.saveAndFlush(
                  ProfileManager.builder()
                      .accountId(doomed.account().getId())
                      .profileId(orphan.getId())
                      .build());
              var visit =
                  shareRepository.saveAndFlush(
                      ProfileHouseholdShare.builder()
                          .profileId(admin.account().getPersonalProfileId())
                          .householdId(doomed.household().getId())
                          .status(ProfileShareStatus.ACTIVE)
                          .build());
              var registration =
                  registrationRepository.saveAndFlush(
                      DeviceRegistration.builder()
                          .esn("esn-doomed")
                          .displayName("TV")
                          .householdId(doomed.household().getId())
                          .authorizingAccountId(admin.account().getId())
                          .build());
              return new Object[] {orphan.getId(), visit.getId(), registration.getId()};
            });

    // The preflight names what teardown will take with it.
    graphql(
            authTestSupport.accountBearer(admin),
            """
            query { teardownPreflight(householdId: "%s") {
              accountCount unlinkedProfiles { name } hostedVisitCount } }
            """
                .formatted(doomed.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.teardownPreflight.accountCount").value(1))
        .andExpect(jsonPath("$.data.teardownPreflight.unlinkedProfiles[0].name").value("Orphan"));

    // A stale ceremony earns the typed answer; the fresh one with a disposition tears down.
    graphql(
            authTestSupport.accountBearer(admin),
            tearDownMutation(doomed.household().getId(), admin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.tearDownHousehold.userErrors[0].__typename")
                .value("ReauthenticationRequiredError"));
    graphql(
            authTestSupport.freshAccountBearer(admin),
            tearDownMutation(doomed.household().getId(), admin.household().getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.tearDownHousehold.userErrors").isEmpty());

    assertThat(householdRepository.findById(doomed.household().getId())).isEmpty();
    assertThat(profileRepository.findById((UUID) state[0])).isEmpty();
    // The visit ended in the transaction, then fell with the Household row itself.
    assertThat(shareRepository.findById((UUID) state[1])).isEmpty();
    assertThat(registrationRepository.findById((UUID) state[2]).orElseThrow().getStatus())
        .isEqualTo(DeviceRegistrationStatus.REVOKED);
    // The final Account arrived in the destination with its Personal Profile.
    var moved = userAccountRepository.findById(doomed.account().getId()).orElseThrow();
    assertThat(moved.getHouseholdId()).isEqualTo(admin.household().getId());

    // Only ServerAdmin reads the audit; the row is there, newest first, and the keyset cursor
    // resumes strictly after the page already seen.
    var firstPage =
        graphql(
                authTestSupport.accountBearer(admin),
                "query { securityAuditEvents(first: 1) { edges { cursor node { operation reason"
                    + " outcome occurredAt resources actorAccountId id } } pageInfo { hasNextPage"
                    + " } } }")
            .andExpect(status().isOk())
            .andExpect(
                jsonPath("$.data.securityAuditEvents.edges[0].node.operation")
                    .value("tearDownHousehold"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var endCursor =
        objectMapper
            .readTree(firstPage)
            .path("data")
            .path("securityAuditEvents")
            .path("edges")
            .get(0)
            .path("cursor")
            .asString();
    graphql(
            authTestSupport.accountBearer(admin),
            """
            query { securityAuditEvents(first: 5, after: "%s") {
              edges { node { operation } } } }
            """
                .formatted(endCursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(
            jsonPath("$.data.securityAuditEvents.edges[*].node.operation")
                .value(not(hasItem("tearDownHousehold"))));
    graphql(
            authTestSupport.accountBearer(doomed),
            "query { securityAuditEvents(first: 10) { edges { node { operation } } } }")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors[0].extensions.code").value("FORBIDDEN"));

    // Activity reads scope by visibility: the hidden Profile reads as empty.
    graphql(
            authTestSupport.accountBearer(admin),
            """
            query { profileActivity(profileId: "%s") { edges { node { id } } } }
            """
                .formatted(admin.account().getPersonalProfileId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.errors").doesNotExist())
        .andExpect(jsonPath("$.data.profileActivity.edges").isEmpty());
  }

  private String tearDownMutation(UUID householdId, UUID destination) {
    return """
           mutation { tearDownHousehold(input: {householdId: "%s", reason: "closing shop",
             finalAccount: {choice: TRANSFER, destinationHouseholdId: "%s"}}) {
             householdId userErrors { __typename } } }
           """
        .formatted(householdId, destination);
  }

  private ResultActions graphql(String bearer, String query) throws Exception {
    return mockMvc.perform(
        post("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .content(objectMapper.writeValueAsString(Map.of("query", query))));
  }
}
