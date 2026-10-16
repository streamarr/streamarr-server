package com.streamarr.server.graphql.mutation.watchprogress;

import com.streamarr.server.domain.BaseCollectable;
import java.util.List;
import java.util.Optional;

public record MarkUnwatchedPayload(
    Optional<BaseCollectable<?>> item, List<MarkUnwatchedError> userErrors) {}
