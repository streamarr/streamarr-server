package com.streamarr.server.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.services.pagination.ListPage;
import com.streamarr.server.services.pagination.PageRequest;
import com.streamarr.server.services.pagination.PaginationDirection;
import com.streamarr.server.services.pagination.Position;
import com.streamarr.server.services.pagination.Row;
import com.streamarr.server.services.pagination.SortDirection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The paging behavior every cursor-paginated list shares, proven against PostgreSQL. A list's IT
 * extends this and says how to add and remove its items; the contract derives the expected order
 * from the ranks it asks for.
 *
 * @param <T> the listed item
 * @param <S> the scope the list is read in
 */
public abstract class PagedListContract<T, S> extends AbstractIntegrationTest {

  /** Equal ranks share one sort value, so their order falls to item ID. */
  private static final List<Integer> RANKS = List.of(0, 1, 1, 1, 2, 3, 3);

  private S scope;

  /** A new, empty scope for one test. */
  protected abstract S createScope();

  protected void deleteScope(S scope) {}

  /**
   * Adds an item that sorts after every item of a lower rank, and shares its sort value with the
   * items of an equal rank.
   */
  protected abstract UUID add(S scope, int rank);

  /** Takes the item out of the list, the way the product does. */
  protected abstract void remove(S scope, UUID item);

  protected abstract ListPage<T> page(S scope, PageRequest request);

  protected abstract UUID idOf(T item);

  /** The direction the list orders item IDs in when sort values tie. */
  protected abstract SortDirection tieDirection();

  /**
   * A position that sorts before every arranged item and after every other item of the list. A list
   * whose scope holds only the arranged items, such as one Profile's list, pages from its start
   * instead; a server-wide list names a position here so other rows cannot interleave.
   */
  protected Optional<Position> positionBeforeArrangedItems(S scope) {
    return Optional.empty();
  }

  /** A position that sorts after every arranged item; see {@link #positionBeforeArrangedItems}. */
  protected Optional<Position> positionAfterArrangedItems(S scope) {
    return Optional.empty();
  }

  @BeforeEach
  void createContractScope() {
    scope = createScope();
  }

  @AfterEach
  void deleteContractScope() {
    deleteScope(scope);
  }

  @Test
  @DisplayName("Should visit every item once in list order when paging forward")
  void shouldVisitEveryItemOnceInListOrderWhenPagingForward() {
    var expected = arrange();
    var start = positionBeforeArrangedItems(scope);
    var visited = new ArrayList<UUID>();
    var page = page(scope, request(start, PaginationDirection.FORWARD));
    if (start.isEmpty()) {
      assertThat(page.hasPreviousPage()).as("first page hasPreviousPage").isFalse();
    }

    visited.addAll(ids(page));
    while (visited.size() < expected.size()) {
      assertThat(page.hasNextPage()).as("a page before the last arranged item").isTrue();
      assertThat(page.rows()).as("a page that has a next page").isNotEmpty();
      page = page(scope, request(lastPosition(page), PaginationDirection.FORWARD));
      assertThat(page.hasPreviousPage()).as("continued page hasPreviousPage").isTrue();
      visited.addAll(ids(page));
    }

    assertThat(visited.subList(0, expected.size())).isEqualTo(expected);
    if (positionAfterArrangedItems(scope).isEmpty()) {
      assertThat(visited).hasSameSizeAs(expected);
      assertThat(page.hasNextPage()).as("last page hasNextPage").isFalse();
    }
  }

  @Test
  @DisplayName("Should visit every item once in list order when paging backward")
  void shouldVisitEveryItemOnceInListOrderWhenPagingBackward() {
    var expected = arrange();
    var start = positionAfterArrangedItems(scope);
    var visited = new ArrayList<UUID>();
    var page = page(scope, request(start, PaginationDirection.REVERSE));
    if (start.isEmpty()) {
      assertThat(page.hasNextPage()).as("last page hasNextPage").isFalse();
    }

    visited.addAll(0, ids(page));
    while (visited.size() < expected.size()) {
      assertThat(page.hasPreviousPage()).as("a page after the first arranged item").isTrue();
      assertThat(page.rows()).as("a page that has a previous page").isNotEmpty();
      page =
          page(
              scope,
              request(Optional.of(page.rows().getFirst().position()), PaginationDirection.REVERSE));
      assertThat(page.hasNextPage()).as("continued page hasNextPage").isTrue();
      visited.addAll(0, ids(page));
    }

    assertThat(visited.subList(visited.size() - expected.size(), visited.size()))
        .isEqualTo(expected);
    if (positionBeforeArrangedItems(scope).isEmpty()) {
      assertThat(visited).hasSameSizeAs(expected);
      assertThat(page.hasPreviousPage()).as("first page hasPreviousPage").isFalse();
    }
  }

  @Test
  @DisplayName("Should continue after an item's position when the item left the list")
  void shouldContinueAfterAnItemsPositionWhenTheItemLeftTheList() {
    var expected = arrange();
    var first =
        page(
            scope,
            PageRequest.builder()
                .position(positionBeforeArrangedItems(scope))
                .direction(PaginationDirection.FORWARD)
                .limit(3)
                .build());
    remove(scope, expected.get(2));

    var next = page(scope, request(lastPosition(first), PaginationDirection.FORWARD));

    assertThat(ids(first)).isEqualTo(expected.subList(0, 3));
    assertThat(ids(next)).isEqualTo(expected.subList(3, 5));
    assertThat(next.hasPreviousPage()).isTrue();
    assertThat(next.hasNextPage()).isTrue();
  }

  @Test
  @DisplayName("Should report no previous page when the only item before a position left the list")
  void shouldReportNoPreviousPageWhenTheOnlyItemBeforeAPositionLeftTheList() {
    assumeTrue(holdsOnlyArrangedItems(), "the list holds rows other than the arranged items");
    var expected = arrange();
    var firstOnly =
        page(
            scope,
            PageRequest.builder()
                .position(Optional.empty())
                .direction(PaginationDirection.FORWARD)
                .limit(1)
                .build());
    remove(scope, expected.getFirst());

    var next = page(scope, request(lastPosition(firstOnly), PaginationDirection.FORWARD));

    assertThat(ids(firstOnly)).containsExactly(expected.getFirst());
    assertThat(ids(next)).isEqualTo(expected.subList(1, 3));
    assertThat(next.hasPreviousPage()).isFalse();
  }

  @Test
  @DisplayName("Should return an empty page without neighbours when the list is empty")
  void shouldReturnAnEmptyPageWithoutNeighboursWhenTheListIsEmpty() {
    assumeTrue(holdsOnlyArrangedItems(), "the list holds rows other than the arranged items");
    var page = page(scope, request(Optional.empty(), PaginationDirection.FORWARD));

    assertThat(page.rows()).isEmpty();
    assertThat(page.hasNextPage()).isFalse();
    assertThat(page.hasPreviousPage()).isFalse();
  }

  private boolean holdsOnlyArrangedItems() {
    return positionBeforeArrangedItems(scope).isEmpty()
        && positionAfterArrangedItems(scope).isEmpty();
  }

  private List<UUID> arrange() {
    var rankById =
        IntStream.range(0, RANKS.size())
            .boxed()
            .collect(Collectors.toMap(index -> add(scope, RANKS.get(index)), RANKS::get));
    return rankById.keySet().stream().sorted(listOrder(rankById)).toList();
  }

  // PostgreSQL orders uuid values byte by byte, which matches their text order.
  private Comparator<UUID> listOrder(Map<UUID, Integer> rankById) {
    Comparator<UUID> ties = Comparator.comparing(UUID::toString);
    if (tieDirection() == SortDirection.DESC) {
      ties = ties.reversed();
    }

    return Comparator.<UUID, Integer>comparing(rankById::get).thenComparing(ties);
  }

  private List<UUID> ids(ListPage<T> page) {
    return page.rows().stream().map(Row::item).map(this::idOf).toList();
  }

  private static Optional<Position> lastPosition(ListPage<?> page) {
    return Optional.of(page.rows().getLast().position());
  }

  private static PageRequest request(Optional<Position> position, PaginationDirection direction) {
    return PageRequest.builder().position(position).direction(direction).limit(2).build();
  }
}
