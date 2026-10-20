package com.streamarr.server.graphql.resolvers;

import static com.streamarr.server.fixtures.AuthenticatedIdentityFixture.profileScopedBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.server.domain.Library;
import com.streamarr.server.fakes.FakeAuthorizationService;
import com.streamarr.server.fakes.FakeLibraryRepository;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.graphql.subscriptions.LibraryContentsBroadcasters;
import com.streamarr.server.graphql.subscriptions.RecordingSubscriber;
import com.streamarr.server.graphql.subscriptions.SubscriptionOverflowException;
import com.streamarr.server.graphql.subscriptions.SubscriptionProperties;
import com.streamarr.server.services.library.LibraryContentsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import reactor.core.scheduler.Schedulers;

@Tag("UnitTest")
@DisplayName("Library contents subscription resolver")
class LibraryContentsSubscriptionResolverTest {

  private static final int OPERATION_BUFFER = 2;

  @Test
  @DisplayName(
      "Should fail a counts subscription when it stops requesting and more changes arrive than"
          + " its buffer holds")
  void shouldFailACountsSubscriptionWhenItStopsRequestingBeyondItsBuffer() {
    var identity = profileScopedBuilder().build();
    var authorization = new FakeAuthorizationService(identity).allowAll();
    var libraries = new FakeLibraryRepository();
    var library = libraries.save(LibraryFixtureCreator.buildFakeLibrary());
    var broadcasters =
        new LibraryContentsBroadcasters(
            new SimpleMeterRegistry(),
            Schedulers.immediate(),
            new SubscriptionProperties(
                OPERATION_BUFFER, 10, DataSize.ofKilobytes(64), Duration.ofSeconds(5)));
    var resolver =
        LibraryContentsSubscriptionResolver.builder()
            .authorization(authorization)
            .contents(
                LibraryContentsService.builder()
                    .libraries(libraries)
                    .authorization(authorization)
                    .build())
            .broadcasters(broadcasters)
            .build();
    var stalled = new RecordingSubscriber<Library>(0);
    resolver.libraryCountsChanged(library.getId().toString()).subscribe(stalled);

    for (var change = 0; change <= OPERATION_BUFFER; change++) {
      broadcasters.getCounts().broadcast(library.getId(), library.getId());
    }

    assertThat(stalled.awaitError()).isInstanceOf(SubscriptionOverflowException.class);
  }
}
