package com.streamarr.server.services.parsers.video;

import com.streamarr.server.services.parsers.MetadataParser;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

@Service
@Order(100)
public class DefaultVideoFileMetadataParser implements MetadataParser<VideoFileParserResult> {

  private static final String TRAILING_SYMBOLS = "-–—";
  private static final String PREFERRED_YEAR_SEPARATORS = "_.()[]-";
  private static final String SUPPORTED_YEAR_SEPARATORS = " _.()[]-";
  private static final String INVALID_TITLE_ENDINGS = "_,.-";
  private static final Pattern YEAR_REGEX = Pattern.compile("(?:19|20)\\d{2}");
  private static final Pattern INVALID_YEAR_SUFFIX_REGEX =
      Pattern.compile("\\d|[xX]\\d{3}|\\W\\d{2}\\W\\d{2}");
  private static final Pattern TAG_REGEX =
      Pattern.compile("^\\s*\\[[^]]+](?!\\.\\w+$)\\s*(?<cleaned>.+)");
  private static final Pattern KNOWN_WORD_EXCLUSIONS_REGEX =
      Pattern.compile(
          "[ _,.()\\[\\]\\-](3d|sbs|tab|hsbs|htab|mvc|HDR|HDC|UHD|UltraHD|4k|ac3|dts|custom|dc|divx|divx5|dsr|dsrip|dutch|dvd|dvdrip|dvdscr|dvdscreener|screener|dvdivx|cam|fragment|fs|hdtv|hdrip|hdtvrip|internal|limited|multisubs|ntsc|ogg|ogm|pal|pdtv|proper|repack|rerip|retail|cd[1-9]|r3|r5|bd5|bd|se|svcd|swedish|german|read.nfo|nfofix|unrated|ws|telesync|ts|telecine|tc|brrip|bdrip|480p|480i|576p|576i|720p|720i|1080p|1080i|2160p|hrhd|hrhdtv|hddvd|bluray|blu-ray|x264|x265|h264|xvid|xvidvd|xxx|www.www|AAC|\\[.*])([ _,.()\\[\\]\\-]|$)",
          Pattern.CASE_INSENSITIVE);

  public Optional<VideoFileParserResult> parse(String filename) {

    if (StringUtils.isBlank(filename)) {
      return Optional.empty();
    }

    var extractedMetadata = extractTitleAndYear(filename);

    if (extractedMetadata.isPresent()) {
      var metadata = extractedMetadata.orElseThrow();

      if (StringUtils.isBlank(metadata.rawTitle())) {
        return Optional.empty();
      }

      return Optional.of(
          VideoFileParserResult.builder()
              .title(cleanTitle(metadata.rawTitle()))
              .year(cleanYear(metadata.year()))
              .build());
    }

    var cleanedInput = cleanTitle(filename);

    if (StringUtils.isBlank(cleanedInput)) {
      return Optional.empty();
    }

    return Optional.of(VideoFileParserResult.builder().title(cleanedInput).build());
  }

  private Optional<ExtractedMetadata> extractTitleAndYear(String filename) {
    for (var separatorMode : YearSeparatorMode.values()) {
      var extractedMetadata = findLastYear(filename, separatorMode);

      if (extractedMetadata.isPresent()) {
        return extractedMetadata;
      }
    }

    return Optional.empty();
  }

  private Optional<ExtractedMetadata> findLastYear(
      String filename, YearSeparatorMode separatorMode) {
    var yearMatcher = YEAR_REGEX.matcher(filename);
    ExtractedMetadata lastMatch = null;

    while (yearMatcher.find()) {
      if (hasInvalidYearSuffix(filename, yearMatcher.end())) {
        continue;
      }

      var rawTitle = rawTitleBeforeYear(filename.substring(0, yearMatcher.start()), separatorMode);

      if (rawTitle.isPresent()) {
        lastMatch = new ExtractedMetadata(rawTitle.orElseThrow(), yearMatcher.group());
      }
    }

    return Optional.ofNullable(lastMatch);
  }

  private Optional<String> rawTitleBeforeYear(String value, YearSeparatorMode separatorMode) {
    return switch (separatorMode) {
      case PREFERRED -> rawTitleBeforePreferredSeparator(value);
      case SUPPORTED -> rawTitleBeforeSupportedSeparator(value);
    };
  }

  private Optional<String> rawTitleBeforePreferredSeparator(String value) {
    var separatorIndex = value.length() - 1;

    if (separatorIndex <= 0 || !contains(PREFERRED_YEAR_SEPARATORS, value.charAt(separatorIndex))) {
      return Optional.empty();
    }

    if (contains(INVALID_TITLE_ENDINGS, value.charAt(separatorIndex - 1))) {
      return Optional.empty();
    }

    return Optional.of(value.substring(0, separatorIndex));
  }

  private Optional<String> rawTitleBeforeSupportedSeparator(String value) {
    var separatorIndex = value.length() - 1;

    if (separatorIndex <= 0 || !contains(SUPPORTED_YEAR_SEPARATORS, value.charAt(separatorIndex))) {
      return Optional.empty();
    }

    while (separatorIndex > 0
        && contains(INVALID_TITLE_ENDINGS, value.charAt(separatorIndex - 1))) {
      if (!contains(SUPPORTED_YEAR_SEPARATORS, value.charAt(separatorIndex - 1))) {
        return Optional.empty();
      }

      separatorIndex--;
    }

    if (separatorIndex == 0) {
      return Optional.empty();
    }

    return Optional.of(value.substring(0, separatorIndex));
  }

  private boolean contains(String characters, char candidate) {
    return characters.indexOf(candidate) >= 0;
  }

  private boolean hasInvalidYearSuffix(String filename, int yearEnd) {
    return INVALID_YEAR_SUFFIX_REGEX.matcher(filename.substring(yearEnd)).lookingAt();
  }

  private String cleanTitle(String rawTitle) {
    var cleanTitle = rawTitle.trim();

    cleanTitle = removeExclusions(cleanTitle);
    cleanTitle = removeTags(cleanTitle);
    cleanTitle = removeTrailingSymbols(cleanTitle);

    return cleanTitle.trim();
  }

  private String removeExclusions(String title) {
    var exclusionMatcher = KNOWN_WORD_EXCLUSIONS_REGEX.matcher(title);

    if (!exclusionMatcher.find()) {
      return title;
    }

    return title.substring(0, exclusionMatcher.start());
  }

  private String removeTags(String title) {
    var tagMatcher = TAG_REGEX.matcher(title);

    if (!tagMatcher.matches()) {
      return title;
    }

    return tagMatcher.group("cleaned");
  }

  private String removeTrailingSymbols(String title) {
    var end = title.length();

    while (end > 0 && TRAILING_SYMBOLS.indexOf(title.charAt(end - 1)) >= 0) {
      end--;
    }

    return title.substring(0, end);
  }

  private String cleanYear(String year) {
    return year.trim();
  }

  private record ExtractedMetadata(String rawTitle, String year) {}

  private enum YearSeparatorMode {
    PREFERRED,
    SUPPORTED
  }
}
