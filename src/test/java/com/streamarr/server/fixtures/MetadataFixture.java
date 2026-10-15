package com.streamarr.server.fixtures;

import com.streamarr.server.services.metadata.MetadataFetchOutcome;
import com.streamarr.server.services.metadata.MetadataResult;
import com.streamarr.server.services.metadata.events.ImageSource;
import java.util.List;
import java.util.Map;

public final class MetadataFixture {

  private MetadataFixture() {}

  public static <T> MetadataResultBuilder<T> metadataResultBuilder() {
    return new MetadataResultBuilder<>();
  }

  public static <T> T found(MetadataFetchOutcome<T> outcome) {
    if (outcome instanceof MetadataFetchOutcome.Found<T>(var result)) {
      return result;
    }

    throw new AssertionError("Expected metadata to be found, but the fetch ended as " + outcome);
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
