package com.streamarr.server.services.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.exceptions.InvalidPaginationCursorException;
import com.streamarr.server.fakes.FakePagedList;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Pager")
class PagerTest {

  private static final String SCOPE = "profile";

  private final FakePagedList list = new FakePagedList();
  private final List<Row<String>> rows =
      IntStream.rangeClosed(1, 5).mapToObj(n -> list.add("item-" + n, n * 10L)).toList();

  @Test
  @DisplayName("Should read the first rows when a forward request has no position")
  void shouldReadTheFirstRowsWhenAForwardRequestHasNoPosition() {
    var page = Pager.page(list, SCOPE, forward(Optional.empty(), 2));

    assertPage(page, false, true, "item-1", "item-2");
  }

  @Test
  @DisplayName("Should continue after the position when a forward request has one")
  void shouldContinueAfterThePositionWhenAForwardRequestHasOne() {
    var page = Pager.page(list, SCOPE, forward(position(2), 2));

    assertPage(page, true, true, "item-3", "item-4");
  }

  @Test
  @DisplayName("Should report no next page when a forward request reaches the end")
  void shouldReportNoNextPageWhenAForwardRequestReachesTheEnd() {
    var page = Pager.page(list, SCOPE, forward(position(4), 2));

    assertPage(page, true, false, "item-5");
  }

  @Test
  @DisplayName("Should report a previous page when the position is the first row")
  void shouldReportAPreviousPageWhenThePositionIsTheFirstRow() {
    var page = Pager.page(list, SCOPE, forward(position(1), 1));

    assertPage(page, true, true, "item-2");
  }

  @Test
  @DisplayName("Should read the last rows in list order when a reverse request has no position")
  void shouldReadTheLastRowsInListOrderWhenAReverseRequestHasNoPosition() {
    var page = Pager.page(list, SCOPE, reverse(Optional.empty(), 2));

    assertPage(page, true, false, "item-4", "item-5");
  }

  @Test
  @DisplayName(
      "Should read the rows before the position in list order when a reverse request has one")
  void shouldReadTheRowsBeforeThePositionInListOrderWhenAReverseRequestHasOne() {
    var page = Pager.page(list, SCOPE, reverse(position(4), 2));

    assertPage(page, true, true, "item-2", "item-3");
  }

  @Test
  @DisplayName("Should report no previous page when a reverse request reaches the start")
  void shouldReportNoPreviousPageWhenAReverseRequestReachesTheStart() {
    var page = Pager.page(list, SCOPE, reverse(position(3), 5));

    assertPage(page, false, true, "item-1", "item-2");
  }

  @Test
  @DisplayName("Should continue from the position when its row was removed")
  void shouldContinueFromThePositionWhenItsRowWasRemoved() {
    var anchor = position(2);
    list.remove(rows.get(1));

    var page = Pager.page(list, SCOPE, forward(anchor, 2));

    assertPage(page, true, true, "item-3", "item-4");
  }

  @Test
  @DisplayName("Should report no previous page when the removed position had nothing before it")
  void shouldReportNoPreviousPageWhenTheRemovedPositionHadNothingBeforeIt() {
    var anchor = position(1);
    list.remove(rows.get(0));

    var page = Pager.page(list, SCOPE, forward(anchor, 2));

    assertPage(page, false, true, "item-2", "item-3");
  }

  @Test
  @DisplayName("Should report a next page when a reverse request continues from a removed row")
  void shouldReportANextPageWhenAReverseRequestContinuesFromARemovedRow() {
    var anchor = position(4);
    list.remove(rows.get(3));

    var page = Pager.page(list, SCOPE, reverse(anchor, 2));

    assertPage(page, true, true, "item-2", "item-3");
  }

  @Test
  @DisplayName(
      "Should report no next page when a reverse request continues from the removed last row")
  void shouldReportNoNextPageWhenAReverseRequestContinuesFromTheRemovedLastRow() {
    var anchor = position(5);
    list.remove(rows.get(4));

    var page = Pager.page(list, SCOPE, reverse(anchor, 2));

    assertPage(page, true, false, "item-3", "item-4");
  }

  @Test
  @DisplayName("Should page each scope from its own position when scopes are paged together")
  void shouldPageEachScopeFromItsOwnPositionWhenScopesArePagedTogether() {
    var removedAnchor = position(4);
    list.remove(rows.get(3));
    var requests = new LinkedHashMap<String, PageRequest>();
    requests.put("first", forward(Optional.empty(), 2));
    requests.put("continued", forward(position(2), 2));
    requests.put("removed", reverse(removedAnchor, 2));

    var pages = Pager.pages(list, requests);

    assertThat(pages).containsOnlyKeys("first", "continued", "removed");
    assertPage(pages.get("first"), false, true, "item-1", "item-2");
    assertPage(pages.get("continued"), true, false, "item-3", "item-5");
    assertPage(pages.get("removed"), true, true, "item-2", "item-3");
  }

  @Test
  @DisplayName("Should report a next page without rows when the request asks for none")
  void shouldReportANextPageWithoutRowsWhenTheRequestAsksForNone() {
    var page = Pager.page(list, SCOPE, forward(Optional.empty(), 0));

    assertPage(page, false, true);
  }

  @Test
  @DisplayName("Should return an empty page when the list is empty")
  void shouldReturnAnEmptyPageWhenTheListIsEmpty() {
    var page = Pager.page(new FakePagedList(), SCOPE, forward(Optional.empty(), 2));

    assertPage(page, false, false);
  }

  @ParameterizedTest
  @MethodSource("valuesOutsideTheListOrder")
  @DisplayName("Should reject a position when its value does not fit the list's order")
  void shouldRejectAPositionWhenItsValueDoesNotFitTheListsOrder(SortValue value) {
    var request = forward(Optional.of(new Position(value, UUID.randomUUID())), 2);

    assertThatThrownBy(() -> Pager.page(list, SCOPE, request))
        .isInstanceOf(InvalidPaginationCursorException.class);
  }

  static Stream<SortValue> valuesOutsideTheListOrder() {
    return Stream.of(
        new SortValue.OfText("10"),
        new SortValue.OfInstant(Instant.EPOCH),
        new SortValue.OfDate(LocalDate.EPOCH),
        new SortValue.Absent());
  }

  private Optional<Position> position(int item) {
    return Optional.of(rows.get(item - 1).position());
  }

  private static PageRequest forward(Optional<Position> position, int limit) {
    return PageRequest.builder()
        .position(position)
        .direction(PaginationDirection.FORWARD)
        .limit(limit)
        .build();
  }

  private static PageRequest reverse(Optional<Position> position, int limit) {
    return PageRequest.builder()
        .position(position)
        .direction(PaginationDirection.REVERSE)
        .limit(limit)
        .build();
  }

  private static void assertPage(
      ListPage<String> page, boolean hasPreviousPage, boolean hasNextPage, String... items) {
    assertThat(page.rows()).extracting(Row::item).containsExactly(items);
    assertThat(page.hasPreviousPage()).as("hasPreviousPage").isEqualTo(hasPreviousPage);
    assertThat(page.hasNextPage()).as("hasNextPage").isEqualTo(hasNextPage);
  }
}
