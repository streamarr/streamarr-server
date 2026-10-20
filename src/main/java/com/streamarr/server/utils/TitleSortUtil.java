package com.streamarr.server.utils;

import java.lang.Character.UnicodeScript;
import java.text.Normalizer;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

@UtilityClass
public class TitleSortUtil {

  private static final String[] ARTICLES = {"The ", "An ", "A "};

  private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

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

  // Folds code point by code point: a compatibility mapping applied to the whole title would
  // also rewrite non-Latin text, such as half-width kana and Thai vowel signs.
  private static String foldLatin(String title) {
    var folded = new StringBuilder(title.length());
    var baseScript = UnicodeScript.UNKNOWN;
    for (var codePoint : title.codePoints().toArray()) {
      var script = UnicodeScript.of(codePoint);
      if (script != UnicodeScript.INHERITED) {
        baseScript = script;
      }

      folded.append(foldCodePoint(codePoint, baseScript));
    }

    return folded.toString();
  }

  private static String foldCodePoint(int codePoint, UnicodeScript baseScript) {
    var character = Character.toString(codePoint);
    if (baseScript != UnicodeScript.LATIN) {
      return character;
    }

    var decomposed = Normalizer.normalize(character, Normalizer.Form.NFKD);
    return COMBINING_MARKS.matcher(decomposed).replaceAll("");
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
