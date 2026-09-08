package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.domain.AuditFieldSetter;
import com.streamarr.server.domain.auth.SecurityAuditEventRecordView;
import com.streamarr.server.domain.streaming.SessionProgress;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.services.auth.AuthenticatedIdentity;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.HouseholdDeletionRejections;
import com.streamarr.server.services.identity.HouseholdDeletionService;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteEmptyHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand;
import com.streamarr.server.services.identity.HouseholdDeletionService.DoomedProfileDetails;
import com.streamarr.server.services.identity.HouseholdDeletionService.HouseholdDeletionPreflightDetails;
import com.streamarr.server.services.identity.HouseholdDeletionService.SecurityAuditPageRequest;
import com.streamarr.server.services.identity.HouseholdDeletionService.TransferLastAccountAndDeleteHouseholdCommand;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.KeysetPaginationOptions;
import com.streamarr.server.services.pagination.MediaPage;
import com.streamarr.server.services.pagination.PageItem;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.PaginationOptions;
import com.streamarr.server.services.pagination.PaginationService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.OngoingStubbing;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(
    classes = {
      HouseholdDeletionResolver.class,
      JacksonAutoConfiguration.class,
      StreamarrDataFetcherExceptionHandler.class,
      CursorUtil.class,
      RelayConnectionAdapter.class,
      PaginationService.class
    })
@DisplayName("Household Deletion Resolver Tests")
class HouseholdDeletionResolverTest {
  private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID DESTINATION = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID MANAGER = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final String REASON = "closing reviewed Household";
  @Autowired private DgsQueryExecutor executor;
  @Autowired private ObjectMapper mapper;
  @MockitoBean private HouseholdDeletionService service;
  @MockitoBean private AuthorizationService authorization;
  private final AuthenticatedIdentity identity =
      AuthenticatedIdentityFixture.accountScopedBuilder().build();

  @BeforeEach
  void setUp() {
    when(authorization.currentIdentity()).thenReturn(identity);
  }

  @ParameterizedTest
  @EnumSource(Action.class)
  @DisplayName("Should return the exact deletion receipt without errors when the action succeeds")
  void shouldReturnExactDeletionReceiptWithoutErrorsWhenActionSucceeds(Action action) {
    stubOutcome(action, Outcome.accepted(SOURCE));

    var response = mutate(action, Map.of());

    assertThat(response.has("errors")).isFalse();
    assertThat(response.at("/data/" + action.operation + "/deletedHouseholdId").asString())
        .isEqualTo(SOURCE.toString());
    assertThat(response.at("/data/" + action.operation + "/userErrors").isArray()).isTrue();
    assertThat(response.at("/data/" + action.operation + "/userErrors").size()).isZero();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejections")
  @DisplayName(
      "Should return the declared error and corrective path when a deletion action is rejected")
  void shouldReturnDeclaredErrorAndCorrectivePathWhenDeletionActionIsRejected(
      RejectionCase rejectionCase) {
    stubOutcome(rejectionCase.action(), Outcome.rejected(rejectionCase.rejection()));

    var response = mutate(rejectionCase.action(), Map.of());

    assertThat(response.has("errors")).isFalse();
    var payload = response.at("/data/" + rejectionCase.action().operation);
    assertThat(payload.path("deletedHouseholdId").isNull()).isTrue();
    assertThat(payload.path("userErrors").size()).isEqualTo(1);
    var error = payload.at("/userErrors/0");
    assertThat(error.path("__typename").asString()).isEqualTo(rejectionCase.type());
    if (rejectionCase.path().isEmpty()) {
      assertThat(error.has("inputPath")).isFalse();
      return;
    }

    assertThat(error.path("inputPath")).isEqualTo(mapper.valueToTree(rejectionCase.path()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidIds")
  @DisplayName("Should identify the malformed input when a deletion ID is invalid")
  void shouldIdentifyMalformedInputWhenDeletionIdIsInvalid(InvalidIdCase invalid) {
    stubOutcome(invalid.action(), Outcome.accepted(SOURCE));
    var response = mutate(invalid.action(), Map.of(invalid.field(), "not-a-uuid"));
    assertThat(response.has("errors")).isFalse();
    var payload = response.at("/data/" + invalid.action().operation);
    assertThat(payload.path("deletedHouseholdId").isNull()).isTrue();
    assertThat(payload.path("userErrors").size()).isEqualTo(1);
    assertThat(payload.at("/userErrors/0/__typename").asString()).isEqualTo("InvalidIdError");
    assertThat(payload.at("/userErrors/0/inputPath"))
        .isEqualTo(mapper.valueToTree(List.of(invalid.field())));
  }

  @ParameterizedTest
  @EnumSource(Action.class)
  @DisplayName("Should sanitize the top-level error when a deletion action fails unexpectedly")
  void shouldSanitizeTopLevelErrorWhenDeletionActionFailsUnexpectedly(Action action) {
    stubbingFor(action).thenThrow(new IllegalStateException("private failure detail"));
    assertSanitizedFailure(mutate(action, Map.of()), action);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("undeclaredRejections")
  @DisplayName(
      "Should reject an undeclared union member when the service returns an incompatible rejection")
  void shouldRejectUndeclaredUnionMemberWhenServiceReturnsIncompatibleRejection(
      UnexpectedRejection unexpected) {
    stubOutcome(unexpected.action(), Outcome.rejected(unexpected.rejection()));
    assertSanitizedFailure(mutate(unexpected.action(), Map.of()), unexpected.action());
  }

  private void assertSanitizedFailure(JsonNode response, Action action) {
    assertThat(response.at("/data/" + action.operation).isNull()).isTrue();
    assertThat(response.path("errors").size()).isEqualTo(1);
    var error = response.at("/errors/0");
    assertThat(error.path("message").asString()).isEqualTo("The request could not be completed.");
    assertThat(error.path("path")).isEqualTo(mapper.valueToTree(List.of(action.operation)));
    assertThat(error.path("extensions").size())
        .as("extensions: %s", error.path("extensions"))
        .isEqualTo(3);
    assertThat(error.at("/extensions/errorType").asString()).isEqualTo("INTERNAL");
    assertThat(error.at("/extensions/code").asString()).isEqualTo("INTERNAL");
    assertThat(error.at("/extensions/requestId").asString()).matches("req-[0-9a-f]{8}");
    assertThat(response.toString())
        .doesNotContain("private failure detail", "IllegalStateException");
  }

  private static Stream<InvalidIdCase> invalidIds() {
    return Stream.concat(
        Stream.of(Action.values()).map(action -> new InvalidIdCase(action, "householdId")),
        Stream.of(
            new InvalidIdCase(Action.TRANSFER, "destinationHouseholdId"),
            new InvalidIdCase(Action.PRESERVE, "destinationHouseholdId"),
            new InvalidIdCase(Action.PRESERVE, "replacementManagerAccountId")));
  }

  private static Stream<UnexpectedRejection> undeclaredRejections() {
    return Stream.of(
        new UnexpectedRejection(
            Action.EMPTY, new HouseholdDeletionRejections.DestinationNotFound()),
        new UnexpectedRejection(Action.TRANSFER, new HouseholdDeletionRejections.LastServerAdmin()),
        new UnexpectedRejection(
            Action.DELETE, new HouseholdDeletionRejections.DestinationNotFound()));
  }

  private record InvalidIdCase(Action action, String field) {}

  private record UnexpectedRejection(Action action, HouseholdDeletionRejections.Delete rejection) {}

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("Should project the visible preview when the service resolves Household visibility")
  void shouldProjectVisiblePreviewWhenServiceResolvesHouseholdVisibility(boolean visible) {
    var preview =
        new HouseholdDeletionPreflightDetails(
            3, List.of(new DoomedProfileDetails(DESTINATION, "Unlinked")), 2);
    when(service.deletionPreflight(identity, SOURCE))
        .thenReturn(visible ? Optional.of(preview) : Optional.empty());
    var response =
        query(
            """
        query { householdDeletionPreview(householdId: "%s") {
          accountCount profilesToDelete { id name } visitingProfileCount
        } }
        """
                .formatted(SOURCE));
    assertThat(response.has("errors")).isFalse();
    var node = response.at("/data/householdDeletionPreview");
    if (!visible) {
      assertThat(node.isNull()).isTrue();
      return;
    }

    assertThat(node)
        .isEqualTo(
            mapper.valueToTree(
                Map.of(
                    "accountCount",
                    3,
                    "profilesToDelete",
                    List.of(Map.of("id", DESTINATION.toString(), "name", "Unlinked")),
                    "visitingProfileCount",
                    2)));
  }

  @Test
  @DisplayName(
      "Should project every activity field and page cursor when the service returns progress")
  void shouldProjectEveryActivityFieldAndPageCursorWhenServiceReturnsProgress() {
    var watchedAt = Instant.parse("2026-08-01T12:00:00Z");
    var progress =
        SessionProgress.builder()
            .id(DESTINATION)
            .profileId(SOURCE)
            .mediaFileId(MANAGER)
            .sessionId(UUID.randomUUID())
            .positionSeconds(123)
            .percentComplete(20.5)
            .durationSeconds(600)
            .build();
    AuditFieldSetter.setLastModifiedOn(progress, watchedAt);
    when(service.profileActivity(identity, SOURCE, activityOptions()))
        .thenReturn(new MediaPage<>(List.of(new PageItem<>(progress, watchedAt)), true, false));

    var response =
        query(
            """
        query { profileActivity(profileId: "%s", first: 2) {
          edges { cursor node { id mediaFileId positionSeconds percentComplete durationSeconds watchedAt } }
          pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
        } }
        """
                .formatted(SOURCE));

    assertThat(response.has("errors")).isFalse();
    assertThat(response.at("/data/profileActivity/edges").size()).isEqualTo(1);
    assertThat(response.at("/data/profileActivity/edges/0/node"))
        .isEqualTo(
            mapper.valueToTree(
                Map.of(
                    "id",
                    DESTINATION.toString(),
                    "mediaFileId",
                    MANAGER.toString(),
                    "positionSeconds",
                    123,
                    "percentComplete",
                    20.5,
                    "durationSeconds",
                    600,
                    "watchedAt",
                    "2026-08-01T12:00:00Z")));
    assertThat(response.at("/data/profileActivity/edges/0/cursor").asString())
        .isEqualTo(opaque(DESTINATION.toString()));
    assertThat(response.at("/data/profileActivity/pageInfo"))
        .isEqualTo(
            mapper.valueToTree(
                Map.of(
                    "hasNextPage",
                    true,
                    "hasPreviousPage",
                    false,
                    "startCursor",
                    opaque(DESTINATION.toString()),
                    "endCursor",
                    opaque(DESTINATION.toString()))));
  }

  @Test
  @DisplayName("Should return an empty activity connection when the service hides the Profile")
  void shouldReturnEmptyActivityConnectionWhenServiceHidesProfile() {
    when(service.profileActivity(identity, SOURCE, activityOptions()))
        .thenReturn(new MediaPage<>(List.of(), false, false));
    var response =
        query(
            """
        query { profileActivity(profileId: "%s", first: 2) {
          edges { node { id } } pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
        } }
        """
                .formatted(SOURCE));
    assertThat(response.has("errors")).isFalse();
    assertThat(response.at("/data/profileActivity/edges").size()).isZero();
    assertThat(response.at("/data/profileActivity/pageInfo/hasNextPage").asBoolean()).isFalse();
    assertThat(response.at("/data/profileActivity/pageInfo/hasPreviousPage").asBoolean()).isFalse();
    assertThat(response.at("/data/profileActivity/pageInfo/startCursor").isNull()).isTrue();
    assertThat(response.at("/data/profileActivity/pageInfo/endCursor").isNull()).isTrue();
  }

  @Test
  @DisplayName(
      "Should decode the reverse audit cursor and project its row when the service returns a page")
  void shouldDecodeReverseAuditCursorAndProjectRowWhenServiceReturnsPage() {
    var occurredAt = Instant.parse("2026-08-01T12:00:00Z");
    var row =
        mapper.convertValue(
            Map.of(
                "id",
                MANAGER,
                "occurredAt",
                occurredAt.plusSeconds(1),
                "actorAccountId",
                SOURCE,
                "operation",
                "deleteEmptyHousehold",
                "outcome",
                "SUCCESS",
                "reason",
                "closing",
                "resources",
                "{\"householdId\":\"example\"}"),
            SecurityAuditEventRecordView.class);
    when(service.securityAuditEvents(
            identity,
            SecurityAuditPageRequest.builder()
                .direction(PaginationDirection.REVERSE)
                .cursorId(DESTINATION)
                .cursorOccurredAt(occurredAt)
                .limit(2)
                .build()))
        .thenReturn(new MediaPage<>(List.of(new PageItem<>(row, row.occurredAt())), true, false));

    var response =
        query(
            """
        query { securityAuditEvents(last: 2, before: "%s") {
          edges { cursor node { id occurredAt actorAccountId operation outcome reason resources } }
          pageInfo { hasNextPage hasPreviousPage }
        } }
        """
                .formatted(opaque(occurredAt + "|" + DESTINATION)));

    assertThat(response.has("errors")).isFalse();
    assertThat(response.at("/data/securityAuditEvents/edges").size()).isEqualTo(1);
    assertThat(response.at("/data/securityAuditEvents/edges/0/node"))
        .isEqualTo(
            mapper.valueToTree(
                Map.of(
                    "id",
                    MANAGER.toString(),
                    "occurredAt",
                    "2026-08-01T12:00:01Z",
                    "actorAccountId",
                    SOURCE.toString(),
                    "operation",
                    "deleteEmptyHousehold",
                    "outcome",
                    "SUCCESS",
                    "reason",
                    "closing",
                    "resources",
                    "{\"householdId\":\"example\"}")));
    assertThat(response.at("/data/securityAuditEvents/edges/0/cursor").asString())
        .isEqualTo(opaque("2026-08-01T12:00:01Z|" + MANAGER));
    assertThat(response.at("/data/securityAuditEvents/pageInfo"))
        .isEqualTo(mapper.valueToTree(Map.of("hasNextPage", true, "hasPreviousPage", false)));
  }

  @ParameterizedTest
  @MethodSource("invalidAuditCursors")
  @DisplayName("Should return invalid cursor when the audit cursor cannot identify a time and ID")
  void shouldReturnInvalidCursorWhenAuditCursorCannotIdentifyTimeAndId(String cursor) {
    var response =
        query(
            """
        query { securityAuditEvents(first: 2, after: "%s") { edges { node { id } } } }
        """
                .formatted(cursor));
    assertThat(response.path("errors").size()).isEqualTo(1);
    assertThat(response.at("/errors/0/extensions/code").asString()).isEqualTo("INVALID_CURSOR");
    assertThat(response.at("/errors/0/extensions/errorType").asString()).isEqualTo("BAD_REQUEST");
    assertThat(response.at("/errors/0/path"))
        .isEqualTo(mapper.valueToTree(List.of("securityAuditEvents")));
  }

  @Test
  @DisplayName("Should expose a forbidden top-level error when audit access is denied")
  void shouldExposeForbiddenTopLevelErrorWhenAuditAccessIsDenied() {
    when(service.securityAuditEvents(
            identity,
            SecurityAuditPageRequest.builder()
                .direction(PaginationDirection.FORWARD)
                .limit(2)
                .build()))
        .thenThrow(new AccessDeniedException("Access denied."));
    var response = query("query { securityAuditEvents(first: 2) { edges { node { id } } } }");
    assertThat(response.path("data").isNull()).isTrue();
    assertThat(response.path("errors").size()).isEqualTo(1);
    assertThat(response.at("/errors/0/extensions/code").asString()).isEqualTo("FORBIDDEN");
    assertThat(response.at("/errors/0/extensions/errorType").asString())
        .isEqualTo("PERMISSION_DENIED");
    assertThat(response.at("/errors/0/extensions/requestId").asString()).matches("req-[0-9a-f]{8}");
    assertThat(response.at("/errors/0/extensions").size()).isEqualTo(3);
  }

  private static Stream<String> invalidAuditCursors() {
    return Stream.of(
        "%%%",
        opaque("missing separator"),
        opaque("not-a-time|" + SOURCE),
        opaque("2026-08-01T12:00:00Z|not-an-id"));
  }

  private static KeysetPaginationOptions activityOptions() {
    return new KeysetPaginationOptions(
        null,
        PaginationOptions.builder()
            .paginationDirection(PaginationDirection.FORWARD)
            .cursor(Optional.empty())
            .limit(2)
            .build());
  }

  private JsonNode query(String document) {
    return mapper.valueToTree(executor.execute(document).toSpecification());
  }

  private static String opaque(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static Stream<RejectionCase> rejections() {
    return Stream.of(Action.values())
        .flatMap(action -> Stream.concat(commonRejections(action), actionRejections(action)));
  }

  private static Stream<RejectionCase> commonRejections(Action action) {
    return Stream.of(
        rejection(action, new HouseholdDeletionRejections.HouseholdNotFound())
            .type("HouseholdNotFoundError")
            .path(List.of("householdId"))
            .build(),
        rejection(action, new HouseholdDeletionRejections.ReasonRequired())
            .type("ReasonRequiredError")
            .path(List.of("reason"))
            .build(),
        rejection(action, new HouseholdDeletionRejections.ReauthenticationRequired())
            .type("ReauthenticationRequiredError")
            .build(),
        rejection(action, new HouseholdDeletionRejections.AccountsRemain())
            .type("AccountsRemainError")
            .build());
  }

  private static Stream<RejectionCase> actionRejections(Action action) {
    var lastAccount =
        rejection(action, new HouseholdDeletionRejections.LastAccountNotFound())
            .type("LastAccountNotFoundError")
            .build();
    var destination =
        rejection(action, new HouseholdDeletionRejections.DestinationNotFound())
            .type("DestinationNotFoundError")
            .path(List.of("destinationHouseholdId"))
            .build();
    var conflict =
        rejection(action, new HouseholdDeletionRejections.NameConflict())
            .type("ProfileNameTakenError")
            .path(List.of("destinationHouseholdId"))
            .build();
    var lastAdmin =
        rejection(action, new HouseholdDeletionRejections.LastServerAdmin())
            .type("LastServerAdminError")
            .build();
    return switch (action) {
      case EMPTY -> Stream.empty();
      case TRANSFER -> Stream.of(lastAccount, destination, conflict);
      case DELETE -> Stream.of(lastAccount, lastAdmin);
      case PRESERVE ->
          Stream.of(
              lastAccount,
              destination,
              conflict,
              lastAdmin,
              rejection(action, new HouseholdDeletionRejections.ReplacementManagerNotFound())
                  .type("AccountNotFoundError")
                  .path(List.of("replacementManagerAccountId"))
                  .build(),
              rejection(action, new HouseholdDeletionRejections.ReplacementManagerNotEligible())
                  .type("ProfileManagerNotEligibleError")
                  .path(List.of("replacementManagerAccountId"))
                  .build());
    };
  }

  private static RejectionCase.RejectionCaseBuilder rejection(
      Action action, HouseholdDeletionRejections.Delete rejection) {
    return RejectionCase.builder().action(action).rejection(rejection).path(List.of());
  }

  @Builder
  private record RejectionCase(
      Action action, HouseholdDeletionRejections.Delete rejection, String type, List<String> path) {
    @Override
    public String toString() {
      return action + ": " + type;
    }
  }

  private void stubOutcome(
      Action action, Outcome<UUID, HouseholdDeletionRejections.Delete> outcome) {
    stubbingFor(action).thenReturn(outcome);
  }

  private OngoingStubbing<Outcome<UUID, HouseholdDeletionRejections.Delete>> stubbingFor(
      Action action) {
    return switch (action) {
      case EMPTY ->
          when(
              service.deleteEmptyHousehold(
                  identity,
                  DeleteEmptyHouseholdCommand.builder()
                      .householdId(SOURCE)
                      .reason(REASON)
                      .build()));
      case TRANSFER ->
          when(
              service.transferLastAccountAndDeleteHousehold(
                  identity,
                  TransferLastAccountAndDeleteHouseholdCommand.builder()
                      .householdId(SOURCE)
                      .destinationHouseholdId(DESTINATION)
                      .reason(REASON)
                      .build()));
      case DELETE ->
          when(
              service.deleteLastAccountAndHousehold(
                  identity,
                  DeleteLastAccountAndHouseholdCommand.builder()
                      .householdId(SOURCE)
                      .reason(REASON)
                      .build()));
      case PRESERVE ->
          when(
              service.deleteLastAccountAndHouseholdPreservingPersonalProfile(
                  identity,
                  DeleteLastAccountAndHouseholdPreservingPersonalProfileCommand.builder()
                      .householdId(SOURCE)
                      .destinationHouseholdId(DESTINATION)
                      .replacementManagerAccountId(MANAGER)
                      .reason(REASON)
                      .build()));
    };
  }

  private JsonNode mutate(Action action, Map<String, Object> overrides) {
    var input = new LinkedHashMap<String, Object>();
    input.put("householdId", SOURCE.toString());
    input.put("reason", REASON);
    if (action == Action.TRANSFER || action == Action.PRESERVE) {
      input.put("destinationHouseholdId", DESTINATION.toString());
    }

    if (action == Action.PRESERVE) {
      input.put("replacementManagerAccountId", MANAGER.toString());
    }

    input.putAll(overrides);
    var result =
        executor.execute(
            """
        mutation($input: %s!) {
          %s(input: $input) {
            deletedHouseholdId
            userErrors { __typename ... on InputMutationError { inputPath } }
          }
        }
        """
                .formatted(action.inputType, action.operation),
            Map.of("input", input));
    return mapper.valueToTree(result.toSpecification());
  }

  private enum Action {
    EMPTY("deleteEmptyHousehold", "DeleteEmptyHouseholdInput"),
    TRANSFER("transferLastAccountAndDeleteHousehold", "TransferLastAccountAndDeleteHouseholdInput"),
    DELETE("deleteLastAccountAndHousehold", "DeleteLastAccountAndHouseholdInput"),
    PRESERVE(
        "deleteLastAccountAndHouseholdPreservingPersonalProfile",
        "DeleteLastAccountAndHouseholdPreservingPersonalProfileInput");
    private final String operation;
    private final String inputType;

    Action(String operation, String inputType) {
      this.operation = operation;
      this.inputType = inputType;
    }
  }
}
