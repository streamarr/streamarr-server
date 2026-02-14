package com.streamarr.server.services.parsers.show;

import static com.streamarr.server.services.parsers.ParserPatterns.EXTERNAL_ID_TAG;

import com.streamarr.server.domain.ExternalSourceType;
import com.streamarr.server.services.parsers.video.VideoFileParserResult;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class SeriesFolderNameParser {

  private static final Pattern YEAR_SUFFIX = Pattern.compile("\\s*+\\((?<year>\\d{4}+)\\)");

  private static final Pattern COUNTRY_SUFFIX = Pattern.compile("\\s*+\\([A-Za-z]{2}+\\)");

  public VideoFileParserResult parse(String folderName) {
    var builder = VideoFileParserResult.builder();
    var remaining = folderName;

    var externalIdMatcher = EXTERNAL_ID_TAG.matcher(remaining);
    if (externalIdMatcher.find()) {
      builder
          .externalId(externalIdMatcher.group("id"))
          .externalSource(
              ExternalSourceType.valueOf(externalIdMatcher.group("source").toUpperCase()));
      remaining = remaining.substring(0, externalIdMatcher.start()).trim();
    }

    var yearMatcher = YEAR_SUFFIX.matcher(remaining);
    if (yearMatcher.find()) {
      builder.year(yearMatcher.group("year"));
      remaining = remaining.substring(0, yearMatcher.start()).trim();
    }

    var countryMatcher = COUNTRY_SUFFIX.matcher(remaining);
    if (countryMatcher.find()) {
      remaining = remaining.substring(0, countryMatcher.start()).trim();
    }

    builder.title(remaining.trim());
    return builder.build();
  }
}
