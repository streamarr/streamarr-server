package com.streamarr.server.services.parsers.show.regex;

import lombok.Builder;

import java.util.regex.Pattern;

public sealed interface EpisodeRegexContainer {
    String expression();

    String exampleMatch();

    Pattern regex();

    record DateRegex(String expression, String exampleMatch, Pattern regex) implements EpisodeRegexContainer {
        @Builder
        public DateRegex {
        }

        public static DateRegexBuilder builder() {
            return new DateRegexBuilder() {
                @Override
                public DateRegex build() {
                    var obj = super.build();

                    return new DateRegex(obj.expression, obj.exampleMatch, Pattern.compile(obj.expression));
                }
            };
        }
    }

    record NamedGroupRegex(String expression, String exampleMatch, Pattern regex) implements EpisodeRegexContainer {
        @Builder
        public NamedGroupRegex {
        }

        public static NamedGroupRegexBuilder builder() {
            return new NamedGroupRegexBuilder() {
                @Override
                public NamedGroupRegex build() {
                    var obj = super.build();

                    return new NamedGroupRegex(obj.expression, obj.exampleMatch, Pattern.compile(obj.expression));
                }
            };
        }
    }

    record IndexedGroupRegex(String expression, String exampleMatch, Pattern regex) implements EpisodeRegexContainer {
        @Builder
        public IndexedGroupRegex {
        }

        public static IndexedGroupRegexBuilder builder() {
            return new IndexedGroupRegexBuilder() {
                @Override
                public IndexedGroupRegex build() {
                    var obj = super.build();

                    return new IndexedGroupRegex(obj.expression, obj.exampleMatch, Pattern.compile(obj.expression));
                }
            };
        }
    }
}
