package com.streamarr.server.services.extraction.video;

import com.streamarr.server.services.extraction.MediaExtractor;
import io.micrometer.core.instrument.util.StringUtils;
import lombok.Builder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class VideoFilenameExtractionService implements MediaExtractor<VideoFilenameExtractionService.Result> {

    // TODO: Rename
    public record Result(String title, String year) {
        @Builder
        public Result {
        }
    }

    // TODO: We should also DI these regex patterns
    private final static List<Pattern> EXTRACTION_REGEXES = List.of(
        Pattern.compile("(.*[^_\\,\\.\\-])[_\\.\\(\\)\\[\\]\\-](19[0-9]{2}|20[0-9]{2})(?![0-9]+|\\W[0-9]{2}\\W[0-9]{2})([ _\\,\\.\\(\\)\\[\\]\\-][^0-9]|).*(19[0-9]{2}|20[0-9]{2})*"),
        Pattern.compile("(.*[^_\\,\\.\\-])[ _\\.\\(\\)\\[\\]\\-]+(19[0-9]{2}|20[0-9]{2})(?![0-9]+|\\W[0-9]{2}\\W[0-9]{2})([ _\\,\\.\\(\\)\\[\\]\\-][^0-9]|).*(19[0-9]{2}|20[0-9]{2})*")
    );
    private final static Pattern TAG_REGEX = Pattern.compile("([\\[\\(].*[\\]\\)]]*)");
    private final static Pattern KNOWN_WORD_EXCLUSIONS_REGEX = Pattern.compile("[ _\\,\\.\\(\\)\\[\\]\\-](3d|sbs|tab|hsbs|htab|mvc|HDR|HDC|UHD|UltraHD|4k|ac3|dts|custom|dc|divx|divx5|dsr|dsrip|dutch|dvd|dvdrip|dvdscr|dvdscreener|screener|dvdivx|cam|fragment|fs|hdtv|hdrip|hdtvrip|internal|limited|multisubs|ntsc|ogg|ogm|pal|pdtv|proper|repack|rerip|retail|cd[1-9]|r3|r5|bd5|bd|se|svcd|swedish|german|read.nfo|nfofix|unrated|ws|telesync|ts|telecine|tc|brrip|bdrip|480p|480i|576p|576i|720p|720i|1080p|1080i|2160p|hrhd|hrhdtv|hddvd|bluray|blu-ray|x264|x265|h264|xvid|xvidvd|xxx|www.www|AAC|DTS|\\[.*\\])([ _\\,\\.\\(\\)\\[\\]\\-]|$)", Pattern.CASE_INSENSITIVE);

    public Optional<Result> extract(String input) {

        if (StringUtils.isBlank(input)) {
            return Optional.empty();
        }

        for (var rx : EXTRACTION_REGEXES) {

            var matcher = rx.matcher(input);

            if (!matcher.matches()) {
                continue;
            }

            var matchResult = matcher.toMatchResult();

            return Optional.of(Result.builder()
                .title(cleanTitle(matchResult.group(1)))
                .year(cleanYear(matchResult.group(2)))
                .build());
        }

        return Optional.of(Result.builder()
            .title(cleanTitle(input))
            .build());
    }

    private String cleanTitle(String rawTitle) {
        var cleanTitle = rawTitle;

        cleanTitle = removeExclusions(cleanTitle);

        cleanTitle = removeTags(cleanTitle);

        return cleanTitle.trim();
    }

    private String cleanYear(String year) {
        return year.trim();
    }

    private String removeTags(String title) {
        var tagMatcher = TAG_REGEX.matcher(title);

        return tagMatcher.replaceAll("");
    }

    private String removeExclusions(String title) {
        var exclusionMatcher = KNOWN_WORD_EXCLUSIONS_REGEX.matcher(title);

        if (!exclusionMatcher.find()) {
            return title;
        }

        return title.substring(0, exclusionMatcher.start());
    }
}
