package com.streamarr.server.domain.media;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Optional;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Image extends BaseAuditableEntity<Image> {

  private UUID entityId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private ImageEntityType entityType;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private ImageType imageType;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private ImageSize variant;

  private int width;
  private int height;
  private String blurHash;
  private String key;
  private String contentSha256;
  private String path;

  @Embedded
  @AttributeOverride(name = "topLeft", column = @Column(name = "ambient_top_left"))
  @AttributeOverride(name = "topRight", column = @Column(name = "ambient_top_right"))
  @AttributeOverride(name = "bottomRight", column = @Column(name = "ambient_bottom_right"))
  @AttributeOverride(name = "bottomLeft", column = @Column(name = "ambient_bottom_left"))
  @AttributeOverride(name = "primary", column = @Column(name = "ambient_primary"))
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private AmbientColors ambientColors;

  public Optional<AmbientColors> getAmbientColors() {
    return Optional.ofNullable(ambientColors);
  }

  public void setAmbientColors(Optional<AmbientColors> ambientColors) {
    this.ambientColors = ambientColors.orElse(null);
  }

  public abstract static class ImageBuilder<C extends Image, B extends ImageBuilder<C, B>>
      extends BaseAuditableEntity.BaseAuditableEntityBuilder<Image, C, B> {

    public B ambientColors(Optional<AmbientColors> ambientColors) {
      this.ambientColors = ambientColors.orElse(null);
      return self();
    }
  }
}
