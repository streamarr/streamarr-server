package com.streamarr.server.fakes;

import com.streamarr.server.domain.AlphabetLetter;
import lombok.experimental.UtilityClass;

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

  // PostgreSQL lowercases only ASCII letters under the C collation titles sort by.
  public String titleSortValue(String titleSort) {
    if (titleSort == null) {
      return null;
    }

    var lowered = new StringBuilder(titleSort.length());
    titleSort
        .chars()
        .forEach(c -> lowered.append((char) (c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c)));
    return lowered.toString();
  }
}
