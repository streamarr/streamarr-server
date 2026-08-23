package com.streamarr.server.graphql.mutation.teardown;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.identity.TeardownRejections;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every service rejection maps to exactly its schema error type. */
@Tag("UnitTest")
@DisplayName("Teardown Errors Tests")
class TeardownErrorsTest {

  @ParameterizedTest(name = "{1}")
  @MethodSource("rejectionMappings")
  @DisplayName("Should map to the matching schema error when teardown is rejected")
  void shouldMapToMatchingSchemaErrorWhenTeardownIsRejected(
      TeardownRejections.TearDown rejection, Class<?> schemaErrorType) {
    assertThat(TeardownErrors.toTearDownError(rejection)).isInstanceOf(schemaErrorType);
  }

  @Test
  @DisplayName("Should map nested input paths as separate segments when teardown is rejected")
  void shouldMapNestedInputPathsAsSeparateSegmentsWhenTeardownIsRejected() {
    var destination =
        (DestinationRequiredError)
            TeardownErrors.toTearDownError(new TeardownRejections.DestinationRequired());
    var replacement =
        (ReplacementManagerRequiredError)
            TeardownErrors.toTearDownError(new TeardownRejections.ReplacementManagerRequired());

    assertThat(destination.inputPath()).containsExactly("lastAccount", "destinationHouseholdId");
    assertThat(replacement.inputPath())
        .containsExactly("lastAccount", "replacementManagerAccountId");
  }

  private static Stream<Arguments> rejectionMappings() {
    return Stream.of(
        Arguments.of(new TeardownRejections.HouseholdNotFound(), HouseholdNotFoundError.class),
        Arguments.of(new TeardownRejections.ReasonRequired(), ReasonRequiredError.class),
        Arguments.of(
            new TeardownRejections.ReauthenticationRequired(), ReauthenticationRequiredError.class),
        Arguments.of(new TeardownRejections.AccountsRemain(), AccountsRemainError.class),
        Arguments.of(
            new TeardownRejections.FinalAccountRequired(), LastAccountActionRequiredError.class),
        Arguments.of(
            new TeardownRejections.FinalAccountUnexpected(),
            LastAccountActionNotAllowedError.class),
        Arguments.of(new TeardownRejections.DestinationRequired(), DestinationRequiredError.class),
        Arguments.of(new TeardownRejections.DestinationNotFound(), DestinationNotFoundError.class),
        Arguments.of(
            new TeardownRejections.ReplacementManagerRequired(),
            ReplacementManagerRequiredError.class),
        Arguments.of(
            new TeardownRejections.ReplacementManagerNotFound(), AccountNotFoundError.class),
        Arguments.of(
            new TeardownRejections.ReplacementManagerNotEligible(),
            ProfileManagerNotEligibleError.class),
        Arguments.of(new TeardownRejections.LastServerAdmin(), LastServerAdminError.class));
  }
}
