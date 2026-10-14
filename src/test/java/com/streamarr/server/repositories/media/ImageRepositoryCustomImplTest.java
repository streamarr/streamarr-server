package com.streamarr.server.repositories.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.fixtures.ArtworkAttemptFixture;
import com.streamarr.server.fixtures.ImageFixture;
import java.util.List;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockExecuteContext;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Image Repository Custom Implementation Tests")
class ImageRepositoryCustomImplTest {

  @Test
  @DisplayName("Should return no replaced paths when replacement is empty")
  void shouldReturnNoReplacedPathsWhenReplacementIsEmpty() {
    var repository = new ImageRepositoryCustomImpl(null, null, null, null);

    assertThat(
            repository.replaceLogicalArtwork(
                List.of(), ArtworkAttemptFixture.attemptFor(List.of())))
        .isEmpty();
  }

  @Test
  @DisplayName("Should insert image when ambient colors are absent")
  void shouldInsertImageWhenAmbientColorsAreAbsent() throws Exception {
    var imageId = UUID.randomUUID();
    var image =
        ImageFixture.imageBuilder(UUID.randomUUID())
            .id(imageId)
            .key("poster")
            .path("poster-small.jpg")
            .build();

    try (var connection = new MockConnection(ImageRepositoryCustomImplTest::lockedOrInserted)) {
      var dsl = DSL.using(connection, SQLDialect.POSTGRES);
      var repository =
          new ImageRepositoryCustomImpl(dsl, null, null, new ItemLocks(dsl));

      assertThat(
              repository.insertAllIfAbsent(
                  List.of(image), ArtworkAttemptFixture.attemptFor(List.of(image))))
          .containsExactly(imageId);
    }
  }

  // Each lock query finds its row, and the insert reports one inserted row.
  private static MockResult[] lockedOrInserted(MockExecuteContext context) {
    if (!context.sql().toLowerCase().startsWith("select")) {
      return new MockResult[] {new MockResult(1)};
    }

    var create = DSL.using(SQLDialect.POSTGRES);
    var one = DSL.field("one", Integer.class);
    var result = create.newResult(one);
    var row = create.newRecord(one);
    row.set(one, 1);
    result.add(row);
    return new MockResult[] {new MockResult(1, result)};
  }
}
