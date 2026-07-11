package com.streamarr.server.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class TitleSortUtil {

  private static final String[] ARTICLES = {"The ", "An ", "A "};

  public static String computeTitleSort(String title) {
    if (title == null) {
      return null;
    }

    for (var article : ARTICLES) {
      if (title.length() > article.length() && title.startsWith(article)) {
        return title.substring(article.length()) + ", " + article.trim();
      }
    }

    return title;
  }
}
