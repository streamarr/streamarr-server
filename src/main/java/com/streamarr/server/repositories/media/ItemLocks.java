package com.streamarr.server.repositories.media;

import static com.streamarr.server.jooq.generated.tables.ArtworkRequest.ARTWORK_REQUEST;
import static com.streamarr.server.jooq.generated.tables.Company.COMPANY;
import static com.streamarr.server.jooq.generated.tables.Episode.EPISODE;
import static com.streamarr.server.jooq.generated.tables.Movie.MOVIE;
import static com.streamarr.server.jooq.generated.tables.Person.PERSON;
import static com.streamarr.server.jooq.generated.tables.Season.SEASON;
import static com.streamarr.server.jooq.generated.tables.Series.SERIES;

import com.streamarr.server.domain.media.ArtworkItem;
import com.streamarr.server.domain.media.ImageEntityType;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.TableField;
import org.springframework.stereotype.Component;

/**
 * Locks the item that image and result rows belong to. Writers of those rows lock the item before
 * any image or result row, and deletes lock it before reading them.
 */
@Component
@RequiredArgsConstructor
class ItemLocks {

  private final DSLContext dsl;

  /**
   * Keeps the item from being deleted until the transaction ends, and returns {@code false} when it
   * no longer exists.
   */
  boolean tryLockAgainstDeletion(UUID itemId, ImageEntityType itemType) {
    var id = idOf(itemType);
    return dsl.selectOne()
        .from(id.getTable())
        .where(id.eq(itemId))
        .forKeyShare()
        .fetchOptional()
        .isPresent();
  }

  /**
   * Keeps the item from being deleted and its artwork request from being replaced or completed
   * until the transaction ends. Returns {@code false} when the item no longer exists or its artwork
   * request no longer has {@code generation}.
   */
  boolean tryLockArtworkGeneration(ArtworkItem item, long generation) {
    if (!tryLockAgainstDeletion(item.itemId(), item.itemType())) {
      return false;
    }

    return dsl.selectOne()
        .from(ARTWORK_REQUEST)
        .where(ARTWORK_REQUEST.ITEM_ID.eq(item.itemId()))
        .and(ARTWORK_REQUEST.ITEM_TYPE.eq(toJooq(item.itemType())))
        .and(ARTWORK_REQUEST.GENERATION.eq(generation))
        .forShare()
        .fetchOptional()
        .isPresent();
  }

  /**
   * Locks the item rows a delete is about to remove, in id order, and returns their ids. A writer
   * holding one of them commits first, so rows read afterwards include what it wrote.
   */
  List<UUID> lockForDeletion(TableField<?, UUID> id, Condition items) {
    return dsl.select(id).from(id.getTable()).where(items).orderBy(id).forUpdate().fetch(id);
  }

  // The generated enum shares its name with the domain enum it mirrors.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.ImageEntityType toJooq(
      ImageEntityType itemType) {
    return com.streamarr.server.jooq.generated.enums.ImageEntityType.lookupLiteral(itemType.name());
  }

  private static TableField<?, UUID> idOf(ImageEntityType itemType) {
    return switch (itemType) {
      case MOVIE -> MOVIE.ID;
      case SERIES -> SERIES.ID;
      case SEASON -> SEASON.ID;
      case EPISODE -> EPISODE.ID;
      case PERSON -> PERSON.ID;
      case COMPANY -> COMPANY.ID;
    };
  }
}
