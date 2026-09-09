package com.streamarr.server.graphql.mutation.household.deletion;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.graphql.mutation.MutationError;
import com.streamarr.server.services.identity.HouseholdDeletionRejections;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Household Deletion Error Tests")
class HouseholdDeletionErrorsTest {

  @ParameterizedTest
  @MethodSource("lastServerAdminErrors")
  @DisplayName("Should explain the ServerAdmin requirement when deletion is rejected")
  void shouldExplainServerAdminRequirementWhenDeletionIsRejected(MutationError error) {
    assertThat(error.message()).isEqualTo("At least one enabled ServerAdmin must remain.");
  }

  private static Stream<MutationError> lastServerAdminErrors() {
    var rejection = new HouseholdDeletionRejections.LastServerAdmin();
    return Stream.of(
        HouseholdDeletionErrors.toDeleteLastAccountAndHouseholdError(rejection),
        HouseholdDeletionErrors.toDeleteLastAccountAndHouseholdPreservingPersonalProfileError(
            rejection));
  }
}
