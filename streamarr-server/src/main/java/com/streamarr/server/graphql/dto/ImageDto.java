package com.streamarr.server.graphql.dto;

import com.streamarr.server.domain.media.ImageType;
import java.util.List;

public record ImageDto(
    ImageType imageType, String blurHash, float aspectRatio, List<ImageVariantDto> variants) {}
