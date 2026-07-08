package com.streamarr.server.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.exceptions.AuthenticationRequiredException;
import com.streamarr.server.exceptions.HouseholdRequiredException;
import com.streamarr.server.exceptions.ProfileRequiredException;
import graphql.Scalars;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.MergedField;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironmentImpl;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

@Tag("UnitTest")
@DisplayName("Streamarr Data Fetcher Exception Handler Tests")
class StreamarrDataFetcherExceptionHandlerTest {

  private final StreamarrDataFetcherExceptionHandler handler =
      new StreamarrDataFetcherExceptionHandler();

  @Test
  @DisplayName("Should map profile required to code when profile missing")
  void shouldMapProfileRequiredToCodeWhenProfileMissing() {
    assertThat(codeFor(new ProfileRequiredException())).isEqualTo("PROFILE_REQUIRED");
  }

  @Test
  @DisplayName("Should map household required to code when household missing")
  void shouldMapHouseholdRequiredToCodeWhenHouseholdMissing() {
    assertThat(codeFor(new HouseholdRequiredException())).isEqualTo("HOUSEHOLD_REQUIRED");
  }

  @Test
  @DisplayName("Should map authentication required to code when identity missing")
  void shouldMapAuthenticationRequiredToCodeWhenIdentityMissing() {
    assertThat(codeFor(new AuthenticationRequiredException())).isEqualTo("AUTHENTICATION_REQUIRED");
  }

  @Test
  @DisplayName("Should map access denied to forbidden when authorization fails")
  void shouldMapAccessDeniedToForbiddenWhenAuthorizationFails() {
    assertThat(codeFor(new AccessDeniedException("denied"))).isEqualTo("FORBIDDEN");
  }

  @Test
  @DisplayName("Should unwrap completion exception when data loader fails async")
  void shouldUnwrapCompletionExceptionWhenDataLoaderFailsAsync() {
    assertThat(codeFor(new CompletionException(new ProfileRequiredException())))
        .isEqualTo("PROFILE_REQUIRED");
  }

  @Test
  @DisplayName("Should delegate to default handler when exception unrelated to identity")
  void shouldDelegateToDefaultHandlerWhenExceptionUnrelatedToIdentity() {
    assertThat(codeFor(new IllegalStateException("boom"))).isNull();
  }

  // The cause-null arm of unwrap stays untested on purpose: a cause-less CompletionException is
  // only constructible by hand (CompletableFuture always wraps a cause), and DGS's default
  // handler recurses infinitely on one — the guard exists to keep our unwrap out of that state.

  private String codeFor(Throwable exception) {
    var result = handler.handleException(parameters(exception)).join();

    var extensions = result.getErrors().getFirst().getExtensions();
    return extensions == null ? null : (String) extensions.get("code");
  }

  private static DataFetcherExceptionHandlerParameters parameters(Throwable exception) {
    var stepInfo =
        ExecutionStepInfo.newExecutionStepInfo()
            .type(Scalars.GraphQLString)
            .path(ResultPath.parse("/test"))
            .build();
    var environment =
        DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .mergedField(MergedField.newMergedField(Field.newField("test").build()).build())
            .executionStepInfo(stepInfo)
            .build();

    return DataFetcherExceptionHandlerParameters.newExceptionParameters()
        .dataFetchingEnvironment(environment)
        .exception(exception)
        .build();
  }
}
