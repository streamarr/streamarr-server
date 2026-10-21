package com.streamarr.server.services.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.streamarr.server.exceptions.InvalidPaginationArgumentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Pagination Service Tests")
class PaginationServiceTest {

  private final PaginationService paginationService = new PaginationService();

  @Nested
  @DisplayName("Resolving Pagination Options")
  class GetPaginationOptions {

    @Test
    @DisplayName(
        "Should throw exception when paginating with both after and before arguments simultaneously.")
    void shouldThrowExceptionWhenPaginatingWithBothAfterAndBeforeCursors() {
      assertThatExceptionOfType(InvalidPaginationArgumentException.class)
          .isThrownBy(() -> paginationService.getPaginationOptions(10, "cursor", 0, "cursor"))
          .withMessageContaining("Cannot request with both after and before simultaneously.");
    }

    @Test
    @DisplayName("Should throw exception when paginating with negative first argument.")
    void shouldThrowExceptionWhenPaginatingWithNegativeFirstLimit() {
      assertThatExceptionOfType(InvalidPaginationArgumentException.class)
          .isThrownBy(() -> paginationService.getPaginationOptions(-1, "cursor", 0, null))
          .withMessageContaining("first must not be negative.");
    }

    @Test
    @DisplayName("Should throw exception when paginating with negative last argument.")
    void shouldThrowExceptionWhenPaginatingWithNegativeLastLimit() {
      assertThatExceptionOfType(InvalidPaginationArgumentException.class)
          .isThrownBy(() -> paginationService.getPaginationOptions(0, null, -1, "cursor"))
          .withMessageContaining("last must not be negative.");
    }

    @Test
    @DisplayName("Should throw exception when paginating with first argument that is too large.")
    void shouldThrowExceptionWhenPaginatingWithFirstLimitTooLarge() {
      assertThatExceptionOfType(InvalidPaginationArgumentException.class)
          .isThrownBy(() -> paginationService.getPaginationOptions(501, "cursor", 0, null))
          .withMessageContaining("first must not exceed 500.");
    }

    @Test
    @DisplayName("Should throw exception when paginating with last argument that is too large.")
    void shouldThrowExceptionWhenPaginatingWithLastLimitTooLarge() {
      assertThatExceptionOfType(InvalidPaginationArgumentException.class)
          .isThrownBy(() -> paginationService.getPaginationOptions(0, null, 501, "cursor"))
          .withMessageContaining("last must not exceed 500.");
    }

    @Test
    @DisplayName(
        "Should throw exception when paginating with no cursor and first argument is too large.")
    void shouldThrowExceptionWhenPaginatingWithNoCursorAndFirstLimitTooLarge() {
      assertThatExceptionOfType(InvalidPaginationArgumentException.class)
          .isThrownBy(() -> paginationService.getPaginationOptions(501, null, 0, null))
          .withMessageContaining("first must not exceed 500.");
    }

    @Test
    @DisplayName("Should paginate forward with zero limit when first is zero.")
    void shouldPaginateForwardWithZeroLimitWhenFirstIsZero() {
      var options = paginationService.getPaginationOptions(0, null, 0, null);

      assertThat(options.getPaginationDirection()).isEqualTo(PaginationDirection.FORWARD);
      assertThat(options.getLimit()).isZero();
    }

    @Test
    @DisplayName("Should accept last of zero when before cursor is provided")
    void shouldAcceptLastOfZeroWhenBeforeCursorProvided() {
      var options = paginationService.getPaginationOptions(0, null, 0, "cursor");

      assertThat(options.getPaginationDirection()).isEqualTo(PaginationDirection.REVERSE);
      assertThat(options.getLimit()).isZero();
      assertThat(options.getCursor()).contains("cursor");
    }

    @Test
    @DisplayName("Should accept first of zero when after cursor is provided")
    void shouldAcceptFirstOfZeroWhenAfterCursorProvided() {
      var options = paginationService.getPaginationOptions(0, "cursor", 0, null);

      assertThat(options.getPaginationDirection()).isEqualTo(PaginationDirection.FORWARD);
      assertThat(options.getLimit()).isZero();
      assertThat(options.getCursor()).contains("cursor");
    }

    @Test
    @DisplayName("Should paginate forward when given first and after.")
    void shouldPaginateForwardWhenGivenFirstAndAfter() {
      var options = paginationService.getPaginationOptions(1, "cursor", 0, null);

      assertThat(options.getPaginationDirection()).isEqualTo(PaginationDirection.FORWARD);
    }

    @Test
    @DisplayName("Should paginate forward when given first.")
    void shouldPaginateForwardWhenGivenFirst() {
      var options = paginationService.getPaginationOptions(1, null, 0, null);

      assertThat(options.getPaginationDirection()).isEqualTo(PaginationDirection.FORWARD);
    }

    @Test
    @DisplayName("Should paginate backward when given last and before.")
    void shouldPaginateBackwardWhenGivenLastAndBefore() {
      var options = paginationService.getPaginationOptions(0, null, 1, "cursor");

      assertThat(options.getPaginationDirection()).isEqualTo(PaginationDirection.REVERSE);
    }

    @Test
    @DisplayName("Should paginate backward when given last without before")
    void shouldPaginateBackwardWhenGivenLastWithoutBefore() {
      var options = paginationService.getPaginationOptions(0, null, 1, null);

      assertThat(options.getPaginationDirection()).isEqualTo(PaginationDirection.REVERSE);
      assertThat(options.getLimit()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should get cursor when given both first and after")
    void shouldHaveCursorWhenGivenBothFirstAndAfter() {
      var cursor = "cursor";

      var options = paginationService.getPaginationOptions(1, cursor, 0, null);

      assertThat(options.getCursor()).isPresent();
      assertThat(options.getCursor()).contains(cursor);
    }

    @Test
    @DisplayName("Should get cursor when given both last and before")
    void shouldHaveCursorWhenGivenBothLastAndBefore() {
      var cursor = "cursor";

      var options = paginationService.getPaginationOptions(0, null, 1, cursor);

      assertThat(options.getCursor()).isPresent();
      assertThat(options.getCursor()).contains(cursor);
    }

    @Test
    @DisplayName("Should get limit when given first")
    void shouldGetLimitWhenGivenFirst() {
      var options = paginationService.getPaginationOptions(1, null, 0, null);

      assertThat(options.getLimit()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should get limit when given first and after")
    void shouldGetLimitWhenGivenFirstAndAfter() {
      var options = paginationService.getPaginationOptions(1, "cursor", 0, null);

      assertThat(options.getLimit()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should get limit when given last and before")
    void shouldGetLimitWhenGivenLastAndBefore() {
      var options = paginationService.getPaginationOptions(0, null, 1, "cursor");

      assertThat(options.getLimit()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should accept first at max page size boundary")
    void shouldAcceptFirstAtMaxPageSize() {
      var options = paginationService.getPaginationOptions(500, null, 0, null);

      assertThat(options.getLimit()).isEqualTo(500);
    }

    @Test
    @DisplayName("Should accept last at max page size boundary")
    void shouldAcceptLastAtMaxPageSize() {
      var options = paginationService.getPaginationOptions(0, null, 500, "cursor");

      assertThat(options.getLimit()).isEqualTo(500);
    }
  }
}
