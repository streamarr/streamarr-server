package com.streamarr.server.utils;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;

import java.util.regex.Pattern;

@Getter
public class EpisodeRegexContainer {

    @NonNull
    private final String expression;
    private final Pattern regex;
    private final String exampleMatch;

    private final boolean isDateRegex;
    private final boolean isOptimistic;
    private final boolean isNamed;
    private final boolean supportsAbsoluteEpisodeNumbers;

    @Builder
    public EpisodeRegexContainer(@NonNull String expression,
                                 String exampleMatch,
                                 boolean isDateRegex,
                                 boolean isOptimistic,
                                 boolean isNamed,
                                 boolean supportsAbsoluteEpisodeNumbers) {
        this.expression = expression;
        this.exampleMatch = exampleMatch;
        this.isDateRegex = isDateRegex;
        this.isOptimistic = isOptimistic;
        this.isNamed = isNamed;
        this.supportsAbsoluteEpisodeNumbers = supportsAbsoluteEpisodeNumbers;

        this.regex = Pattern.compile(expression);
    }
}
