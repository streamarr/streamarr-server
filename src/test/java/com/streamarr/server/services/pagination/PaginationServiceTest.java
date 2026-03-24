package com.streamarr.server.services.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.exceptions.InvalidPaginationArgumentException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Relay Spec Pagination Service Tests")
class PaginationServiceTest {

  private final PaginationService paginationService = new PaginationService();

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
        .withMessageContaining("first must be greater than zero.");
  }

  @Test
  @DisplayName("Should throw exception when paginating with negative last argument.")
  void shouldThrowExceptionWhenPaginatingWithNegativeLastLimit() {
    assertThatExceptionOfType(InvalidPaginationArgumentException.class)
        .isThrownBy(() -> paginationService.getPaginationOptions(0, null, -1, "cursor"))
        .withMessageContaining("last must be greater than zero.");
  }

  @Test
  @DisplayName("Should throw exception when paginating with first argument that is too large.")
  void shouldThrowExceptionWhenPaginatingWithFirstLimitTooLarge() {
    assertThatExceptionOfType(InvalidPaginationArgumentException.class)
        .isThrownBy(() -> paginationService.getPaginationOptions(501, "cursor", 0, null))
        .withMessageContaining("first must be less than 500.");
  }

  @Test
  @DisplayName("Should throw exception when paginating with last argument that is too large.")
  void shouldThrowExceptionWhenPaginatingWithLastLimitTooLarge() {
    assertThatExceptionOfType(InvalidPaginationArgumentException.class)
        .isThrownBy(() -> paginationService.getPaginationOptions(0, null, 501, "cursor"))
        .withMessageContaining("last must be less than 500.");
  }

  @Test
  @DisplayName(
      "Should throw exception when paginating with no cursor and first argument is too large.")
  void shouldThrowExceptionWhenPaginatingWithNoCursorAndFirstLimitTooLarge() {
    assertThatExceptionOfType(InvalidPaginationArgumentException.class)
        .isThrownBy(() -> paginationService.getPaginationOptions(501, null, 0, null))
        .withMessageContaining("first must be less than 500.");
  }

  @Test
  @DisplayName("Should throw exception when paginating with no cursor and only last argument.")
  void shouldThrowExceptionWhenPaginatingWithNoCursorAndOnlyLast() {
    assertThatExceptionOfType(InvalidPaginationArgumentException.class)
        .isThrownBy(() -> paginationService.getPaginationOptions(0, null, 1, null))
        .withMessageContaining("first must be greater than zero.");
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

  // --- buildMediaPage tests ---

  // --- buildMediaPage tests (mirrors buildConnection) ---

  @Test
  @DisplayName("Should build media page when given single result in non-seek result list")
  void shouldBuildMediaPageWhenGivenSingleResultInNonSeekList() {
    var items = List.of(new PageItem<>(Movie.builder().build(), "sortVal"));

    var options =
        PaginationOptions.builder()
            .cursor(Optional.empty())
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(1)
            .build();

    var page = paginationService.buildMediaPage(items, options, Optional.empty());

    assertThat(page.items()).hasSize(1);
    assertThat(page.hasNextPage()).isFalse();
    assertThat(page.hasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName(
      "Should build media page of one when limited by one given multiple results in non-seek list")
  void shouldBuildMediaPageOfOneWhenLimitedByOneGivenMultipleResultsInNonSeekList() {
    var items =
        List.of(
            new PageItem<>(Movie.builder().build(), "sortVal1"),
            new PageItem<>(Movie.builder().build(), "sortVal2"));

    var options =
        PaginationOptions.builder()
            .cursor(Optional.empty())
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(1)
            .build();

    var page = paginationService.buildMediaPage(items, options, Optional.empty());

    assertThat(page.items()).hasSize(1);
    assertThat(page.hasNextPage()).isTrue();
    assertThat(page.hasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName("Should build empty media page when paginating forward given empty list")
  void shouldBuildEmptyMediaPageWhenPaginatingForwardGivenEmptyList() {
    List<PageItem<Movie>> items = Collections.emptyList();

    var options =
        PaginationOptions.builder()
            .cursor(Optional.empty())
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(1)
            .build();

    var page = paginationService.buildMediaPage(items, options, Optional.empty());

    assertThat(page.items()).isEmpty();
    assertThat(page.hasNextPage()).isFalse();
    assertThat(page.hasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName("Should build empty media page when paginating backward given empty list")
  void shouldBuildEmptyMediaPageWhenPaginatingReverseGivenEmptyList() {
    List<PageItem<Movie>> items = Collections.emptyList();

    var options =
        PaginationOptions.builder()
            .cursor(Optional.empty())
            .paginationDirection(PaginationDirection.REVERSE)
            .limit(1)
            .build();

    var page = paginationService.buildMediaPage(items, options, Optional.empty());

    assertThat(page.items()).isEmpty();
    assertThat(page.hasNextPage()).isFalse();
    assertThat(page.hasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName(
      "Should build empty media page when paginating forward given list containing only cursorId")
  void shouldBuildEmptyMediaPageWhenPaginatingForwardGivenListContainingCursorId() {
    var itemId = UUID.randomUUID();

    var items = List.of(new PageItem<>(Movie.builder().id(itemId).build(), "sortVal"));

    var options =
        PaginationOptions.builder()
            .cursor(Optional.of("cursor"))
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(1)
            .build();

    var page = paginationService.buildMediaPage(items, options, Optional.of(itemId));

    assertThat(page.items()).isEmpty();
    assertThat(page.hasNextPage()).isFalse();
    assertThat(page.hasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName(
      "Should build empty media page when paginating backward given list containing only cursorId")
  void shouldBuildEmptyMediaPageWhenPaginatingBackwardGivenListContainingCursorId() {
    var itemId = UUID.randomUUID();

    var items = List.of(new PageItem<>(Movie.builder().id(itemId).build(), "sortVal"));

    var options =
        PaginationOptions.builder()
            .cursor(Optional.of("cursor"))
            .paginationDirection(PaginationDirection.REVERSE)
            .limit(1)
            .build();

    var page = paginationService.buildMediaPage(items, options, Optional.of(itemId));

    assertThat(page.items()).isEmpty();
    assertThat(page.hasNextPage()).isFalse();
    assertThat(page.hasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName(
      "Should build media page when paginating forward given list containing result from previous page")
  void shouldBuildMediaPageWhenPaginatingForwardGivenSeekListContainingPreviousPage() {
    var itemId1 = UUID.randomUUID();
    var itemId2 = UUID.randomUUID();

    var items =
        List.of(
            new PageItem<>(Movie.builder().id(itemId1).build(), "sortVal1"),
            new PageItem<>(Movie.builder().id(itemId2).build(), "sortVal2"));

    var options =
        PaginationOptions.builder()
            .cursor(Optional.of("cursor"))
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(1)
            .build();

    var page = paginationService.buildMediaPage(items, options, Optional.of(itemId1));

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().getFirst().item().getId()).isEqualTo(itemId2);
    assertThat(page.hasNextPage()).isFalse();
    assertThat(page.hasPreviousPage()).isTrue();
  }

  @Test
  @DisplayName(
      "Should build media page when paginating backward given list containing result from next page")
  void shouldBuildMediaPageWhenPaginatingBackwardGivenSeekListContainingNextPage() {
    var itemId1 = UUID.randomUUID();
    var itemId2 = UUID.randomUUID();

    var items =
        List.of(
            new PageItem<>(Movie.builder().id(itemId2).build(), "sortVal2"),
            new PageItem<>(Movie.builder().id(itemId1).build(), "sortVal1"));

    var options =
        PaginationOptions.builder()
            .cursor(Optional.of("cursor"))
            .paginationDirection(PaginationDirection.REVERSE)
            .limit(1)
            .build();

    var page = paginationService.buildMediaPage(items, options, Optional.of(itemId1));

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().getFirst().item().getId()).isEqualTo(itemId2);
    assertThat(page.hasNextPage()).isTrue();
    assertThat(page.hasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName(
      "Should build media page when paginating forward given list with previous and next page results")
  void shouldBuildMediaPageWhenPaginatingForwardGivenSeekListContainingPreviousPageAndNextPage() {
    var itemId1 = UUID.randomUUID();
    var itemId2 = UUID.randomUUID();
    var itemId3 = UUID.randomUUID();

    var items =
        List.of(
            new PageItem<>(Movie.builder().id(itemId1).build(), "sortVal1"),
            new PageItem<>(Movie.builder().id(itemId2).build(), "sortVal2"),
            new PageItem<>(Movie.builder().id(itemId3).build(), "sortVal3"));

    var options =
        PaginationOptions.builder()
            .cursor(Optional.of("cursor"))
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(1)
            .build();

    var page = paginationService.buildMediaPage(items, options, Optional.of(itemId1));

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().getFirst().item().getId()).isEqualTo(itemId2);
    assertThat(page.hasNextPage()).isTrue();
    assertThat(page.hasPreviousPage()).isTrue();
  }

  @Test
  @DisplayName(
      "Should build media page when paginating backward given list with next and previous page results")
  void shouldBuildMediaPageWhenPaginatingBackwardGivenSeekListContainingNextPageAndPreviousPage() {
    var itemId1 = UUID.randomUUID();
    var itemId2 = UUID.randomUUID();
    var itemId3 = UUID.randomUUID();

    var items =
        List.of(
            new PageItem<>(Movie.builder().id(itemId3).build(), "sortVal3"),
            new PageItem<>(Movie.builder().id(itemId2).build(), "sortVal2"),
            new PageItem<>(Movie.builder().id(itemId1).build(), "sortVal1"));

    var options =
        PaginationOptions.builder()
            .cursor(Optional.of("cursor"))
            .paginationDirection(PaginationDirection.REVERSE)
            .limit(1)
            .build();

    var page = paginationService.buildMediaPage(items, options, Optional.of(itemId1));

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().getFirst().item().getId()).isEqualTo(itemId2);
    assertThat(page.hasNextPage()).isTrue();
    assertThat(page.hasPreviousPage()).isTrue();
  }

  @Test
  @DisplayName("Should not drop first item when cursor item is absent from forward seek results")
  void shouldNotDropFirstItemWhenCursorItemAbsentFromForwardSeekResults() {
    var cursorId = UUID.randomUUID();
    var itemId = UUID.randomUUID();

    var items = List.of(new PageItem<>(Movie.builder().id(itemId).build(), "sortVal"));

    var options =
        PaginationOptions.builder()
            .cursor(Optional.of("c1"))
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(1)
            .build();

    var page = paginationService.buildMediaPage(items, options, Optional.of(cursorId));

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().getFirst().item().getId()).isEqualTo(itemId);
    assertThat(page.hasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName("Should not drop last item when cursor item is absent from backward seek results")
  void shouldNotDropLastItemWhenCursorItemAbsentFromBackwardSeekResults() {
    var cursorId = UUID.randomUUID();
    var itemId = UUID.randomUUID();

    var items = List.of(new PageItem<>(Movie.builder().id(itemId).build(), "sortVal"));

    var options =
        PaginationOptions.builder()
            .cursor(Optional.of("c1"))
            .paginationDirection(PaginationDirection.REVERSE)
            .limit(1)
            .build();

    var page = paginationService.buildMediaPage(items, options, Optional.of(cursorId));

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().getFirst().item().getId()).isEqualTo(itemId);
    assertThat(page.hasNextPage()).isFalse();
  }

  @Test
  @DisplayName("Should return at most limit items when cursor item absent from forward results")
  void shouldReturnAtMostLimitItemsWhenCursorItemAbsentFromForwardResults() {
    var cursorId = UUID.randomUUID();

    var items =
        List.of(
            new PageItem<>(Movie.builder().id(UUID.randomUUID()).build(), "s1"),
            new PageItem<>(Movie.builder().id(UUID.randomUUID()).build(), "s2"),
            new PageItem<>(Movie.builder().id(UUID.randomUUID()).build(), "s3"));

    var options =
        PaginationOptions.builder()
            .cursor(Optional.of("c1"))
            .paginationDirection(PaginationDirection.FORWARD)
            .limit(1)
            .build();

    var page = paginationService.buildMediaPage(items, options, Optional.of(cursorId));

    assertThat(page.items()).hasSize(1);
  }
}
