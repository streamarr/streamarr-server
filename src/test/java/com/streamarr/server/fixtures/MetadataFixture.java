package com.streamarr.server.fixtures;

import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.events.ImageSource;
import com.streamarr.server.services.metadata.events.MetadataEnrichedEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MetadataFixture {

  private MetadataFixture() {}

  public static MetadataEnrichedEventBuilder metadataEnrichedEventBuilder() {
    return new MetadataEnrichedEventBuilder();
  }

  public static <T> MetadataResultBuilder<T> metadataResultBuilder() {
    return new MetadataResultBuilder<>();
  }

  public static final class MetadataEnrichedEventBuilder {

    private UUID entityId;
    private ImageEntityType entityType;
    private List<ImageSource> imageSources = List.of();
    private ImageRefreshMode imageRefreshMode = ImageRefreshMode.PRESERVE;

    private MetadataEnrichedEventBuilder() {}

    public MetadataEnrichedEventBuilder entityId(UUID entityId) {
      this.entityId = entityId;
      return this;
    }

    public MetadataEnrichedEventBuilder entityType(ImageEntityType entityType) {
      this.entityType = entityType;
      return this;
    }

    public MetadataEnrichedEventBuilder imageSources(List<ImageSource> imageSources) {
      this.imageSources = imageSources;
      return this;
    }

    public MetadataEnrichedEventBuilder imageRefreshMode(ImageRefreshMode imageRefreshMode) {
      this.imageRefreshMode = imageRefreshMode;
      return this;
    }

    public MetadataEnrichedEvent build() {
      return new MetadataEnrichedEvent(entityId, entityType, imageSources, imageRefreshMode);
    }
  }

  public static final class MetadataResultBuilder<T> {

    private T entity;
    private List<ImageSource> imageSources = List.of();
    private Map<String, List<ImageSource>> personImageSources = Map.of();
    private Map<String, List<ImageSource>> companyImageSources = Map.of();

    private MetadataResultBuilder() {}

    public MetadataResultBuilder<T> entity(T entity) {
      this.entity = entity;
      return this;
    }

    public MetadataResultBuilder<T> imageSources(List<ImageSource> imageSources) {
      this.imageSources = imageSources;
      return this;
    }

    public MetadataResultBuilder<T> personImageSources(
        Map<String, List<ImageSource>> personImageSources) {
      this.personImageSources = personImageSources;
      return this;
    }

    public MetadataResultBuilder<T> companyImageSources(
        Map<String, List<ImageSource>> companyImageSources) {
      this.companyImageSources = companyImageSources;
      return this;
    }

    public MetadataResult<T> build() {
      return new MetadataResult<>(entity, imageSources, personImageSources, companyImageSources);
    }
  }
}
