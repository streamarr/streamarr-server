package com.streamarr.server.repositories;

import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.left;
import static org.jooq.impl.DSL.upper;
import static org.jooq.impl.DSL.when;

import com.streamarr.server.domain.AlphabetLetter;
import com.streamarr.server.domain.LibraryMetadata;
import com.streamarr.server.jooq.generated.Tables;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Query;
import org.jooq.Record2;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@RequiredArgsConstructor
public class LibraryMetadataRepositoryCustomImpl implements LibraryMetadataRepositoryCustom {

  private final DSLContext context;

  @Override
  @Transactional
  public int replaceLetterCounts(UUID libraryId) {
    var entries = queryLetterCounts(libraryId);
    persistLetterCounts(libraryId, entries);
    return entries.size();
  }

  private List<LibraryMetadata> queryLetterCounts(UUID libraryId) {
    var firstChar = left(Tables.BASE_COLLECTABLE.TITLE_SORT, inline(1));

    Field<String> letterExpression =
        when(firstChar.likeRegex(inline("[a-zA-Z]")), upper(firstChar)).otherwise(inline("HASH"));

    List<Record2<String, Integer>> results =
        context
            .select(letterExpression.as("letter"), count().as("item_count"))
            .from(Tables.BASE_COLLECTABLE)
            .where(Tables.BASE_COLLECTABLE.LIBRARY_ID.eq(libraryId))
            .groupBy(letterExpression)
            .orderBy(letterExpression)
            .fetch();

    var metadataEntries = new ArrayList<LibraryMetadata>();

    for (var row : results) {
      var letterStr = row.value1();
      var itemCount = row.value2();

      var letter = parseAlphabetLetter(letterStr);
      if (letter == null) {
        continue;
      }

      metadataEntries.add(
          LibraryMetadata.builder()
              .libraryId(libraryId)
              .letter(letter)
              .itemCount(itemCount)
              .build());
    }

    return metadataEntries;
  }

  private void persistLetterCounts(UUID libraryId, List<LibraryMetadata> entries) {
    var lm = Tables.LIBRARY_METADATA;

    if (!entries.isEmpty()) {
      var upserts =
          entries.stream()
              .map(
                  entry ->
                      (Query)
                          context
                              .insertInto(lm)
                              .set(lm.LIBRARY_ID, libraryId)
                              .set(lm.LETTER, toJooqLetter(entry.getLetter()))
                              .set(lm.ITEM_COUNT, entry.getItemCount())
                              .onConflict(lm.LIBRARY_ID, lm.LETTER)
                              .doUpdate()
                              .set(lm.ITEM_COUNT, entry.getItemCount()))
              .toArray(Query[]::new);

      context.batch(upserts).execute();
    }

    var currentLetters = entries.stream().map(e -> toJooqLetter(e.getLetter())).toList();

    context
        .deleteFrom(lm)
        .where(lm.LIBRARY_ID.eq(libraryId))
        .and(currentLetters.isEmpty() ? lm.LETTER.isNotNull() : lm.LETTER.notIn(currentLetters))
        .execute();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.streamarr.server.jooq.generated.enums.AlphabetLetter toJooqLetter(
      AlphabetLetter letter) {
    return com.streamarr.server.jooq.generated.enums.AlphabetLetter.valueOf(letter.name());
  }

  private static AlphabetLetter parseAlphabetLetter(String value) {
    try {
      return AlphabetLetter.valueOf(value);
    } catch (IllegalArgumentException _) {
      log.warn("Unexpected letter value from aggregation: {}", value);
      return null;
    }
  }
}
