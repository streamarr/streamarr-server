package com.streamarr.server.graphql.mutation.teardown;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.services.identity.TeardownRejections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Every service rejection maps to exactly its schema error type. */
@Tag("UnitTest")
@DisplayName("Teardown Errors Tests")
class TeardownErrorsTest {

  @Test
  @DisplayName("Should map every teardown rejection to its schema error")
  void shouldMapEveryTeardownRejectionToItsSchemaError() {
    assertThat(TeardownErrors.toTearDownError(new TeardownRejections.HouseholdNotFound()))
        .isInstanceOf(HouseholdNotFoundError.class);
    assertThat(TeardownErrors.toTearDownError(new TeardownRejections.ReasonRequired()))
        .isInstanceOf(ReasonRequiredError.class);
    assertThat(TeardownErrors.toTearDownError(new TeardownRejections.ReauthenticationRequired()))
        .isInstanceOf(ReauthenticationRequiredError.class);
    assertThat(TeardownErrors.toTearDownError(new TeardownRejections.AccountsRemain()))
        .isInstanceOf(AccountsRemainError.class);
    assertThat(TeardownErrors.toTearDownError(new TeardownRejections.FinalAccountRequired()))
        .isInstanceOf(FinalAccountRequiredError.class);
    assertThat(TeardownErrors.toTearDownError(new TeardownRejections.FinalAccountUnexpected()))
        .isInstanceOf(FinalAccountUnexpectedError.class);
    assertThat(TeardownErrors.toTearDownError(new TeardownRejections.DestinationRequired()))
        .isInstanceOf(DestinationRequiredError.class);
    assertThat(TeardownErrors.toTearDownError(new TeardownRejections.DestinationNotFound()))
        .isInstanceOf(DestinationNotFoundError.class);
    assertThat(TeardownErrors.toTearDownError(new TeardownRejections.ReplacementManagerRequired()))
        .isInstanceOf(ReplacementManagerRequiredError.class);
    assertThat(TeardownErrors.toTearDownError(new TeardownRejections.ReplacementManagerNotFound()))
        .isInstanceOf(ReplacementManagerNotFoundError.class);
    assertThat(
            TeardownErrors.toTearDownError(new TeardownRejections.ReplacementManagerNotEligible()))
        .isInstanceOf(ReplacementManagerNotEligibleError.class);
    assertThat(TeardownErrors.toTearDownError(new TeardownRejections.LastServerAdmin()))
        .isInstanceOf(LastServerAdminError.class);
  }
}
