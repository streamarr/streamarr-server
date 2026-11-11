package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.ProfileHouseholdShare;
import com.streamarr.server.domain.auth.ProfileShareStatus;
import com.streamarr.server.repositories.auth.DeviceRegistrationRepository;
import com.streamarr.server.repositories.auth.ProfileHouseholdShareRepository;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.support.AuthTestSupport;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** What a Household's Device registrations say about the people who linked them. */
@Tag("IntegrationTest")
@DisplayName("Household Devices Endpoints Integration Tests")
class HouseholdDevicesEndpointsIT extends AbstractIntegrationTest {

  private static final int COUNTED_RUNS = 3;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private DeviceRegistrationRepository registrationRepository;
  @Autowired private ProfileHouseholdShareRepository shareRepository;
  @Autowired private UserAccountRepository accountRepository;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private EntityManagerFactory entityManagerFactory;

  private final List<AuthTestSupport.TestIdentity> identities = new ArrayList<>();
  private AuthTestSupport.TestIdentity host;

  @BeforeEach
  void setUp() {
    host = identity();
  }

  @AfterEach
  void tearDown() {
    identities.reversed().forEach(authTestSupport::deleteIdentity);
  }

  @Test
  @DisplayName("Should name who linked each Device when a HouseholdAdmin lists the Household's")
  void shouldNameWhoLinkedEachDeviceWhenHouseholdAdminListsHouseholds() throws Exception {
    var hostsTv = register(host);
    var visitor = visitor();
    var visitorsTv = register(visitor);

    var names = displayNamesByRegistration(host);

    assertThat(names)
        .containsEntry(hostsTv.toString(), host.account().getDisplayName())
        .containsEntry(visitorsTv.toString(), visitor.account().getDisplayName());
  }

  @Test
  @DisplayName("Should name who linked each Device when a ServerAdmin lists a Household's")
  void shouldNameWhoLinkedEachDeviceWhenServerAdminListsHouseholds() throws Exception {
    var hostsTv = register(host);
    var serverAdmin = authTestSupport.createAdminIdentity();
    identities.add(serverAdmin);

    assertThat(displayNamesByRegistration(serverAdmin))
        .containsEntry(hostsTv.toString(), host.account().getDisplayName());
  }

  @Test
  @DisplayName("Should list only the Devices visitors linked when the list is kept to visitors")
  void shouldListOnlyDevicesVisitorsLinkedWhenListIsKeptToVisitors() throws Exception {
    register(host);
    register(authTestSupport.createHouseholdMemberIdentity(host));
    var visitor = visitor();
    var visitorsTv = register(visitor);

    var visitors = edges(householdDevices(host, ", visitorsOnly: true"));
    var every = edges(householdDevices(host, ", visitorsOnly: false"));

    assertThat(visitors)
        .singleElement()
        .satisfies(
            node -> {
              assertThat(node.path("id").asString()).isEqualTo(visitorsTv.toString());
              assertThat(node.path("authorizingAccountDisplayName").asString())
                  .isEqualTo(visitor.account().getDisplayName());
            });
    assertThat(every).hasSize(3);
  }

  @Test
  @DisplayName("Should hide the Devices and who linked them when a member lists them")
  void shouldHideDevicesAndWhoLinkedThemWhenMemberListsThem() throws Exception {
    register(host);
    var member = authTestSupport.createHouseholdMemberIdentity(host);

    assertThat(edges(householdDevices(member, ""))).isEmpty();
  }

  @Test
  @DisplayName(
      "Should read who linked each Device in the same number of statements when more linked them")
  void shouldReadWhoLinkedEachDeviceInTheSameNumberOfStatementsWhenMoreLinkedThem()
      throws Exception {
    register(host);
    register(visitor());
    // The first request fills caches that later requests skip.
    householdDevices(host, "");
    var forTwoDevices = statementsWhileListing(host);

    register(visitor());
    register(authTestSupport.createHouseholdMemberIdentity(host));

    assertThat(displayNamesByRegistration(host)).hasSize(4);
    assertThat(statementsWhileListing(host)).isEqualTo(forTwoDevices);
  }

  private Map<String, String> displayNamesByRegistration(AuthTestSupport.TestIdentity reader)
      throws Exception {
    var names = new HashMap<String, String>();
    edges(householdDevices(reader, ""))
        .forEach(
            node ->
                names.put(
                    node.path("id").asString(),
                    node.path("authorizingAccountDisplayName").asString(null)));
    return names;
  }

  private JsonNode householdDevices(AuthTestSupport.TestIdentity reader, String arguments)
      throws Exception {
    var query =
        """
        query { householdDevices(householdId: "%s"%s) {
          edges { node { id authorizingAccountId authorizingAccountDisplayName } } } }
        """
            .formatted(host.household().getId(), arguments);
    var response =
        mockMvc
            .perform(
                post("/graphql")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + authTestSupport.accountBearer(reader))
                    .content(objectMapper.writeValueAsString(Map.of("query", query))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.errors").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).path("data").path("householdDevices");
  }

  private static List<JsonNode> edges(JsonNode connection) {
    var nodes = new ArrayList<JsonNode>();
    connection.path("edges").forEach(edge -> nodes.add(edge.path("node")));
    return nodes;
  }

  /**
   * An Account of another Household, named apart from the host, whose Personal Profile is available
   * in the host's.
   */
  private AuthTestSupport.TestIdentity visitor() {
    var visitor = identity();
    var name = "Visitor " + visitor.account().getId();
    transactionTemplate.executeWithoutResult(
        _ -> accountRepository.tryRename(visitor.account().getId(), name));
    visitor.account().setDisplayName(name);
    shareRepository.saveAndFlush(
        ProfileHouseholdShare.builder()
            .profileId(visitor.profile().getId())
            .householdId(host.household().getId())
            .status(ProfileShareStatus.ACTIVE)
            .build());
    return visitor;
  }

  /** A TV in the host's Household, linked by the given Account. */
  private UUID register(AuthTestSupport.TestIdentity linker) {
    return transactionTemplate
        .execute(
            _ ->
                registrationRepository.saveAndFlush(
                    DeviceRegistration.builder()
                        .esn("tv-" + UUID.randomUUID())
                        .displayName("Den TV")
                        .householdId(host.household().getId())
                        .authorizingAccountId(linker.account().getId())
                        .build()))
        .getId();
  }

  private AuthTestSupport.TestIdentity identity() {
    var identity = authTestSupport.createIdentity();
    identities.add(identity);
    return identity;
  }

  // Hibernate statistics are global, so the count holds only while no other test uses the context.
  // The session reaper also reads the server settings every few seconds on its own thread, so a
  // run can count one statement that is not the request's; the fewest over a few runs is its own.
  private long statementsWhileListing(AuthTestSupport.TestIdentity reader) throws Exception {
    var fewest = Long.MAX_VALUE;
    for (var run = 0; run < COUNTED_RUNS; run++) {
      fewest = Math.min(fewest, statementsOfOneListing(reader));
    }

    return fewest;
  }

  private long statementsOfOneListing(AuthTestSupport.TestIdentity reader) throws Exception {
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    var previouslyEnabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    statistics.clear();

    try {
      householdDevices(reader, "");
      return statistics.getPrepareStatementCount();
    } finally {
      statistics.clear();
      statistics.setStatisticsEnabled(previouslyEnabled);
    }
  }
}
