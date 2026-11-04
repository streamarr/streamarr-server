package com.streamarr.server.graphql.mutation.language;

import com.streamarr.server.domain.BaseCollectable;
import java.util.List;
import java.util.Optional;

/** The payload of setLanguageOverride and of clearLanguageOverride. */
public record LanguageOverridePayload(
    Optional<BaseCollectable<?>> item, List<LanguageOverrideError> userErrors) {}
