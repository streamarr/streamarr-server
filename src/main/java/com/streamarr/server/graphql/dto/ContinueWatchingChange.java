package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.BaseCollectable;
import java.util.UUID;
import lombok.NonNull;

public record ContinueWatchingChange(@NonNull UUID itemId, OrderedEdge<BaseCollectable<?>> edge) {}
