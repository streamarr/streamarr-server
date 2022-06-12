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

    public Optional<EpisodePathResult> extract(String input) {
        // TODO: Can any of this be generic and reused with fillAdditional?
        var optionalResult = episodeRegexConfig.getStandardRegexContainerList().stream()
            .map(regexContainer -> attemptMatch(input, regexContainer))
            .filter(episodePathResult -> episodePathResult.isPresent() && episodePathResult.get().isSuccess())
            .findFirst()
            .flatMap(i -> i);

        if (optionalResult.isPresent() && (StringUtils.isBlank(optionalResult.get().getSeriesName()) && (optionalResult.get().getEpisodeNumber().isPresent() || optionalResult.get().getEndingEpisodeNumber().isEmpty()))) {
            fillAdditional(input, optionalResult.get());
        }

        // TODO: clean this up, make it immutable?
        if (optionalResult.isPresent() && StringUtils.isNotBlank(optionalResult.get().getSeriesName())) {
            var cleanedName = cleanSeriesName(optionalResult.get().getSeriesName());

            optionalResult.get().setSeriesName(cleanedName);
        }

        return optionalResult;
    }

    private Optional<EpisodePathResult> attemptMatch(String name, EpisodeRegexContainer regexContainer) {
        // This is a hack to handle wmc naming
        if (regexContainer.isDateRegex()) {
            name = name.replace('_', '-');
        }

        var match = regexContainer.getRegex().matcher(name);

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
            var endingEpisodeNumber = getAndValidateEndingEpisodeNumber(name, match);
            var seriesName = extractSeriesName(match);

            return Optional.of(EpisodePathResult.builder()
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .endingEpisodeNumber(endingEpisodeNumber)
                .seriesName(seriesName)
                .success(validationSuccess(episodeNumber, seasonNumber))
                .build());
        }

        var seasonNumber = getIntFromGroup(match, 1);
        var episodeNumber = getIntFromGroup(match, 2);

        return Optional.of(EpisodePathResult.builder()
            .seasonNumber(seasonNumber)
            .episodeNumber(episodeNumber)
            .success(validationSuccess(episodeNumber, seasonNumber))
            .build());
    }

    private OptionalInt getAndValidateEndingEpisodeNumber(String name, Matcher match) {
        var endingEpisodeNumber = getIntFromGroup(match, "endingepnumber");

        if (endingEpisodeNumber.isEmpty()) {
            return OptionalInt.empty();
        }

        var endingNumberGroupEndIndex = match.end("endingepnumber");

        // Will only set EndingEpisodeNumber if the captured number is not followed by additional numbers
        // or a 'p' or 'i' as what you would get with a pixel resolution specification.
        // It avoids erroneous parsing of something like "series-s09e14-1080p.mkv" as a multi-episode from E14 to E108
        if (endingNumberGroupEndIndex >= name.length() || !containsChar("0123456789iIpP", name.charAt(endingNumberGroupEndIndex))) {
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

    private String extractSeriesName(Matcher match) {
        try {
            var seriesName = match.group("seriesname");
            return StringUtils.isBlank(seriesName) ? null : seriesName;
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
        } catch (IllegalArgumentException ignored) {
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

    private boolean validationSuccess(OptionalInt episodeNumber, OptionalInt seasonNumber) {
        return episodeNumber.isPresent() && (seasonNumber.isEmpty() || isValidSeasonNumber(seasonNumber.getAsInt()));
    }

    // Invalidate match when the season is 200 through 1927 or above 2500
    // because it is an error unless the TV show is intentionally using false season numbers.
    // It avoids erroneous parsing of something like "Series Special (1920x1080).mkv" as being season 1920 episode 1080.
    private boolean isValidSeasonNumber(int seasonNumber) {
        return (seasonNumber < 200 || seasonNumber >= 1928) && seasonNumber <= 2500;
    }

    private String cleanSeriesName(String input) {
        return input.trim()
            .replaceAll("[_.-]*$", "")
            .trim();
    }

    // TODO: Rename, terrible
    private void fillAdditional(String path, EpisodePathResult result) {

        var multipleEpisodeRegexContainerSet = episodeRegexConfig.getMultipleEpisodeRegexContainerList();

        if (StringUtils.isBlank(result.getSeriesName())) {
            multipleEpisodeRegexContainerSet.addAll(0, episodeRegexConfig.getStandardRegexContainerList().stream()
                .filter(EpisodeRegexContainer::isNamed)
                .toList());
        }

        fillAdditional(path, result, multipleEpisodeRegexContainerSet);
    }

    private void fillAdditional(String path, EpisodePathResult info, List<EpisodeRegexContainer> expressions) {
        expressions.stream()
            .map(regexContainer -> attemptMatch(path, regexContainer))
            .filter(episodePathResult -> episodePathResult.isPresent() && episodePathResult.get().isSuccess())
            .forEach(result -> {
                if (StringUtils.isBlank(info.getSeriesName()) && StringUtils.isNotBlank(result.get().getSeriesName())) {
                    info.setSeriesName(result.get().getSeriesName());
                }

                if (info.getEndingEpisodeNumber().isEmpty() && info.getEpisodeNumber().isPresent()) {
                    info.setEndingEpisodeNumber(result.get().getEndingEpisodeNumber());
                }
            });
    }
}
