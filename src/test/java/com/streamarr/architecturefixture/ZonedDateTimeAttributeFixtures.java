package com.streamarr.architecturefixture;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.ZonedDateTime;
import java.util.UUID;
import lombok.Getter;

// Outside com.streamarr.server so that entity scanning never maps these classes.
public final class ZonedDateTimeAttributeFixtures {

  private ZonedDateTimeAttributeFixtures() {}

  @Entity
  @Getter
  public static class ZonedDateTimeEntity {
    @Id private UUID id;
    private ZonedDateTime airedAt;
  }

  @MappedSuperclass
  @Getter
  public abstract static class ZonedDateTimeMappedSuperclass {
    private ZonedDateTime airedAt;
  }

  @Embeddable
  public record ZonedDateTimeEmbeddable(ZonedDateTime airedAt) {}
}
