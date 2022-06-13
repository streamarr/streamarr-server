package com.streamarr.server.utils;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;

@Component
@RequiredArgsConstructor
public class EpisodeFilenameExtractor {

    private final EpisodeRegexConfig episodeRegexConfig;

    public Optional<EpisodePathResult> extract(String filename) {
        var optionalResult = episodeRegexConfig.getStandardRegexContainerList().stream()
            .map(regexContainer -> attemptMatch(filename, regexContainer))
            .filter(episodePathResult -> episodePathResult.isPresent() && episodePathResult.get().isSuccess())
            .findFirst()
            .flatMap(i -> i);

        if (optionalResult.isEmpty()) {
            return optionalResult;
        }

        var episodePathResult = optionalResult.get();

        if (!isMissingInfo(episodePathResult)) {
            return optionalResult;
        }

        return Optional.of(fillAdditionalInfo(filename, episodePathResult));
    }

    private Optional<EpisodePathResult> attemptMatch(String filename, EpisodeRegexContainer regexContainer) {
        // This is a hack to handle wmc naming
        if (regexContainer.isDateRegex()) {
            filename = filename.replace('_', '-');
        }

        var match = regexContainer.getRegex().matcher(filename);

        if (!match.matches()) {
            return Optional.empty();
        }

        if (regexContainer.isDateRegex()) {

            // TODO: Replace with getIntFromGroup()?
            var year = Integer.parseInt(match.group("year"));
            var month = Integer.parseInt(match.group("month"));
            var day = Integer.parseInt(match.group("day"));

            var parsedDate = LocalDate.of(year, month, day);

            return Optional.of(EpisodePathResult.builder()
                .date(parsedDate)
                .success(true)
                .onlyDate(true)
                .build());
        }

        if (regexContainer.isNamed()) {
            var episodeNumber = getIntFromGroup(match, "epnumber");
            var seasonNumber = getIntFromGroup(match, "seasonnumber");
            var endingEpisodeNumber = getAndValidateEndingEpisodeNumber(filename, match);
            var seriesName = getSeriesName(match);

            return Optional.of(EpisodePathResult.builder()
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .endingEpisodeNumber(endingEpisodeNumber)
                .seriesName(seriesName)
                .success(isValidResult(episodeNumber, seasonNumber))
                .build());
        }

        var seasonNumber = getIntFromGroup(match, 1);
        var episodeNumber = getIntFromGroup(match, 2);

        return Optional.of(EpisodePathResult.builder()
            .seasonNumber(seasonNumber)
            .episodeNumber(episodeNumber)
            .success(isValidResult(episodeNumber, seasonNumber))
            .build());
    }

    private OptionalInt getAndValidateEndingEpisodeNumber(String filename, Matcher match) {
        var endingEpisodeNumber = getIntFromGroup(match, "endingepnumber");

        if (endingEpisodeNumber.isEmpty()) {
            return OptionalInt.empty();
        }

        var endingNumberGroupEndIndex = match.end("endingepnumber");

        // Will only return value if the captured number is not followed by additional numbers
        // or a 'p' or 'i' as what one would get with a pixel resolution specification.
        // It avoids erroneous parsing of something like "series-s09e14-1080p.mkv" as a multi-episode from E14 to E108.
        if (endingNumberGroupEndIndex >= filename.length() || !containsChar("0123456789iIpP", filename.charAt(endingNumberGroupEndIndex))) {
            return endingEpisodeNumber;
        }

        return OptionalInt.empty();
    }

    public boolean containsChar(String s, char search) {
        if (s.length() == 0)
            return false;
        else
            return s.charAt(0) == search || containsChar(s.substring(1), search);
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
        return episodeNumber.isPresent() && (seasonNumber.isEmpty() || isValidSeasonNumber(seasonNumber.getAsInt()));
    }

    // Invalidate the match when the season is 200 through 1927 or above 2500
    // It avoids erroneous parsing of something like "Series Special (1920x1080).mkv" as being season 1920, episode 1080.
    private boolean isValidSeasonNumber(int seasonNumber) {
        return (seasonNumber < 200 || seasonNumber >= 1928) && seasonNumber <= 2500;
    }

    private boolean isMissingInfo(EpisodePathResult result) {
        return StringUtils.isBlank(result.getSeriesName()) || (result.getEpisodeNumber().isPresent() && result.getEndingEpisodeNumber().isEmpty());
    }

    private String cleanSeriesName(String input) {
        return input.trim()
            .replaceAll("[_.-]*$", "")
            .trim();
    }

    private EpisodePathResult fillAdditionalInfo(String filename, EpisodePathResult result) {

        var multipleEpisodeRegexContainerSet = episodeRegexConfig.getMultipleEpisodeRegexContainerList();

        if (StringUtils.isBlank(result.getSeriesName())) {
            multipleEpisodeRegexContainerSet.addAll(0, episodeRegexConfig.getStandardRegexContainerList().stream()
                .filter(EpisodeRegexContainer::isNamed)
                .toList());
        }

        return fillAdditionalInfo(filename, result, multipleEpisodeRegexContainerSet);
    }

    private EpisodePathResult fillAdditionalInfo(String filename, EpisodePathResult result, List<EpisodeRegexContainer> expressions) {
        EpisodePathResult.EpisodePathResultBuilder builder = result.toBuilder();

        for (var i : expressions) {
            var newResult = attemptMatch(filename, i);

            if (newResult.isEmpty() || !newResult.get().isSuccess()) {
                continue;
            }
            
            if (StringUtils.isBlank(result.getSeriesName()) && StringUtils.isNotBlank(newResult.get().getSeriesName())) {
                builder.seriesName(newResult.get().getSeriesName());
            }

            if (result.getEndingEpisodeNumber().isEmpty() && result.getEpisodeNumber().isPresent()) {
                builder.endingEpisodeNumber(newResult.get().getEndingEpisodeNumber());
            }

            if (isDoneFillingInfo(result)) {
                break;
            }
        }

        return builder.build();
    }

    private boolean isDoneFillingInfo(EpisodePathResult result) {
        return StringUtils.isNotBlank(result.getSeriesName()) && (result.getEpisodeNumber().isEmpty() || result.getEndingEpisodeNumber().isPresent());
    }
}
