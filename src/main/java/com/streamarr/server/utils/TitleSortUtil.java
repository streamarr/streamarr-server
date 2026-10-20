package com.streamarr.server.utils;

import java.text.Normalizer;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

@UtilityClass
public class TitleSortUtil {

  private static final String[] ARTICLES = {"The ", "An ", "A "};

  // Only marks on a Latin base are removed; the rest recompose, so non-Latin titles keep theirs.
  private static final Pattern LATIN_COMBINING_MARKS = Pattern.compile("(\\p{IsLatin})\\p{M}+");

  private static final Pattern LEADING_ABOVE_Z = Pattern.compile("^[{|}~][{|}~\\s]*");

  /**
   * Encodes a display title as its stored sort title. Latin diacritics and compatibility characters
   * fold to their base letters, leading ASCII characters that sort above {@code z} under the {@code
   * C} collation are removed unless nothing else remains, and a leading article moves to the end.
   */
  public static String computeTitleSort(String title) {
    if (title == null) {
      return null;
    }

    var normalized = stripLeadingAboveZ(foldLatin(title));
    return stripLeadingAboveZ(moveLeadingArticle(normalized));
  }

  private static String foldLatin(String title) {
    var decomposed = Normalizer.normalize(title, Normalizer.Form.NFKD);
    var folded = LATIN_COMBINING_MARKS.matcher(decomposed).replaceAll("$1");
    return Normalizer.normalize(folded, Normalizer.Form.NFC);
  }

  private static String stripLeadingAboveZ(String title) {
    var stripped = LEADING_ABOVE_Z.matcher(title).replaceFirst("");
    if (stripped.isEmpty()) {
      return title;
    }

    return stripped;
  }

  private static String moveLeadingArticle(String title) {
    for (var article : ARTICLES) {
      if (title.length() > article.length() && title.startsWith(article)) {
        return title.substring(article.length()) + ", " + article.trim();
      }
    }

    return title;
  }
}
