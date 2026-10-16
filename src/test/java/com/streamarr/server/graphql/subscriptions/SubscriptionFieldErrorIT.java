package com.streamarr.server.graphql.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.Library;
import com.streamarr.server.domain.LibraryStatus;
import com.streamarr.server.fixtures.LibraryFixtureCreator;
import com.streamarr.server.repositories.LibraryRepository;
import com.streamarr.server.services.events.library.LibraryStatusChangedEvent;
import com.streamarr.server.services.filepath.FilepathCodec;
import com.streamarr.server.support.ActiveSubscriptions;
import com.streamarr.server.support.AuthTestSupport;
import com.streamarr.server.support.GraphQlTransportWsTestClient;
import graphql.schema.DataFetchingEnvironment;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

@Tag("IntegrationTest")
@DisplayName("Subscription Field Error Integration Tests")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SubscriptionFieldErrorIT.UnresolvableFilepathUriResolver.class)
class SubscriptionFieldErrorIT extends AbstractIntegrationTest {

  private static final String STATUS_FIELD = "libraryStatusChanged";
  private static final String UNRESOLVABLE_NAME = "Unresolvable Library";

  @LocalServerPort private int port;

  @Autowired private ApplicationEventPublisher eventPublisher;
  @Autowired private AuthTestSupport authTestSupport;
  @Autowired private LibraryRepository libraryRepository;
  @Autowired private MeterRegistry meterRegistry;

  @TempDir private Path libraryDirectory;

  private final List<UUID> libraryIds = new ArrayList<>();
  private AuthTestSupport.TestIdentity admin;
  private GraphQlTransportWsTestClient socket;
  private ActiveSubscriptions activeSubscriptions;

  @BeforeEach
  void subscribeToStatusChanges() {
    activeSubscriptions = new ActiveSubscriptions(meterRegistry);
    admin = authTestSupport.createAdminIdentity();
    socket =
        GraphQlTransportWsTestClient.connectWithBearer(port, authTestSupport.profileBearer(admin));
    socket.subscribe(
        "status", "subscription { libraryStatusChanged { id name filepathUri status } }");
    activeSubscriptions.awaitCount(STATUS_FIELD, 1);
  }

  @AfterEach
  void releaseResources() {
    socket.close();
    activeSubscriptions.awaitNoneActive();
    libraryIds.forEach(libraryRepository::deleteById);
    authTestSupport.deleteIdentity(admin);
  }

  @Test
  @DisplayName(
      "Should deliver null with errors and keep the operation open when a library's non-null field fails to resolve")
  void shouldDeliverNullWithErrorsAndKeepOperationOpenWhenLibrarysNonNullFieldFailsToResolve() {
    var resolvable = saveLibrary("Resolvable Library");
    var unresolvable = saveLibrary(UNRESOLVABLE_NAME);

    publishStatusChange(resolvable);
    assertThat(nextStatusChange(resolvable.getId()).path("filepathUri").asString())
        .isEqualTo(resolvable.getFilepathUri());

    publishStatusChange(unresolvable);

    var failed = nextMessageWithoutLibrary();
    assertThat(failed.path("type").asString()).isEqualTo("next");
    var payload = failed.path("payload");
    assertThat(payload.path("data").has(STATUS_FIELD)).isTrue();
    assertThat(payload.path("data").path(STATUS_FIELD).isNull()).isTrue();
    assertThat(payload.path("errors").path(0).path("path").valueStream())
        .map(JsonNode::asString)
        .containsExactly(STATUS_FIELD, "filepathUri");

    publishStatusChange(resolvable);

    assertThat(nextStatusChange(resolvable.getId()).path("filepathUri").asString())
        .isEqualTo(resolvable.getFilepathUri());
  }

  private Library saveLibrary(String name) {
    var library =
        libraryRepository.saveAndFlush(
            LibraryFixtureCreator.unsavedLibraryBuilder()
                .name(name)
                .filepathUri(FilepathCodec.encode(libraryDirectory.resolve(name)))
                .status(LibraryStatus.HEALTHY)
                .build());
    libraryIds.add(library.getId());
    return library;
  }

  private void publishStatusChange(Library library) {
    eventPublisher.publishEvent(new LibraryStatusChangedEvent(library.getId()));
  }

  // The next status change of one library, skipping changes other tests make to their libraries.
  private JsonNode nextStatusChange(UUID libraryId) {
    while (true) {
      var change = socket.nextMessageFor("status").path("payload").path("data").path(STATUS_FIELD);
      if (libraryId.toString().equals(change.path("id").asString())) {
        return change;
      }
    }
  }

  // The next message that delivers no library, skipping changes other tests make to their
  // libraries.
  private JsonNode nextMessageWithoutLibrary() {
    while (true) {
      var message = socket.nextMessageFor("status");
      if (!message.path("payload").path("data").path(STATUS_FIELD).isObject()) {
        return message;
      }
    }
  }

  /** Fails the non-null filepathUri of the library named {@value #UNRESOLVABLE_NAME}. */
  @DgsComponent
  static class UnresolvableFilepathUriResolver {

    @DgsData(parentType = "Library", field = "filepathUri")
    public String filepathUri(DataFetchingEnvironment dfe) {
      Library library = dfe.getSource();
      if (UNRESOLVABLE_NAME.equals(library.getName())) {
        throw new UnresolvableFieldException();
      }

      return library.getFilepathUri();
    }
  }

  private static final class UnresolvableFieldException extends RuntimeException {

    private UnresolvableFieldException() {
      super("filepathUri cannot be resolved for this library");
    }
  }
}
