package com.streamarr.server.domain.media;

import com.streamarr.server.domain.BaseAuditableEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.UUID;
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
public class MediaFile extends BaseAuditableEntity<MediaFile> {

  private UUID mediaId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  private MediaFileStatus status;

  private UUID libraryId;

  private String filename;
  private String filepathUri;

  private long size;

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    MediaFile that = (MediaFile) o;

    return filepathUri != null && filepathUri.equals(that.getFilepathUri());
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
