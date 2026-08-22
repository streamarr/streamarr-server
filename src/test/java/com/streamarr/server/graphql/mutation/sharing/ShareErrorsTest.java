package com.streamarr.server.graphql.mutation.sharing;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.streamarr.server.services.identity.ShareRejections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Share Errors Tests")
class ShareErrorsTest {

  @Test
  @DisplayName("Should map every offer rejection when an offer fails")
  void shouldMapEveryOfferRejectionWhenOfferFails() {
    assertSoftly(
        softly -> {
          softly
              .assertThat(ShareErrors.toOfferError(new ShareRejections.ProfileNotFound()))
              .isExactlyInstanceOf(ProfileNotFoundError.class);
          softly
              .assertThat(ShareErrors.toOfferError(new ShareRejections.HouseholdNotFound()))
              .isExactlyInstanceOf(HouseholdNotFoundError.class);
          softly
              .assertThat(ShareErrors.toOfferError(new ShareRejections.AlreadyShared()))
              .isExactlyInstanceOf(ProfileAlreadySharedError.class);
        });
  }

  @Test
  @DisplayName("Should map every acceptance rejection when acceptance fails")
  void shouldMapEveryAcceptanceRejectionWhenAcceptanceFails() {
    assertSoftly(
        softly -> {
          softly
              .assertThat(ShareErrors.toAcceptError(new ShareRejections.ShareNotFound()))
              .isExactlyInstanceOf(ShareNotFoundError.class);
          softly
              .assertThat(ShareErrors.toAcceptError(new ShareRejections.ShareNotPending()))
              .isExactlyInstanceOf(ShareNotPendingError.class);
          softly
              .assertThat(ShareErrors.toAcceptError(new ShareRejections.NoEligibleAdmin()))
              .isExactlyInstanceOf(NoEligibleAdminError.class);
          softly
              .assertThat(ShareErrors.toAcceptError(new ShareRejections.NameConflict()))
              .isExactlyInstanceOf(ShareNameConflictError.class);
        });
  }

  @Test
  @DisplayName("Should map every decision rejection when rejecting an offer fails")
  void shouldMapEveryDecisionRejectionWhenRejectingOfferFails() {
    assertSoftly(
        softly -> {
          softly
              .assertThat(ShareErrors.toRejectError(new ShareRejections.ShareNotFound()))
              .isExactlyInstanceOf(ShareNotFoundError.class);
          softly
              .assertThat(ShareErrors.toRejectError(new ShareRejections.ShareNotPending()))
              .isExactlyInstanceOf(ShareNotPendingError.class);
        });
  }

  @Test
  @DisplayName("Should map every cancellation rejection when cancellation fails")
  void shouldMapEveryCancellationRejectionWhenCancellationFails() {
    assertSoftly(
        softly -> {
          softly
              .assertThat(ShareErrors.toCancelError(new ShareRejections.ShareNotFound()))
              .isExactlyInstanceOf(ShareNotFoundError.class);
          softly
              .assertThat(ShareErrors.toCancelError(new ShareRejections.ShareNotPending()))
              .isExactlyInstanceOf(ShareNotPendingError.class);
        });
  }

  @Test
  @DisplayName("Should map every end rejection when an ordinary end fails")
  void shouldMapEveryEndRejectionWhenOrdinaryEndFails() {
    assertSoftly(
        softly -> {
          softly
              .assertThat(ShareErrors.toEndError(new ShareRejections.ShareNotFound()))
              .isExactlyInstanceOf(ShareNotFoundError.class);
          softly
              .assertThat(ShareErrors.toEndError(new ShareRejections.ShareNotActive()))
              .isExactlyInstanceOf(ShareNotActiveError.class);
          softly
              .assertThat(ShareErrors.toEndError(new ShareRejections.StructuralShareCannotEnd()))
              .isExactlyInstanceOf(StructuralShareError.class);
        });
  }

  @Test
  @DisplayName("Should map every force-end rejection when a force-end fails")
  void shouldMapEveryForceEndRejectionWhenForceEndFails() {
    assertSoftly(
        softly -> {
          softly
              .assertThat(ShareErrors.toForceEndError(new ShareRejections.ShareNotFound()))
              .isExactlyInstanceOf(ShareNotFoundError.class);
          softly
              .assertThat(ShareErrors.toForceEndError(new ShareRejections.ShareNotActive()))
              .isExactlyInstanceOf(ShareNotActiveError.class);
          softly
              .assertThat(
                  ShareErrors.toForceEndError(new ShareRejections.StructuralShareCannotEnd()))
              .isExactlyInstanceOf(StructuralShareError.class);
          softly
              .assertThat(ShareErrors.toForceEndError(new ShareRejections.ReasonRequired()))
              .isExactlyInstanceOf(ReasonRequiredError.class);
          softly
              .assertThat(
                  ShareErrors.toForceEndError(new ShareRejections.ReauthenticationRequired()))
              .isExactlyInstanceOf(ReauthenticationRequiredError.class);
        });
  }

  @Test
  @DisplayName("Should fail visibly when an operation receives an impossible rejection")
  void shouldFailVisiblyWhenOperationReceivesImpossibleRejection() {
    assertSoftly(
        softly -> {
          softly
              .assertThatThrownBy(
                  () -> ShareErrors.toRejectError(new ShareRejections.NoEligibleAdmin()))
              .isInstanceOf(IllegalStateException.class);
          softly
              .assertThatThrownBy(
                  () -> ShareErrors.toRejectError(new ShareRejections.NameConflict()))
              .isInstanceOf(IllegalStateException.class);
          softly
              .assertThatThrownBy(
                  () -> ShareErrors.toCancelError(new ShareRejections.NoEligibleAdmin()))
              .isInstanceOf(IllegalStateException.class);
          softly
              .assertThatThrownBy(
                  () -> ShareErrors.toCancelError(new ShareRejections.NameConflict()))
              .isInstanceOf(IllegalStateException.class);
          softly
              .assertThatThrownBy(
                  () -> ShareErrors.toEndError(new ShareRejections.ReauthenticationRequired()))
              .isInstanceOf(IllegalStateException.class);
          softly
              .assertThatThrownBy(
                  () -> ShareErrors.toEndError(new ShareRejections.ReasonRequired()))
              .isInstanceOf(IllegalStateException.class);
        });
  }
}
