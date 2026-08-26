package com.streamarr.server.graphql.resolvers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import com.netflix.graphql.dgs.test.EnableDgsTest;
import com.streamarr.server.fixtures.AuthenticatedIdentityFixture;
import com.streamarr.server.graphql.StreamarrDataFetcherExceptionHandler;
import com.streamarr.server.graphql.cursor.CursorUtil;
import com.streamarr.server.graphql.cursor.CursorValidator;
import com.streamarr.server.graphql.cursor.RelayConnectionAdapter;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.identity.AdministrationQueryService;
import com.streamarr.server.services.identity.CredentialIssuanceService;
import com.streamarr.server.services.identity.InvitationRejections;
import com.streamarr.server.services.mutation.Outcome;
import com.streamarr.server.services.pagination.PaginationService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Tag("UnitTest")
@EnableDgsTest
@SpringBootTest(
    classes = {
      CredentialAdministrationResolver.class,
      JacksonAutoConfiguration.class,
      StreamarrDataFetcherExceptionHandler.class
    })
@DisplayName("Credential Administration Resolver Tests")
class CredentialAdministrationResolverTest {

  private static final String ISSUE_INVITATION_MUTATION =
      """
      mutation {
        issueAccountInvitation(input: {
          recipientEmail: "invitee@example.com"
          householdId: "%s"
          householdRole: MEMBER
          profileName: "Invitee"
          profileKind: ADULT
        }) {
          issued { code }
          userErrors { __typename ... on InputMutationError { inputPath } }
        }
      }
      """
          .formatted(UUID.randomUUID());
  private static final String CANCEL_INVITATION_MUTATION =
      """
      mutation {
        cancelAccountInvitation(input: { invitationId: "%s" }) {
          invitation { status }
          userErrors { __typename }
        }
      }
      """
          .formatted(UUID.randomUUID());
  private static final String ISSUE_RESET_MUTATION =
      """
      mutation {
        issuePasswordReset(input: { accountId: "%s", reason: "locked out" }) {
          issued { code }
          userErrors { __typename }
        }
      }
      """
          .formatted(UUID.randomUUID());

  @Autowired private DgsQueryExecutor dgsQueryExecutor;

  @MockitoBean private AuthorizationService authorizationService;

  @MockitoBean private CredentialIssuanceService credentialIssuanceService;

  @MockitoBean private AdministrationQueryService administrationQueryService;

  @MockitoBean private PaginationService paginationService;

  @MockitoBean private CursorUtil cursorUtil;

  @MockitoBean private CursorValidator cursorValidator;

  @MockitoBean private RelayConnectionAdapter relayConnectionAdapter;

  @MockitoBean private Clock clock;

  @BeforeEach
  void setUpIdentity() {
    when(authorizationService.currentIdentity())
        .thenReturn(AuthenticatedIdentityFixture.accountScopedBuilder().build());
  }

  @ParameterizedTest(name = "Should expose {0} through the invitation payload union")
  @MethodSource("issuanceErrorCases")
  @DisplayName(
      "Should expose an issuance rejection through the invitation payload union when the service rejects")
  void shouldExposeIssuanceRejectionThroughInvitationPayloadUnionWhenServiceRejects(
      IssuanceErrorCase errorCase) {
    when(credentialIssuanceService.issueAccountInvitation(any(), any()))
        .thenReturn(Outcome.rejected(errorCase.rejection()));

    String type =
        dgsQueryExecutor.executeAndExtractJsonPath(
            ISSUE_INVITATION_MUTATION, "data.issueAccountInvitation.userErrors[0].__typename");

    assertThat(type).isEqualTo(errorCase.expectedType());
    if (errorCase.expectedInputPath() == null) {
      return;
    }

    List<String> inputPath =
        dgsQueryExecutor.executeAndExtractJsonPath(
            ISSUE_INVITATION_MUTATION, "data.issueAccountInvitation.userErrors[0].inputPath");
    assertThat(inputPath).containsExactlyElementsOf(errorCase.expectedInputPath());
  }

  @Test
  @DisplayName(
      "Should expose InvitationNotPending through the cancellation payload union when the service rejects")
  void shouldExposeInvitationNotPendingThroughCancellationPayloadUnionWhenServiceRejects() {
    when(credentialIssuanceService.cancelAccountInvitation(any(), any()))
        .thenReturn(Outcome.rejected(new InvitationRejections.InvitationNotPending()));

    String type =
        dgsQueryExecutor.executeAndExtractJsonPath(
            CANCEL_INVITATION_MUTATION, "data.cancelAccountInvitation.userErrors[0].__typename");

    assertThat(type).isEqualTo("InvitationNotPendingError");
  }

  @ParameterizedTest(name = "Should expose {0} through the password-reset payload union")
  @MethodSource("resetErrorCases")
  @DisplayName(
      "Should expose a reset rejection through the password-reset payload union when the service rejects")
  void shouldExposeResetRejectionThroughPasswordResetPayloadUnionWhenServiceRejects(
      ResetErrorCase errorCase) {
    when(credentialIssuanceService.issuePasswordReset(any(), any(), anyString()))
        .thenReturn(Outcome.rejected(errorCase.rejection()));

    String type =
        dgsQueryExecutor.executeAndExtractJsonPath(
            ISSUE_RESET_MUTATION, "data.issuePasswordReset.userErrors[0].__typename");

    assertThat(type).isEqualTo(errorCase.expectedType());
  }

  private static Stream<IssuanceErrorCase> issuanceErrorCases() {
    return Stream.of(
        new IssuanceErrorCase(new InvitationRejections.EmailRequired(), "EmailRequiredError", null),
        new IssuanceErrorCase(
            new InvitationRejections.EmailAlreadyUsed(), "EmailAlreadyUsedError", null),
        new IssuanceErrorCase(
            new InvitationRejections.ProfileNameRequired(), "ProfileNameRequiredError", null),
        new IssuanceErrorCase(
            new InvitationRejections.ProfileNameTaken(), "ProfileNameTakenError", null),
        new IssuanceErrorCase(
            new InvitationRejections.HouseholdNotFound(), "HouseholdNotFoundError", null),
        new IssuanceErrorCase(
            new InvitationRejections.RestrictedFirstAccount(), "RestrictedFirstAccountError", null),
        new IssuanceErrorCase(
            new InvitationRejections.RestrictedHouseholdAdmin(),
            "RestrictedHouseholdAdminError",
            List.of("householdRole")),
        new IssuanceErrorCase(
            new InvitationRejections.LocalManagerRequired(),
            "EligibleProfileManagerRequiredError",
            List.of("profileManagerAccountId")),
        new IssuanceErrorCase(
            new InvitationRejections.LocalManagerNotFound(),
            "AccountNotFoundError",
            List.of("profileManagerAccountId")),
        new IssuanceErrorCase(
            new InvitationRejections.MaximumAllowedRatingAgeInvalid(),
            "MaximumAllowedRatingAgeInvalidError",
            null));
  }

  private static Stream<ResetErrorCase> resetErrorCases() {
    return Stream.of(
        new ResetErrorCase(new InvitationRejections.AccountNotFound(), "AccountNotFoundError"),
        new ResetErrorCase(new InvitationRejections.ReasonRequired(), "ReasonRequiredError"),
        new ResetErrorCase(
            new InvitationRejections.ReauthenticationRequired(), "ReauthenticationRequiredError"));
  }

  private record IssuanceErrorCase(
      InvitationRejections.Issue rejection, String expectedType, List<String> expectedInputPath) {

    @Override
    public String toString() {
      return expectedType;
    }
  }

  private record ResetErrorCase(InvitationRejections.IssueReset rejection, String expectedType) {

    @Override
    public String toString() {
      return expectedType;
    }
  }
}
