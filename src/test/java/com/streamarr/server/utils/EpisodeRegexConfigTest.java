package com.streamarr.server.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;


@Tag("UnitTest")
@DisplayName("Episode Regex Config Tests")
public class EpisodeRegexConfigTest {

    private final EpisodeRegexConfig episodeRegexConfig = new EpisodeRegexConfig();

    @TestFactory
    Stream<DynamicTest> dynamicTestsForStandardEpisodeRegex() {

        return episodeRegexConfig.getStandardRegexContainerList().stream()
            .map(regexContainer -> DynamicTest.dynamicTest(
                "testing example: " + regexContainer.exampleMatch(),
                () -> {
                    assertTrue(regexContainer.regex().matcher(regexContainer.exampleMatch()).matches());
                }
            ));
    }
}
