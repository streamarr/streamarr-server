package com.streamarr.server.services.parsers.video;

import com.streamarr.server.services.parsers.MetadataParser;
import org.apache.commons.lang3.StringUtils;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

@Service
@Order(100)
public class DefaultVideoFileMetadataParser implements MetadataParser<VideoFileParserResult> {

  private static final List<Pattern> EXTRACTION_REGEXES =
      List.of(
          Pattern.compile(
                  "(.*[^_,.\\-])[_.()\\[\\]\\-](19[0-9]{2}|20[0-9]{2})(?![0-9]+|\\W[0-9]{2}\\W[0-9]{2})([ _,.()\\[\\]\\-][^0-9]|).*(19[0-9]{2}|20[0-9]{2})*"),
          Pattern.compile(
                  "(.*[^_,.\\-])[ _.()\\[\\]\\-]+(19[0-9]{2}|20[0-9]{2})(?![0-9]+|\\W[0-9]{2}\\W[0-9]{2})([ _,.()\\[\\]\\-][^0-9]|).*(19[0-9]{2}|20[0-9]{2})*"));
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

    for (var rx : EXTRACTION_REGEXES) {

      var matcher = rx.matcher(filename);

      if (!matcher.matches()) {
        continue;
      }

      var matchResult = matcher.toMatchResult();

      if (StringUtils.isBlank(matchResult.group(1))) {
        return Optional.empty();
      }

      return Optional.of(
          VideoFileParserResult.builder()
              .title(cleanTitle(matchResult.group(1)))
              .year(cleanYear(matchResult.group(2)))
              .build());
    }

    var cleanedInput = cleanTitle(filename);

    if (StringUtils.isBlank(cleanedInput)) {
      return Optional.empty();
    }

    return Optional.of(VideoFileParserResult.builder().title(cleanedInput).build());
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
    return title.replaceAll("(-$)", "");
  }

  private String cleanYear(String year) {
    return year.trim();
  }
}
