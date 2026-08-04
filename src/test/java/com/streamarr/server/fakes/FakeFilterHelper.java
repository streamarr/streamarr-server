package com.streamarr.server.fakes;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.services.pagination.MediaFilter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.experimental.UtilityClass;
import org.jooq.SortOrder;

@UtilityClass
public class FakeFilterHelper {

  public boolean matchesLetterEquality(String title, AlphabetLetter letter) {
    if (title == null || title.isEmpty()) {
      return false;
    }
    var firstChar = Character.toLowerCase(title.charAt(0));
    if (letter == AlphabetLetter.HASH) {
      return firstChar < 'a' || firstChar > 'z';
    }
    return firstChar == Character.toLowerCase(letter.name().charAt(0));
  }

  public boolean matchesLetterAscRange(String title, AlphabetLetter letter) {
    if (letter == AlphabetLetter.HASH) {
      return true;
    }
    if (title == null || title.isEmpty()) {
      return false;
    }
    var firstChar = Character.toLowerCase(title.charAt(0));
    return firstChar >= Character.toLowerCase(letter.name().charAt(0));
  }

  public boolean matchesLetterDescRange(String title, AlphabetLetter letter) {
    if (letter == AlphabetLetter.Z) {
      return true;
    }
    if (title == null || title.isEmpty()) {
      return false;
    }
    var firstChar = Character.toLowerCase(title.charAt(0));
    if (letter == AlphabetLetter.HASH) {
      return firstChar < 'a' || firstChar > 'z';
    }
    return firstChar <= Character.toLowerCase(letter.name().charAt(0));
  }

  // True when the title sits above a TITLE-sort letter jump's landing page - the negation of the
  // landing range. HASH never has rows above it: it lands at the top under ASC, and under DESC it
  // matches two non-adjacent runs of the ordering so it has no single anchor to sit below.
  public boolean isAboveLetterAnchor(String title, AlphabetLetter letter, SortOrder direction) {
    if (letter == AlphabetLetter.HASH || title == null || title.isEmpty()) {
      return false;
    }
    if (direction == SortOrder.DESC) {
      return !matchesLetterDescRange(title, letter);
    }
    return !matchesLetterAscRange(title, letter);
  }

  public Comparator<String> titleSortComparator(SortOrder direction) {
    var comparator = String.CASE_INSENSITIVE_ORDER;
    return direction == SortOrder.DESC
        ? Comparator.nullsFirst(comparator.reversed())
        : Comparator.nullsLast(comparator);
  }

  public MediaFilter reverseFilter(MediaFilter filter) {
    if (filter.getSortDirection().equals(SortOrder.DESC)) {
      return filter.toBuilder().sortDirection(SortOrder.ASC).build();
    }

    return filter.toBuilder().sortDirection(SortOrder.DESC).build();
  }

  public int findCursorIndex(
      List<? extends BaseAuditableEntity<?>> sorted, Optional<UUID> cursorId) {
    if (cursorId.isEmpty()) {
      return 0;
    }

    var id = cursorId.get();
    for (int i = 0; i < sorted.size(); i++) {
      if (sorted.get(i).getId().equals(id)) {
        return i;
      }
    }

    return 0;
  }
}
