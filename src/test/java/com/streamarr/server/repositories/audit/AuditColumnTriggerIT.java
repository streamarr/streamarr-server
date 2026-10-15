package com.streamarr.server.repositories.audit;

import static com.streamarr.server.jooq.generated.tables.Genre.GENRE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.jooq.generated.tables.records.GenreRecord;
import com.streamarr.server.support.AuditActorSetting;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("IntegrationTest")
@DisplayName("Audit Column Trigger Integration Tests")
class AuditColumnTriggerIT extends AbstractIntegrationTest {

  private static final OffsetDateTime LONG_AGO =
      OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

  private final String sourceId = "audit-trigger-" + UUID.randomUUID();

  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate transactionTemplate;

  @AfterEach
  void deleteGenre() {
    dsl.deleteFrom(GENRE).where(GENRE.SOURCE_ID.eq(sourceId)).execute();
  }

  @Test
  @DisplayName("Should reject an insert when the transaction names no actor")
  void shouldRejectInsertWhenTransactionNamesNoActor() {
    assertThatThrownBy(() -> inTransactionNaming("", this::insertGenre))
        .hasStackTraceContaining("names no audit actor");

    assertThat(dsl.fetchExists(GENRE, GENRE.SOURCE_ID.eq(sourceId))).isFalse();
  }

  @Test
  @DisplayName("Should reject an update when the transaction names no actor")
  void shouldRejectUpdateWhenTransactionNamesNoActor() {
    inTransactionNaming(UUID.randomUUID().toString(), this::insertGenre);

    assertThatThrownBy(() -> inTransactionNaming("", context -> renameGenre(context, "Renamed")))
        .hasStackTraceContaining("names no audit actor");

    assertThat(storedGenre().getName()).isEqualTo("Drama");
  }

  @Test
  @DisplayName("Should fill all four columns when the transaction names an actor")
  void shouldFillAllFourColumnsWhenTransactionNamesAnActor() {
    var actor = UUID.randomUUID();

    var transactionTime =
        inTransactionNaming(
            actor.toString(),
            context -> {
              context
                  .insertInto(GENRE)
                  .set(GENRE.SOURCE_ID, sourceId)
                  .set(GENRE.NAME, "Drama")
                  .set(GENRE.CREATED_BY, UUID.randomUUID())
                  .set(GENRE.CREATED_ON, LONG_AGO)
                  .set(GENRE.LAST_MODIFIED_BY, UUID.randomUUID())
                  .set(GENRE.LAST_MODIFIED_ON, LONG_AGO)
                  .execute();
              return transactionTime(context);
            });

    var genre = storedGenre();
    assertThat(genre.getCreatedBy()).isEqualTo(actor);
    assertThat(genre.getLastModifiedBy()).isEqualTo(actor);
    assertThat(genre.getCreatedOn()).isAtSameInstantAs(transactionTime);
    assertThat(genre.getLastModifiedOn()).isAtSameInstantAs(transactionTime);
  }

  @Test
  @DisplayName("Should keep the creator and creation time when a later transaction updates the row")
  void shouldKeepCreatorAndCreationTimeWhenALaterTransactionUpdatesTheRow() {
    var creator = UUID.randomUUID();
    var modifier = UUID.randomUUID();
    var creationTime = inTransactionNaming(creator.toString(), this::insertGenre);

    var modificationTime =
        inTransactionNaming(
            modifier.toString(),
            context -> {
              context
                  .update(GENRE)
                  .set(GENRE.NAME, "Renamed")
                  .set(GENRE.CREATED_BY, modifier)
                  .set(GENRE.CREATED_ON, LONG_AGO)
                  .where(GENRE.SOURCE_ID.eq(sourceId))
                  .execute();
              return transactionTime(context);
            });

    var genre = storedGenre();
    assertThat(genre.getName()).isEqualTo("Renamed");
    assertThat(genre.getCreatedBy()).isEqualTo(creator);
    assertThat(genre.getCreatedOn()).isAtSameInstantAs(creationTime);
    assertThat(genre.getLastModifiedBy()).isEqualTo(modifier);
    assertThat(genre.getLastModifiedOn()).isAtSameInstantAs(modificationTime).isAfter(creationTime);
  }

  @Test
  @DisplayName("Should fill the last modified columns when ON CONFLICT DO UPDATE changes the row")
  void shouldFillLastModifiedColumnsWhenOnConflictDoUpdateChangesTheRow() {
    var creator = UUID.randomUUID();
    var modifier = UUID.randomUUID();
    var creationTime = inTransactionNaming(creator.toString(), this::insertGenre);

    var modificationTime =
        inTransactionNaming(
            modifier.toString(),
            context -> {
              context
                  .insertInto(GENRE)
                  .set(GENRE.SOURCE_ID, sourceId)
                  .set(GENRE.NAME, "Renamed")
                  .onConflict(GENRE.SOURCE_ID)
                  .doUpdate()
                  .set(GENRE.NAME, "Renamed")
                  .execute();
              return transactionTime(context);
            });

    var genre = storedGenre();
    assertThat(genre.getName()).isEqualTo("Renamed");
    assertThat(genre.getCreatedBy()).isEqualTo(creator);
    assertThat(genre.getCreatedOn()).isAtSameInstantAs(creationTime);
    assertThat(genre.getLastModifiedBy()).isEqualTo(modifier);
    assertThat(genre.getLastModifiedOn()).isAtSameInstantAs(modificationTime);
  }

  @Test
  @DisplayName("Should store empty actor columns when the transaction is a request without account")
  void shouldStoreEmptyActorColumnsWhenTransactionIsARequestWithoutAccount() {
    var transactionTime =
        inTransactionNaming(AuditActorSetting.REQUEST_WITHOUT_ACCOUNT, this::insertGenre);

    var genre = storedGenre();
    assertThat(genre.getCreatedBy()).isNull();
    assertThat(genre.getLastModifiedBy()).isNull();
    assertThat(genre.getCreatedOn()).isAtSameInstantAs(transactionTime);
    assertThat(genre.getLastModifiedOn()).isAtSameInstantAs(transactionTime);
  }

  @Test
  @DisplayName("Should reject a write when the actor setting is not a UUID")
  void shouldRejectWriteWhenActorSettingIsNotAUuid() {
    assertThatThrownBy(() -> inTransactionNaming("someone", this::insertGenre))
        .hasStackTraceContaining("invalid input syntax for type uuid");

    assertThat(dsl.fetchExists(GENRE, GENRE.SOURCE_ID.eq(sourceId))).isFalse();
  }

  private <T> T inTransactionNaming(String actorSetting, Function<DSLContext, T> work) {
    return transactionTemplate.execute(
        _ -> {
          dsl.select(
                  DSL.function(
                      "set_config",
                      String.class,
                      DSL.val(AuditActorSetting.NAME),
                      DSL.val(actorSetting),
                      DSL.val(true)))
              .execute();
          return work.apply(dsl);
        });
  }

  private OffsetDateTime insertGenre(DSLContext context) {
    context.insertInto(GENRE).set(GENRE.SOURCE_ID, sourceId).set(GENRE.NAME, "Drama").execute();
    return transactionTime(context);
  }

  private OffsetDateTime renameGenre(DSLContext context, String name) {
    context.update(GENRE).set(GENRE.NAME, name).where(GENRE.SOURCE_ID.eq(sourceId)).execute();
    return transactionTime(context);
  }

  private static OffsetDateTime transactionTime(DSLContext context) {
    return context.select(DSL.currentOffsetDateTime()).fetchSingle().value1();
  }

  private GenreRecord storedGenre() {
    return dsl.fetchSingle(GENRE, GENRE.SOURCE_ID.eq(sourceId));
  }
}
