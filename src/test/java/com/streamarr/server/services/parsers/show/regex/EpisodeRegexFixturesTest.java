package com.streamarr.server.services.parsers.show.regex;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;


@Tag("UnitTest")
@DisplayName("Episode Regex Fixtures Tests")
public class EpisodeRegexFixturesTest {

    private final EpisodeRegexFixtures episodeRegexFixtures = new EpisodeRegexFixtures();

    @TestFactory
    Stream<DynamicTest> dynamicTestsForStandardEpisodeRegex() {

        return episodeRegexFixtures.getStandardRegexContainerList().stream()
            .map(regexContainer -> DynamicTest.dynamicTest(
                "testing example: " + regexContainer.exampleMatch(),
                () -> {
                    assertTrue(regexContainer.regex().matcher(regexContainer.exampleMatch()).matches());
                }
            ));
    }
}
