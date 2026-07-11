package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.media.ImageSize;
import java.util.UUID;

public record ImageVariantDto(UUID id, ImageSize size, int width, int height, String url) {}
