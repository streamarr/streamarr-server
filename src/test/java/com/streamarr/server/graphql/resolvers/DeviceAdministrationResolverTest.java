package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.auth.DeviceRegistration;
import com.streamarr.server.domain.auth.EsnBlock;
import com.streamarr.server.fakes.FakeAuthorizationDecider;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.CursorValidator;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.repositories.auth.UserAccountRepository;
import com.streamarr.server.services.authorization.SecurityContextAuthorizationService;
import com.streamarr.server.services.identity.DeviceAdministrationService;
import com.streamarr.server.services.identity.DeviceRejections;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.PaginationService;
import com.streamarr.server.support.security.WithAccountContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@WithAccountContext
@SpringBootTest(
    classes = {
      DeviceAdministrationResolver.class,
      PaginationService.class,
      CursorUtil.class,
      CursorValidator.class,
      RelayConnectionAdapter.class,
      JacksonAutoConfiguration.class,
      SecurityContextAuthorizationService.class,
      FakeAuthorizationDecider.class,
      StreamarrDataFetcherExceptionHandler.class
    })
@DisplayName("Device Administration Resolver Tests")
class DeviceAdministrationResolverTest {

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private DeviceAdministrationService deviceAdministrationService;
  @MockitoBean private UserAccountRepository userAccountRepository;

  private final UUID householdId = UUID.randomUUID();

  @Test
  @DisplayName("Should apply the default page size when Household Devices omit pagination")
  void shouldApplyDefaultPageSizeWhenHouseholdDevicesOmitPagination() {
    var registrations =
        IntStream.rangeClosed(1, 101)
            .mapToObj(index -> registration("esn-%03d".formatted(index)))
            .toList();
    when(deviceAdministrationService.householdDevices(any(), eq(householdId)))
        .thenReturn(registrations);

    List<String> esns =
        dgsQueryExecutor.executeAndExtractJsonPath(
            """
            { householdDevices(householdId: "%s") {
                edges { node { esn } }
                pageInfo { hasNextPage hasPreviousPage }
              } }
            """
                .formatted(householdId),
            "data.householdDevices.edges[*].node.esn");
    Boolean hasNext =
        dgsQueryExecutor.executeAndExtractJsonPath(
            """
            { householdDevices(householdId: "%s") {
                pageInfo { hasNextPage }
              } }
            """
                .formatted(householdId),
            "data.householdDevices.pageInfo.hasNextPage");

    assertThat(esns).hasSize(100).startsWith("esn-001").endsWith("esn-100");
    assertThat(hasNext).isTrue();
  }

  @Test
  @DisplayName("Should page Household Devices forward when an after cursor is given")
  void shouldPageHouseholdDevicesForwardWhenAfterCursorGiven() {
    stubThreeRegistrations();
    String after =
        dgsQueryExecutor.executeAndExtractJsonPath(
            householdDevicesQuery("first: 1"), "data.householdDevices.edges[0].cursor");

    var query = householdDevicesQuery("first: 1, after: \"%s\"".formatted(after));
    List<String> esns =
        dgsQueryExecutor.executeAndExtractJsonPath(
            query, "data.householdDevices.edges[*].node.esn");
    Boolean hasPrevious =
        dgsQueryExecutor.executeAndExtractJsonPath(
            query, "data.householdDevices.pageInfo.hasPreviousPage");
    Boolean hasNext =
        dgsQueryExecutor.executeAndExtractJsonPath(
            query, "data.householdDevices.pageInfo.hasNextPage");

    assertThat(esns).containsExactly("esn-2");
    assertThat(hasPrevious).isTrue();
    assertThat(hasNext).isTrue();
  }

  @Test
  @DisplayName("Should use the default reverse page size when only before is given")
  void shouldUseDefaultReversePageSizeWhenOnlyBeforeGiven() {
    stubThreeRegistrations();
    String before =
        dgsQueryExecutor.executeAndExtractJsonPath(
            householdDevicesQuery("first: 3"), "data.householdDevices.edges[2].cursor");

    List<String> esns =
        dgsQueryExecutor.executeAndExtractJsonPath(
            householdDevicesQuery("before: \"%s\"".formatted(before)),
            "data.householdDevices.edges[*].node.esn");

    assertThat(esns).containsExactly("esn-1", "esn-2");
  }

  @Test
  @DisplayName("Should reject invalid Household Device pagination")
  void shouldRejectInvalidHouseholdDevicePagination() {
    when(deviceAdministrationService.householdDevices(any(), eq(householdId)))
        .thenReturn(List.of());

    var result = dgsQueryExecutor.execute(householdDevicesQuery("first: -1"));

    assertThat(result.getErrors())
        .singleElement()
        .satisfies(
            error -> assertThat(error.getExtensions()).containsEntry("code", "INVALID_INPUT"));
  }

  @Test
  @DisplayName("Should map Household and server-wide ESN block queries")
  void shouldMapHouseholdAndServerWideEsnBlockQueries() {
    var householdBlock = block("household-esn", householdId);
    var serverBlock = block("server-esn", null);
    when(deviceAdministrationService.esnBlocks(any(), eq(householdId)))
        .thenReturn(List.of(householdBlock));
    when(deviceAdministrationService.serverEsnBlocks(any())).thenReturn(List.of(serverBlock));

    String householdEsn =
        dgsQueryExecutor.executeAndExtractJsonPath(
            "{ esnBlocks(householdId: \"%s\") { edges { node { esn householdId } } } }"
                .formatted(householdId),
            "data.esnBlocks.edges[0].node.esn");
    String serverEsn =
        dgsQueryExecutor.executeAndExtractJsonPath(
            "{ serverEsnBlocks { edges { node { esn householdId } } } }",
            "data.serverEsnBlocks.edges[0].node.esn");

    assertThat(householdEsn).isEqualTo("household-esn");
    assertThat(serverEsn).isEqualTo("server-esn");
  }

  @Test
  @DisplayName("Should map accepted results from every Device mutation")
  void shouldMapAcceptedResultsFromEveryDeviceMutation() {
    var registrationId = UUID.randomUUID();
    var householdBlock = block("household-esn", householdId);
    var serverBlock = block("server-esn", null);
    when(deviceAdministrationService.revokeDeviceRegistration(any(), eq(registrationId)))
        .thenReturn(Outcome.accepted(registrationId));
    when(deviceAdministrationService.blockEsn(
            any(), eq(householdId), eq("household-esn"), eq("stolen")))
        .thenReturn(Outcome.accepted(householdBlock));
    when(deviceAdministrationService.blockEsnServerWide(any(), eq("server-esn"), eq("stolen")))
        .thenReturn(Outcome.accepted(serverBlock));
    when(deviceAdministrationService.unblockEsn(any(), eq(householdId), eq("household-esn")))
        .thenReturn(Outcome.accepted("household-esn"));
    when(deviceAdministrationService.unblockEsnServerWide(any(), eq("server-esn")))
        .thenReturn(Outcome.accepted("server-esn"));

    assertThat(mutationValue(revokeMutation(registrationId), "registrationId"))
        .isEqualTo(registrationId.toString());
    assertThat(mutationValue(blockMutation(), "block.esn")).isEqualTo("household-esn");
    assertThat(mutationValue(serverBlockMutation(), "block.esn")).isEqualTo("server-esn");
    assertThat(mutationValue(unblockMutation(), "esn")).isEqualTo("household-esn");
    assertThat(mutationValue(serverUnblockMutation(), "esn")).isEqualTo("server-esn");
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("revokeErrors")
  @DisplayName("Should map every registration revocation rejection at the mutation surface")
  void shouldMapEveryRegistrationRevocationRejectionAtMutationSurface(
      DeviceRejections.Revoke rejection, String expectedType) {
    var registrationId = UUID.randomUUID();
    when(deviceAdministrationService.revokeDeviceRegistration(any(), eq(registrationId)))
        .thenReturn(Outcome.rejected(rejection));

    assertThat(userErrorType(revokeMutation(registrationId))).isEqualTo(expectedType);
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("blockErrors")
  @DisplayName("Should map every Household block rejection at the mutation surface")
  void shouldMapEveryHouseholdBlockRejectionAtMutationSurface(
      DeviceRejections.Block rejection, String expectedType) {
    when(deviceAdministrationService.blockEsn(any(), eq(householdId), anyString(), anyString()))
        .thenReturn(Outcome.rejected(rejection));

    assertThat(userErrorType(blockMutation())).isEqualTo(expectedType);
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("serverBlockErrors")
  @DisplayName("Should map every server-wide block rejection at the mutation surface")
  void shouldMapEveryServerWideBlockRejectionAtMutationSurface(
      DeviceRejections.BlockServerWide rejection, String expectedType) {
    when(deviceAdministrationService.blockEsnServerWide(any(), anyString(), anyString()))
        .thenReturn(Outcome.rejected(rejection));

    assertThat(userErrorType(serverBlockMutation())).isEqualTo(expectedType);
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("unblockErrors")
  @DisplayName("Should map every Household unblock rejection at the mutation surface")
  void shouldMapEveryHouseholdUnblockRejectionAtMutationSurface(
      DeviceRejections.Unblock rejection, String expectedType) {
    when(deviceAdministrationService.unblockEsn(any(), eq(householdId), anyString()))
        .thenReturn(Outcome.rejected(rejection));

    assertThat(userErrorType(unblockMutation())).isEqualTo(expectedType);
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("serverUnblockErrors")
  @DisplayName("Should map every server-wide unblock rejection at the mutation surface")
  void shouldMapEveryServerWideUnblockRejectionAtMutationSurface(
      DeviceRejections.UnblockServerWide rejection, String expectedType) {
    when(deviceAdministrationService.unblockEsnServerWide(any(), anyString()))
        .thenReturn(Outcome.rejected(rejection));

    assertThat(userErrorType(serverUnblockMutation())).isEqualTo(expectedType);
  }

  private void stubThreeRegistrations() {
    when(deviceAdministrationService.householdDevices(any(), eq(householdId)))
        .thenReturn(List.of(registration("esn-1"), registration("esn-2"), registration("esn-3")));
  }

  private DeviceRegistration registration(String esn) {
    var registration =
        DeviceRegistration.builder()
            .esn(esn)
            .displayName(esn)
            .householdId(householdId)
            .authorizingAccountId(UUID.randomUUID())
            .build();
    registration.setId(UUID.randomUUID());
    AuditFieldSetter.setCreatedOn(registration, Instant.parse("2026-08-21T00:00:00Z"));
    return registration;
  }

  private static EsnBlock block(String esn, UUID householdId) {
    var block = EsnBlock.builder().esn(esn).householdId(householdId).reason("stolen").build();
    block.setId(UUID.randomUUID());
    return block;
  }

  private String householdDevicesQuery(String arguments) {
    return """
        { householdDevices(householdId: "%s", %s) {
            edges { cursor node { esn } }
            pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
          } }
        """
        .formatted(householdId, arguments);
  }

  private Object mutationValue(String mutation, String path) {
    var field = mutation.substring("mutation { ".length(), mutation.indexOf('('));
    return dgsQueryExecutor.executeAndExtractJsonPath(
        mutation, "data.%s.%s".formatted(field, path));
  }

  private String userErrorType(String mutation) {
    var field = mutation.substring("mutation { ".length(), mutation.indexOf('('));
    return dgsQueryExecutor.executeAndExtractJsonPath(
        mutation, "data.%s.userErrors[0].__typename".formatted(field));
  }

  private static String revokeMutation(UUID registrationId) {
    return "mutation { revokeDeviceRegistration(input: {registrationId: \"%s\"}) { registrationId userErrors { __typename } } }"
        .formatted(registrationId);
  }

  private String blockMutation() {
    return "mutation { blockEsn(input: {householdId: \"%s\", esn: \"household-esn\", reason: \"stolen\"}) { block { esn } userErrors { __typename } } }"
        .formatted(householdId);
  }

  private static String serverBlockMutation() {
    return "mutation { blockEsnServerWide(input: {esn: \"server-esn\", reason: \"stolen\"}) { block { esn } userErrors { __typename } } }";
  }

  private String unblockMutation() {
    return "mutation { unblockEsn(input: {householdId: \"%s\", esn: \"household-esn\"}) { esn userErrors { __typename } } }"
        .formatted(householdId);
  }

  private static String serverUnblockMutation() {
    return "mutation { unblockEsnServerWide(input: {esn: \"server-esn\"}) { esn userErrors { __typename } } }";
  }

  private static Stream<Arguments> revokeErrors() {
    return Stream.of(
        Arguments.of(new DeviceRejections.RegistrationNotFound(), "RegistrationNotFoundError"),
        Arguments.of(new DeviceRejections.RegistrationNotActive(), "RegistrationNotActiveError"));
  }

  private static Stream<Arguments> blockErrors() {
    return Stream.of(
        Arguments.of(new DeviceRejections.HouseholdNotFound(), "HouseholdNotFoundError"),
        Arguments.of(new DeviceRejections.EsnRequired(), "EsnRequiredError"),
        Arguments.of(new DeviceRejections.ReasonRequired(), "ReasonRequiredError"),
        Arguments.of(new DeviceRejections.AlreadyBlocked(), "EsnAlreadyBlockedError"));
  }

  private static Stream<Arguments> serverBlockErrors() {
    return Stream.of(
        Arguments.of(new DeviceRejections.EsnRequired(), "EsnRequiredError"),
        Arguments.of(new DeviceRejections.ReasonRequired(), "ReasonRequiredError"),
        Arguments.of(new DeviceRejections.AlreadyBlocked(), "EsnAlreadyBlockedError"),
        Arguments.of(
            new DeviceRejections.ReauthenticationRequired(), "ReauthenticationRequiredError"));
  }

  private static Stream<Arguments> unblockErrors() {
    return Stream.of(
        Arguments.of(new DeviceRejections.HouseholdNotFound(), "HouseholdNotFoundError"),
        Arguments.of(new DeviceRejections.EsnRequired(), "EsnRequiredError"),
        Arguments.of(new DeviceRejections.BlockNotFound(), "EsnBlockNotFoundError"));
  }

  private static Stream<Arguments> serverUnblockErrors() {
    return Stream.of(
        Arguments.of(new DeviceRejections.EsnRequired(), "EsnRequiredError"),
        Arguments.of(new DeviceRejections.BlockNotFound(), "EsnBlockNotFoundError"));
  }
}
