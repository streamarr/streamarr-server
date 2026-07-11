package com.streamarr.server.services.metadata;

import com.streamarr.server.services.metadata.events.ImageSource;
import java.util.List;
import java.util.Map;

public record MetadataResult<T>(
    T entity,
    List<ImageSource> imageSources,
    Map<String, List<ImageSource>> personImageSources,
    Map<String, List<ImageSource>> companyImageSources) {}
