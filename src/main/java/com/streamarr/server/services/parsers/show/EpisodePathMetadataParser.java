package com.streamarr.server.services.parsers.show;

import com.streamarr.server.services.parsers.MetadataParser;
import com.streamarr.server.services.parsers.show.regex.EpisodeRegexContainer;
import com.streamarr.server.services.parsers.show.regex.EpisodeRegexFixtures;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Order(0)
public class EpisodePathMetadataParser implements MetadataParser<EpisodePathResult> {

  private final EpisodeRegexFixtures episodeRegexFixtures;

  public Optional<EpisodePathResult> parse(String filename) {
    var optionalResult =
        episodeRegexFixtures.getStandardRegexContainerList().stream()
            .map(regexContainer -> attemptMatch(filename, regexContainer))
            .filter(
                episodePathResult ->
                    episodePathResult.isPresent() && episodePathResult.get().isSuccess())
            .findFirst()
            .flatMap(i -> i);

    if (optionalResult.isEmpty()) {
      return optionalResult;
    }

    var episodePathResult = optionalResult.get();

    if (isNotMissingInfo(episodePathResult)) {
      return optionalResult;
    }

    return Optional.of(fillAdditionalInfo(filename, episodePathResult));
  }

  private Optional<EpisodePathResult> attemptMatch(
      String filename, EpisodeRegexContainer regexContainer) {
    return switch (regexContainer) {
      case EpisodeRegexContainer.DateRegex d -> attemptDateMatch(d.regex(), filename);
      case EpisodeRegexContainer.NamedGroupRegex d -> attemptNamedMatch(d.regex(), filename);
      case EpisodeRegexContainer.IndexedGroupRegex d -> attemptIndexedMatch(d.regex(), filename);
    };
  }

  private Optional<EpisodePathResult> attemptDateMatch(Pattern pattern, String filename) {
    // This is a hack to handle wmc naming
    filename = filename.replace('_', '-');

    var match = pattern.matcher(filename);

    if (!match.matches()) {
      return Optional.empty();
    }

    var year = Integer.parseInt(match.group("year"));
    var month = Integer.parseInt(match.group("month"));
    var day = Integer.parseInt(match.group("day"));

    var parsedDate = LocalDate.of(year, month, day);

    return Optional.of(
        EpisodePathResult.builder().date(parsedDate).success(true).onlyDate(true).build());
  }

  private Optional<EpisodePathResult> attemptNamedMatch(Pattern pattern, String filename) {
    var match = pattern.matcher(filename);

    if (!match.matches()) {
      return Optional.empty();
    }

    var episodeNumber = getIntFromGroup(match, "epnumber");
    var seasonNumber = getIntFromGroup(match, "seasonnumber");
    var endingEpisodeNumber = getAndValidateEndingEpisodeNumber(filename, episodeNumber, match);
    var seriesName = getSeriesName(match);

    return Optional.of(
        EpisodePathResult.builder()
            .seasonNumber(seasonNumber)
            .episodeNumber(episodeNumber)
            .endingEpisodeNumber(endingEpisodeNumber)
            .seriesName(seriesName)
            .success(isValidResult(episodeNumber, seasonNumber))
            .build());
  }

  private Optional<EpisodePathResult> attemptIndexedMatch(Pattern pattern, String filename) {
    var match = pattern.matcher(filename);

    if (!match.matches()) {
      return Optional.empty();
    }

    var seasonNumber = getIntFromGroup(match, 1);
    var episodeNumber = getIntFromGroup(match, 2);

    return Optional.of(
        EpisodePathResult.builder()
            .seasonNumber(seasonNumber)
            .episodeNumber(episodeNumber)
            .success(isValidResult(episodeNumber, seasonNumber))
            .build());
  }

  private OptionalInt getAndValidateEndingEpisodeNumber(
      String filename, OptionalInt episodeNumber, Matcher match) {
    var endingEpisodeNumber = getIntFromGroup(match, "endingepnumber");

    if (endingEpisodeNumber.isEmpty()) {
      return endingEpisodeNumber;
    }

    if (isDuplicateEpisodeAndEndingEpisode(episodeNumber, endingEpisodeNumber)) {
      return OptionalInt.empty();
    }

    var endingNumberGroupEndIndex = match.end("endingepnumber");

    // Will only return value if the captured number is not followed by additional numbers
    // or a 'p' or 'i' as what one would get with a pixel resolution specification.
    // It avoids erroneous parsing of something like "series-s09e14-1080p.mkv" as a multi-episode
    // from E14 to E108.
    if (endingNumberGroupEndIndex >= filename.length()
        || !containsChar("0123456789iIpP", filename.charAt(endingNumberGroupEndIndex))) {
      return endingEpisodeNumber;
    }

    return OptionalInt.empty();
  }

  private boolean containsChar(String s, char search) {
    if (s.length() == 0) return false;
    else return s.charAt(0) == search || containsChar(s.substring(1), search);
  }

  private String getSeriesName(Matcher match) {
    try {
      var seriesName = match.group("seriesname");
      return StringUtils.isBlank(seriesName) ? null : cleanSeriesName(seriesName);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private OptionalInt getIntFromGroup(Matcher match, String groupName) {
    try {
      return stringToOptionalIntConverter(match.group(groupName));
    } catch (IllegalArgumentException ignored) {
      return OptionalInt.empty();
    }
  }

  private OptionalInt getIntFromGroup(Matcher match, int groupIndex) {
    try {
      return stringToOptionalIntConverter(match.group(groupIndex));
    } catch (IndexOutOfBoundsException ignored) {
      return OptionalInt.empty();
    }
  }

  private OptionalInt stringToOptionalIntConverter(String input) {
    if (StringUtils.isBlank(input)) {
      return OptionalInt.empty();
    }

    return tryStringToOptionalIntConversion(input);
  }

  private OptionalInt tryStringToOptionalIntConversion(String input) {
    try {
      return OptionalInt.of(Integer.parseInt(input));
    } catch (NumberFormatException ignore) {
      return OptionalInt.empty();
    }
  }

  private boolean isValidResult(OptionalInt episodeNumber, OptionalInt seasonNumber) {
    return episodeNumber.isPresent()
        && (seasonNumber.isEmpty() || isValidSeasonNumber(seasonNumber.getAsInt()));
  }

  // Invalidate the match when the season is 200 through 1927 or above 2500
  // It avoids erroneous parsing of something like "Series Special (1920x1080).mkv" as being season
  // 1920, episode 1080.
  private boolean isValidSeasonNumber(int seasonNumber) {
    return (seasonNumber < 200 || seasonNumber >= 1928) && seasonNumber <= 2500;
  }

  private String cleanSeriesName(String input) {
    return input.trim().replaceAll("[_.-]*$", "").trim();
  }

  private EpisodePathResult fillAdditionalInfo(String filename, EpisodePathResult result) {

    var multipleEpisodeRegexContainerSet =
        episodeRegexFixtures.getMultipleEpisodeRegexContainerList();

    if (StringUtils.isBlank(result.getSeriesName())) {
      multipleEpisodeRegexContainerSet.addAll(
          0,
          episodeRegexFixtures.getStandardRegexContainerList().stream()
              .filter(EpisodeRegexContainer.NamedGroupRegex.class::isInstance)
              .toList());
    }

    return fillAdditionalInfo(filename, result, multipleEpisodeRegexContainerSet);
  }

  private EpisodePathResult fillAdditionalInfo(
      String filename, EpisodePathResult result, List<EpisodeRegexContainer> expressions) {
    EpisodePathResult.EpisodePathResultBuilder builder = result.toBuilder();

    for (var expression : expressions) {
      var newResultOptional = attemptMatch(filename, expression);

      if (newResultOptional.isEmpty() || !newResultOptional.get().isSuccess()) {
        continue;
      }

      var newResult = newResultOptional.get();

      if (StringUtils.isBlank(result.getSeriesName())
          && StringUtils.isNotBlank(newResult.getSeriesName())) {
        builder.seriesName(newResult.getSeriesName());
      }

      if (isDuplicateEpisodeAndEndingEpisode(
          result.getEpisodeNumber(), newResult.getEndingEpisodeNumber())) {
        continue;
      }

      if (result.getEndingEpisodeNumber().isEmpty() && result.getEpisodeNumber().isPresent()) {
        builder.endingEpisodeNumber(newResult.getEndingEpisodeNumber());
      }

      if (isNotMissingInfo(result)) {
        break;
      }
    }

    return builder.build();
  }

  private boolean isDuplicateEpisodeAndEndingEpisode(
      OptionalInt episodeNumber, OptionalInt endingEpisodeNumber) {
    return (episodeNumber.isPresent() && endingEpisodeNumber.isPresent())
        && (episodeNumber.getAsInt() == endingEpisodeNumber.getAsInt());
  }

  private boolean isNotMissingInfo(EpisodePathResult result) {
    return StringUtils.isNotBlank(result.getSeriesName())
        && result.getEndingEpisodeNumber().isPresent();
  }
}
