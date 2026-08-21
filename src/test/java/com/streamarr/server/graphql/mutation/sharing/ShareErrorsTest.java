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
