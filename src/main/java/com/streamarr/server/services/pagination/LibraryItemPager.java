package com.streamarr.server.services.pagination;

import com.streamarr.server.domain.AlphabetLetter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Pages a Library's items. A first page under TITLE sort with a start letter lands at the letter:
 * it reads from just before the letter's first title, so the titles before the letter count as a
 * previous page.
 */
public final class LibraryItemPager {

  private static final UUID LOWEST_ID = new UUID(0, 0);

  private LibraryItemPager() {}

  public static <T> ListPage<T> page(
      PagedList<T, MediaFilter> list, MediaFilter filter, PageRequest request) {
    var scope = filter.listScope();
    var landing = landing(filter, request);
    if (landing.isEmpty()) {
      return Pager.page(list, scope, request);
    }

    var landed = Pager.page(list, scope, positioned(request, landing.get()));
    // Descending, # lands on the titles below "a". Without any, every # title sorts above "z",
    // at the top of the list.
    if (landed.rows().isEmpty() && isDescendingHash(filter)) {
      return Pager.page(list, scope, request);
    }

    return landed;
  }

  // Ascending # and descending Z start the list, so they land at its first page.
  private static Optional<Position> landing(MediaFilter filter, PageRequest request) {
    var letter = filter.getStartLetter();
    if (request.position().isPresent()
        || letter == null
        || filter.getSortBy() != OrderMediaBy.TITLE) {
      return Optional.empty();
    }

    if (filter.getSortDirection() == SortDirection.ASC) {
      return letter == AlphabetLetter.HASH ? Optional.empty() : before(letter);
    }

    return switch (letter) {
      case Z -> Optional.empty();
      case HASH -> before(AlphabetLetter.A);
      default -> before(AlphabetLetter.values()[letter.ordinal() + 1]);
    };
  }

  // Titles sort lowercased, so a letter's titles all sort at or after its lowercase letter.
  private static Optional<Position> before(AlphabetLetter letter) {
    var value = new SortValue.OfText(letter.name().toLowerCase(Locale.ROOT));
    return Optional.of(new Position(value, LOWEST_ID));
  }

  private static boolean isDescendingHash(MediaFilter filter) {
    return filter.getStartLetter() == AlphabetLetter.HASH
        && filter.getSortDirection() == SortDirection.DESC;
  }

  private static PageRequest positioned(PageRequest request, Position position) {
    return PageRequest.builder()
        .position(Optional.of(position))
        .direction(request.direction())
        .limit(request.limit())
        .build();
  }
}
