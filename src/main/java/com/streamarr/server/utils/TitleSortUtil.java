package com.streamarr.server.utils;

import java.lang.Character.UnicodeScript;
import java.text.Normalizer;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

@UtilityClass
public class TitleSortUtil {

  private static final String[] ARTICLES = {"The ", "An ", "A "};

  private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

  // Every symbol class except currency (Sc): a title can spell a letter with a currency sign, as
  // "$" stands for "S", and stripping it would file the title under the wrong letter.
  private static final Pattern LEADING_DECORATION =
      Pattern.compile("^[\\p{P}\\p{Sm}\\p{Sk}\\p{So}\\p{Z}\\p{C}\\p{M}]+");

  /**
   * Encodes a display title as its stored sort title. Latin diacritics and compatibility characters
   * fold to their base letters; leading punctuation, symbols other than currency signs, and
   * invisible characters are removed unless nothing else remains; and a leading article moves to
   * the end. An article is recognized as written, so a first word that only folds to one, such as
   * the French preposition "À", stays in place.
   */
  public static String computeTitleSort(String title) {
    if (title == null) {
      return null;
    }

    var withArticleMoved = moveLeadingArticle(stripLeadingDecoration(title));
    return stripLeadingDecoration(foldLatin(withArticleMoved));
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

  private static String stripLeadingDecoration(String title) {
    var stripped = LEADING_DECORATION.matcher(title).replaceFirst("");
    if (stripped.isEmpty()) {
      return title;
    }

    return stripped;
  }

  private static String moveLeadingArticle(String title) {
    for (var article : ARTICLES) {
      if (title.startsWith(article) && hasLetterOrDigit(title.substring(article.length()))) {
        return title.substring(article.length()) + ", " + article.trim();
      }
    }

    return title;
  }

  private static boolean hasLetterOrDigit(String text) {
    return text.codePoints().anyMatch(Character::isLetterOrDigit);
  }
}
