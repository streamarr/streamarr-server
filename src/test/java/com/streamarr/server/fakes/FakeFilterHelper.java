package com.streamarr.server.fakes;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.BaseAuditableEntity;
import com.streamarr.server.services.pagination.MediaFilter;
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
