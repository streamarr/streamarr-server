package com.streamarr.server.services.parsers.show.regex;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Getter
public class EpisodeRegexFixtures {

    private final List<EpisodeRegexContainer> standardRegexContainerList = new ArrayList<>(List.of(
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Extracts seriesName, seasonNumber, episodeNumber -> "/foo.s01.e01"
            // TODO: Currently requires path... Do we want this?
            .expression(".*(\\\\|\\/)(?<seriesname>((?![Ss]([0-9]+)[ ._-]*[Ee]([0-9]+))[^\\\\\\/])*)?[Ss](?<seasonnumber>[0-9]+)[ ._-]*[Ee](?<epnumber>[0-9]+)([^\\\\/]*)$")
            .exampleMatch("/foo.s01.e01")
            .build(),
        EpisodeRegexContainer.IndexedGroupRegex.builder()
            // Extracts just an episode number. Ex -> "foo.ep01", "foo.EP_01"
            .expression(".*[\\._ -]()[Ee][Pp]_?([0-9]+)([^\\\\/]*)$")
            .exampleMatch("foo.ep01")
            .build(),
        EpisodeRegexContainer.IndexedGroupRegex.builder()
            // Extracts just an episode number. Ex -> "foo.E01.", "foo.e01."
            .expression("[^\\\\/]*?()\\.?[Ee]([0-9]+)([^\\\\/]*)$")
            .exampleMatch("foo.e01")
            .build(),
        EpisodeRegexContainer.DateRegex.builder()
            // Extracts date from filename. Ex -> "PBS NewsHour 2020-04-17"
            .expression(".*(?<year>[0-9]{4})[\\\\.-](?<month>[0-9]{2})[\\\\.-](?<day>[0-9]{2})")
            .exampleMatch("PBS NewsHour 2020-04-17")
            .build(),
        EpisodeRegexContainer.DateRegex.builder()
            // Extracts date from filename. Ex -> "PBS NewsHour 17-04-2020"
            .expression(".*(?<day>[0-9]{2})[.-](?<month>[0-9]{2})[.-](?<year>[0-9]{4})")
            .exampleMatch("PBS NewsHour 17-04-2020")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Extracts seriesName, episodeNumber, and optionally endingEpisodeNumber. Ex -> "/Season 1/foo 03", "/Season 1/foo 03-06"
            // TODO: Currently requires path... Do we want this?
            .expression(".*[\\\\\\/](?![Ee]pisode)(?<seriesname>[\\w\\s]+?)\\s(?<epnumber>[0-9]{1,3})(-(?<endingepnumber>[0-9]{2,3}))*[^\\\\\\/x]*$")
            .exampleMatch("/Season 1/name episode 03-06")
            .build(),
        EpisodeRegexContainer.IndexedGroupRegex.builder()
            // Extracts episodeNumber, seasonNumber. Ex -> "foo 2x2", "/Season 2/Elementary - 02x03 - 02x04 - 02x15 - Ep Name"
            // TODO: Currently requires path... Do we want this?
            .expression("^.*?[\\\\\\/\\._ \\[\\(-]([0-9]+)x([0-9]+(?:(?:[a-i]|\\.[1-9])(?![0-9]))?)([^\\\\\\/]*)$")
            .exampleMatch("foo 02x03")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Warning; Causes false positives for triple-digit episode names
            // Extracts seriesName, episodeNumber. Ex -> "[tag] Foo - 1"
            .expression(".*[\\\\\\/]?.*?(\\[.*?\\])+.*?(?<seriesname>[-\\w\\s]+?)[\\s_]*-[\\s_]*(?<epnumber>[0-9]+).*$")
            .exampleMatch("[tag] Foo - 1")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // /server/anything_102.mp4
            // /server/james.corden.2017.04.20.anne.hathaway.720p.hdtv.x264-crooks.mkv
            // /server/anything_1996.11.14.mp4
            .expression("^.*?(?!.*\\/)(?<seriesname>(?![0-9]+[0-9][0-9])([^\\\\\\\\\\\\/_])*)[\\\\\\\\\\\\/._ -](?<seasonnumber>[0-9]+)(?<epnumber>[0-9][0-9](?:(?:[a-i]|\\\\.[1-9])(?![0-9]))?)([._ -][^\\\\\\/]*)?$")
            .exampleMatch("/server/anything_102")
            .build(),
        // TODO: Is this covered in unit tests or actually used?
        EpisodeRegexContainer.IndexedGroupRegex.builder()
            // Maybe extracts part number. Ex -> "/season 1/title_part_1.avi"
            // TODO: Currently requires path... Do we want this?
            .expression(".*[\\\\/._ -]p(?:ar)?t[_. -]()([ivx]+|[0-9]+)([._ -][^\\\\/]*)$")
            .exampleMatch("/season 1/title_part_1.avi")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Extracts episodeNumber and optionally endingEpisodeNumber. Ex -> "Episode 16", "Episode 16 - Title"
            .expression("[Ee]pisode (?<epnumber>[0-9]+)(-(?<endingepnumber>[0-9]+))?[^\\\\\\/]*$")
            .exampleMatch("Episode 16")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Extracts seasonNumber and episodeNumber. Ex -> "/season 1/2x1 foo"
            // TODO: Currently requires path... Do we want this?
            .expression(".*(\\\\|\\/)[sS]?(?<seasonnumber>[0-9]+)[xX](?<epnumber>[0-9]+)[^\\\\\\/]*$")
            .exampleMatch("/season 1/2x1 foo")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Extracts seasonNumber and episodeNumber. Ex -> "/season 1/s2xe1 foo"
            // TODO: Currently requires path... Do we want this?
            .expression(".*(\\\\|\\/)[sS](?<seasonnumber>[0-9]+)[x,X]?[eE](?<epnumber>[0-9]+)[^\\\\\\/]*$")
            .exampleMatch("/season 1/s2xe1 foo")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Extracts seasonNumber and episodeNumber. Ex -> "/season 01/02 episode title"
            .expression(".*[Ss]eason[\\._ ](?<seasonnumber>[0-9]+)[\\\\\\/](?<epnumber>[0-9]{1,3})([^\\\\\\/]*)$")
            .exampleMatch("/season 01/02 episode title")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Extracts seriesName, seasonNumber, and episodeNumber. Ex -> "/server/the_simpsons-s02x01_18536"
            // TODO: Currently requires path... Do we want this?
            .expression(".*(\\\\|\\/)(?<seriesname>((?![sS]?[0-9]{1,4}[xX][0-9]{1,3})[^\\\\\\/])*)?([sS]?(?<seasonnumber>[0-9]{1,4})[xX](?<epnumber>[0-9]+))[^\\\\\\/]*$")
            .exampleMatch("/server/the_simpsons-s02x01_18536.mp4")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Extracts seriesName, seasonNumber, and episodeNumber. Ex -> "/server/the_simpsons-s02e01_18536"
            // TODO: Currently requires path... Do we want this?
            .expression(".*(\\\\|\\/)(?<seriesname>[^\\\\\\/]*)[sS](?<seasonnumber>[0-9]{1,4})[xX\\.]?[eE](?<epnumber>[0-9]+)[^\\\\\\/]*$")
            .exampleMatch("/server/the_simpsons-s02e01_18536.mp4")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Extracts episodeNumber and optionally endingEpisodeNumber. Ex -> "/01-03.avi", "/01.avi"
            // TODO: Currently requires path AND extension... Do we want this?
            .expression(".*[\\\\\\/](?<epnumber>[0-9]+)(-(?<endingepnumber>[0-9]+))*\\.\\w+$")
            .exampleMatch("/01.avi")
            .build(),
        EpisodeRegexContainer.IndexedGroupRegex.builder()
            // Extracts seasonNumber and episodeNumber. Ex -> "1-12 episode title, 1-12.avi"
            .expression(".*([0-9]+)-([0-9]+).*$")
            .exampleMatch("1-12 episode title")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Extracts episodeNumber and optionally endingEpisodeNumber. Ex -> "/01 - blah", "/01-blah", "/01-02 - blah"
            // TODO: Currently requires path... Do we want this?
            .expression(".*(\\\\|\\/)(?<epnumber>[0-9]{1,3})(-(?<endingepnumber>[0-9]{2,3}))*\\s?-\\s?[^\\\\\\/]*$")
            .exampleMatch("/01 - blah")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Extracts episodeNumber and optionally endingEpisodeNumber. Ex -> "/01.blah", "/01-02.blah"
            // TODO: Currently requires path... Do we want this?
            .expression(".*(\\\\|\\/)(?<epnumber>[0-9]{1,3})(-(?<endingepnumber>[0-9]{2,3}))*\\.[^\\\\\\/]+$")
            .exampleMatch("/01.blah")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Extracts episodeNumber and optionally endingEpisodeNumber. Ex -> "/blah - 01", "/blah 2 - 01", "/blah 2 - 01-02"
            // TODO: Currently requires path... Do we want this?
            .expression(".*[\\\\\\/][^\\\\\\/]* - (?<epnumber>[0-9]{1,3})(-(?<endingepnumber>[0-9]{2,3}))*[^\\\\\\/]*$")
            .exampleMatch("/blah - 01")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Extracts seriesName and seasonNumber. Ex -> "the show/season 1", "the show/s01"
            .expression("(.*(\\\\|\\/))*(?<seriesname>.+)\\/[Ss](eason)?[\\. _\\-]*(?<seasonnumber>[0-9]+)")
            .exampleMatch("the show/season 1")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            // Extracts seriesName and seasonNumber. Ex -> "the show S01", "the show season 1"
            .expression("(.*(\\\\|\\/))*(?<seriesname>.+)[\\. _\\-]+[sS](eason)?[\\. _\\-]*(?<seasonnumber>[0-9]+)")
            .exampleMatch("the show S01")
            .build()
    ));

    // TODO: Do we care about case sensitivity here?
    private final List<EpisodeRegexContainer> multipleEpisodeRegexContainerList = new ArrayList<>(List.of(
        EpisodeRegexContainer.NamedGroupRegex.builder()
            .expression(".*(\\\\|\\/)[sS]?(?<seasonnumber>[0-9]{1,4})[xX](?<epnumber>[0-9]{1,3})((-| - )[0-9]{1,4}[eExX](?<endingepnumber>[0-9]{1,3}))+[^\\\\\\/]*$")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            .expression(".*(\\\\|\\/)[sS]?(?<seasonnumber>[0-9]{1,4})[xX](?<epnumber>[0-9]{1,3})((-| - )[0-9]{1,4}[xX][eE](?<endingepnumber>[0-9]{1,3}))+[^\\\\\\/]*$")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            .expression(".*(\\\\|\\/)[sS]?(?<seasonnumber>[0-9]{1,4})[xX](?<epnumber>[0-9]{1,3})((-| - )?[xXeE](?<endingepnumber>[0-9]{1,3}))+[^\\\\\\/]*$")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            .expression(".*(\\\\|\\/)[sS]?(?<seasonnumber>[0-9]{1,4})[xX](?<epnumber>[0-9]{1,3})(-[xE]?[eE]?(?<endingepnumber>[0-9]{1,3}))+[^\\\\\\/]*$")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            .expression(".*(\\\\|\\/)(?<seriesname>((?![sS]?[0-9]{1,4}[xX][0-9]{1,3})[^\\\\\\/])*)?([sS]?(?<seasonnumber>[0-9]{1,4})[xX](?<epnumber>[0-9]{1,3}))((-| - )[0-9]{1,4}[xXeE](?<endingepnumber>[0-9]{1,3}))+[^\\\\\\/]*$")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            .expression(".*(\\\\|\\/)(?<seriesname>((?![sS]?[0-9]{1,4}[xX][0-9]{1,3})[^\\\\\\/])*)?([sS]?(?<seasonnumber>[0-9]{1,4})[xX](?<epnumber>[0-9]{1,3}))((-| - )[0-9]{1,4}[xX][eE](?<endingepnumber>[0-9]{1,3}))+[^\\\\\\/]*$")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            .expression(".*(\\\\|\\/)(?<seriesname>((?![sS]?[0-9]{1,4}[xX][0-9]{1,3})[^\\\\\\/])*)?([sS]?(?<seasonnumber>[0-9]{1,4})[xX](?<epnumber>[0-9]{1,3}))((-| - )?[xXeE](?<endingepnumber>[0-9]{1,3}))+[^\\\\\\/]*$")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            .expression(".*(\\\\|\\/)(?<seriesname>((?![sS]?[0-9]{1,4}[xX][0-9]{1,3})[^\\\\\\/])*)?([sS]?(?<seasonnumber>[0-9]{1,4})[xX](?<epnumber>[0-9]{1,3}))(-[xX]?[eE]?(?<endingepnumber>[0-9]{1,3}))+[^\\\\\\/]*$")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            .expression(".*(\\\\|\\/)(?<seriesname>[^\\\\\\/]*)[sS](?<seasonnumber>[0-9]{1,4})[xX\\.]?[eE](?<epnumber>[0-9]{1,3})((-| - )?[xXeE](?<endingepnumber>[0-9]{1,3}))+[^\\\\\\/]*$")
            .build(),
        EpisodeRegexContainer.NamedGroupRegex.builder()
            .expression(".*(\\\\|\\/)(?<seriesname>[^\\\\\\/]*)[sS](?<seasonnumber>[0-9]{1,4})[xX\\.]?[eE](?<epnumber>[0-9]{1,3})(-[xX]?[eE]?(?<endingepnumber>[0-9]{1,3}))+[^\\\\\\/]*$")
            .build()
    ));
}
