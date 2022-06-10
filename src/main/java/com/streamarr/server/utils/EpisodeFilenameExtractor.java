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
        var optionalResult = episodeRegexConfig.getStandardRegexContainerList().stream()
            .map(regexContainer -> attemptMatch(input, regexContainer))
            .filter(episodePathResult -> episodePathResult.isPresent() && episodePathResult.get().isSuccess())
            .findFirst()
            .flatMap(i -> i);

        // TODO: Should we really always run this?...
        optionalResult.ifPresent(episodePathResult -> fillAdditional(input, episodePathResult));

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

            var episodeNumber = attemptEpisodeNumberExtractionFromNamedGroup(match);
            var seasonNumber = attemptSeasonNumberExtractionFromNamedGroup(match);
            var endingEpisodeNumber = attemptEndingEpisodeNumberExtraction(match);
            var seriesName = attemptSeriesNameExtraction(match);

            return Optional.of(EpisodePathResult.builder()
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .endingEpisodeNumber(endingEpisodeNumber)
                .seriesName(seriesName)
                .success(validationSuccess(episodeNumber, seasonNumber))
                .build());
        }

        var seasonNumber = attemptSeasonNumberExtractionFromGroupIndex(match);
        var episodeNumber = attemptEpisodeNumberExtractionFromGroupIndex(match);

        return Optional.of(EpisodePathResult.builder()
            .seasonNumber(seasonNumber)
            .episodeNumber(episodeNumber)
            .success(validationSuccess(episodeNumber, seasonNumber))
            .build());
    }

    private OptionalInt attemptSeasonNumberExtractionFromNamedGroup(Matcher match) {
        try {
            return getIntFromGroup(match, "seasonnumber");
        } catch (IllegalArgumentException ignored) {
            return OptionalInt.empty();
        }
    }

    private OptionalInt attemptEpisodeNumberExtractionFromNamedGroup(Matcher match) {
        try {
            return getIntFromGroup(match, "epnumber");
        } catch (IllegalArgumentException ignored) {
            return OptionalInt.empty();
        }
    }

    private OptionalInt attemptEndingEpisodeNumberExtraction(Matcher match) {
        try {
            var endingEpisodeNumber = getIntFromGroup(match, "endingepnumber");
            // Will only set EndingEpisodeNumber if the captured number is not followed by additional numbers
            // or a 'p' or 'i' as what you would get with a pixel resolution specification.
            // It avoids erroneous parsing of something like "series-s09e14-1080p.mkv" as a multi-episode from E14 to E108

            // TODO: implement this nasty stuff....
            if (true) {
                return endingEpisodeNumber;
            } else {
                return OptionalInt.empty();
            }
        } catch (IllegalArgumentException ignored) {
            return OptionalInt.empty();
        }
    }

    private String attemptSeriesNameExtraction(Matcher match) {
        try {
            var seriesName = match.group("seriesname");
            return StringUtils.isBlank(seriesName) ? null : seriesName;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private OptionalInt attemptSeasonNumberExtractionFromGroupIndex(Matcher match) {
        try {
            return getIntFromGroup(match, 1);
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    private OptionalInt attemptEpisodeNumberExtractionFromGroupIndex(Matcher match) {
        try {
            return getIntFromGroup(match, 2);
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    private OptionalInt getIntFromGroup(Matcher match, String groupName) {
        var result = match.group(groupName);
        if (StringUtils.isNotBlank(result)) {
            return attemptStringToIntConversion(result);
        }
        return OptionalInt.empty();
    }

    private OptionalInt getIntFromGroup(Matcher match, int groupIndex) {
        var result = match.group(groupIndex);
        if (StringUtils.isNotBlank(result)) {
            return attemptStringToIntConversion(result);
        }
        return OptionalInt.empty();
    }

    private OptionalInt attemptStringToIntConversion(String input) {
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
        for (var i : expressions) {
            var result = attemptMatch(path, i);

            if (result.isEmpty() || !result.get().isSuccess()) {
                continue;
            }

            if (StringUtils.isBlank(info.getSeriesName()) && StringUtils.isNotBlank(result.get().getSeriesName())) {
                info.setSeriesName(result.get().getSeriesName());
            }

            if (info.getEndingEpisodeNumber().isEmpty() && info.getEpisodeNumber().isPresent()) {
                info.setEndingEpisodeNumber(result.get().getEndingEpisodeNumber());
            }

            if (StringUtils.isNotBlank(info.getSeriesName())
                && (info.getEpisodeNumber().isEmpty() || info.getEndingEpisodeNumber().isPresent())) {
                break;
            }
        }
    }
}
