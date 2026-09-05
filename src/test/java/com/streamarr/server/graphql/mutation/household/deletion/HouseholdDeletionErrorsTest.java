package com.streamarr.server.graphql.mutation.household.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.services.identity.HouseholdDeletionRejections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Household Deletion Errors Tests")
class HouseholdDeletionErrorsTest {

  @ParameterizedTest
  @MethodSource("deletionRejections")
  @DisplayName("Should map to the matching schema error when Household deletion is rejected")
  void shouldMapToMatchingSchemaErrorWhenHouseholdDeletionIsRejected(
      HouseholdDeletionRejections.Delete rejection, Class<?> schemaErrorType) {
    assertThat(
            HouseholdDeletionErrors.toDeleteLastAccountAndHouseholdPreservingPersonalProfileError(
                rejection))
        .isInstanceOf(schemaErrorType);
  }

  @Test
  @DisplayName("Should expose only errors declared by each deletion action")
  void shouldExposeOnlyErrorsDeclaredByEachDeletionAction() {
    var common = new HouseholdDeletionRejections.AccountsRemain();
    var destination = new HouseholdDeletionRejections.DestinationNotFound();
    var lastAdmin = new HouseholdDeletionRejections.LastServerAdmin();

    assertThat(HouseholdDeletionErrors.toDeleteEmptyHouseholdError(common))
        .isInstanceOf(AccountsRemainError.class);
    assertThat(HouseholdDeletionErrors.toTransferLastAccountAndDeleteHouseholdError(destination))
        .isInstanceOf(DestinationNotFoundError.class);
    assertThat(HouseholdDeletionErrors.toDeleteLastAccountAndHouseholdError(lastAdmin))
        .isInstanceOf(LastServerAdminError.class);
    assertThatThrownBy(() -> HouseholdDeletionErrors.toDeleteEmptyHouseholdError(destination))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> HouseholdDeletionErrors.toTransferLastAccountAndDeleteHouseholdError(lastAdmin))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> HouseholdDeletionErrors.toDeleteLastAccountAndHouseholdError(destination))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Should map direct input paths for action-specific IDs")
  void shouldMapDirectInputPathsForActionSpecificIds() {
    var destination =
        (DestinationNotFoundError)
            HouseholdDeletionErrors.toTransferLastAccountAndDeleteHouseholdError(
                new HouseholdDeletionRejections.DestinationNotFound());
    var replacement =
        (AccountNotFoundError)
            HouseholdDeletionErrors.toDeleteLastAccountAndHouseholdPreservingPersonalProfileError(
                new HouseholdDeletionRejections.ReplacementManagerNotFound());

    assertThat(destination.inputPath()).containsExactly("destinationHouseholdId");
    assertThat(replacement.inputPath()).containsExactly("replacementManagerAccountId");
    assertThat(HouseholdDeletionErrors.invalidId("householdId").inputPath())
        .isEqualTo(List.of("householdId"));
  }

  private static Stream<Arguments> deletionRejections() {
    return Stream.of(
        Arguments.of(
            new HouseholdDeletionRejections.HouseholdNotFound(), HouseholdNotFoundError.class),
        Arguments.of(new HouseholdDeletionRejections.ReasonRequired(), ReasonRequiredError.class),
        Arguments.of(
            new HouseholdDeletionRejections.ReauthenticationRequired(),
            ReauthenticationRequiredError.class),
        Arguments.of(new HouseholdDeletionRejections.AccountsRemain(), AccountsRemainError.class),
        Arguments.of(
            new HouseholdDeletionRejections.LastAccountNotFound(), LastAccountNotFoundError.class),
        Arguments.of(
            new HouseholdDeletionRejections.DestinationNotFound(), DestinationNotFoundError.class),
        Arguments.of(
            new HouseholdDeletionRejections.ReplacementManagerNotFound(),
            AccountNotFoundError.class),
        Arguments.of(
            new HouseholdDeletionRejections.ReplacementManagerNotEligible(),
            ProfileManagerNotEligibleError.class),
        Arguments.of(
            new HouseholdDeletionRejections.LastServerAdmin(), LastServerAdminError.class));
  }
}
