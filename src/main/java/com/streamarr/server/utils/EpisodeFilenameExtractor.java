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

        // TODO: look for other regex expressions that rely on partial match...
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

            // TODO: what if we don't have a season number? Is that possible?
            // TODO: helper?
            var success = episodeNumber.isPresent() && (seasonNumber.isEmpty() || isValidSeasonNumber(seasonNumber.getAsInt()));

            return Optional.of(EpisodePathResult.builder()
                .seasonNumber(seasonNumber)
                .episodeNumber(episodeNumber)
                .endingEpisodeNumber(endingEpisodeNumber)
                .seriesName(seriesName)
                .success(success)
                .build());
        }

        var seasonNumber = attemptSeasonNumberExtractionFromGroupIndex(match);
        var episodeNumber = attemptEpisodeNumberExtractionFromGroupIndex(match);

        // TODO: what if we don't have a season number? Is that possible?
        // TODO: helper?
        var success = episodeNumber.isPresent() && (seasonNumber.isEmpty() || isValidSeasonNumber(seasonNumber.getAsInt()));

        return Optional.of(EpisodePathResult.builder()
            .seasonNumber(seasonNumber)
            .episodeNumber(episodeNumber)
            .success(success)
            .build());
    }

    private OptionalInt attemptSeasonNumberExtractionFromNamedGroup(Matcher match) {
        try {
            var seasonNumber = match.group("seasonnumber");
            if (StringUtils.isNotBlank(seasonNumber)) {
                return OptionalInt.of(Integer.parseInt(seasonNumber));
            }
        } catch (IllegalArgumentException ignored) {
            return OptionalInt.empty();
        }

        return OptionalInt.empty();
    }

    private OptionalInt attemptEpisodeNumberExtractionFromNamedGroup(Matcher match) {
        try {
            var episodeNumber = match.group("epnumber");
            if (StringUtils.isNotBlank(episodeNumber)) {
                return OptionalInt.of(Integer.parseInt(episodeNumber));
            }
        } catch (IllegalArgumentException ignored) {
            return OptionalInt.empty();
        }

        return OptionalInt.empty();
    }

    private OptionalInt attemptEndingEpisodeNumberExtraction(Matcher match) {
        try {
            var endingEpisodeNumber = match.group("endingepnumber");
            if (StringUtils.isNotBlank(endingEpisodeNumber)) {
                // Will only set EndingEpisodeNumber if the captured number is not followed by additional numbers
                // or a 'p' or 'i' as what you would get with a pixel resolution specification.
                // It avoids erroneous parsing of something like "series-s09e14-1080p.mkv" as a multi-episode from E14 to E108

                var endingEpisodeNumberInt = Integer.parseInt(endingEpisodeNumber);

                // TODO: implement this nasty stuff....
                if (true) {
                    return OptionalInt.of(endingEpisodeNumberInt);
                }
            }
        } catch (IllegalArgumentException ignored) {
            return OptionalInt.empty();
        }

        return OptionalInt.empty();
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
        var seasonNumber = match.group(1);
        if (StringUtils.isNotBlank(seasonNumber)) {
            try {
                return OptionalInt.of(Integer.parseInt(seasonNumber));
            } catch (NumberFormatException ignored) {
                return OptionalInt.empty();
            }
        }

        return OptionalInt.empty();
    }

    private OptionalInt attemptEpisodeNumberExtractionFromGroupIndex(Matcher match) {
        var episodeNumber = match.group(2);
        if (StringUtils.isNotBlank(episodeNumber)) {
            try {
                return OptionalInt.of(Integer.parseInt(episodeNumber));
            } catch (NumberFormatException ignored) {
                return OptionalInt.empty();
            }
        }

        return OptionalInt.empty();
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
