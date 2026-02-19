package com.streamarr.server.services.parsers;

import java.util.regex.Pattern;

public final class ParserPatterns {

  private ParserPatterns() {}

  public static final Pattern EXTERNAL_ID_TAG =
      Pattern.compile("[\\[\\{(](?i)(?<source>imdb|tmdb|tvdb)(?:id)?[ \\-=](?<id>.+?)[\\]\\})]");
}
