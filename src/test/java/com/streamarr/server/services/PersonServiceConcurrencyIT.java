package com.streamarr.server.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.server.AbstractIntegrationTest;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.PersonRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@Tag("IntegrationTest")
@DisplayName("PersonService Concurrency Integration Tests")
class PersonServiceConcurrencyIT extends AbstractIntegrationTest {

  @Autowired private PersonService personService;

  @Autowired private PersonRepository personRepository;

  @Test
  @DisplayName("Should create single person when concurrent inserts race on same source ID")
  void shouldCreateSinglePersonWhenConcurrentInsertsRaceOnSameSourceId() {
    var sourceId = "concurrent-person-" + System.nanoTime();
    var threadCount = 5;
    var executor = Executors.newFixedThreadPool(threadCount);
    var startLatch = new CountDownLatch(1);
    var doneLatch = new CountDownLatch(threadCount);
    var results = new CopyOnWriteArrayList<List<Person>>();
    var exceptions = new CopyOnWriteArrayList<Exception>();

    for (int i = 0; i < threadCount; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();
              var person = Person.builder().name("Concurrent Actor").sourceId(sourceId).build();
              var result = personService.getOrCreatePersons(List.of(person), Map.of());
              results.add(result);
            } catch (Exception e) {
              exceptions.add(e);
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(doneLatch.getCount()).isZero());

    executor.shutdown();

    assertThat(exceptions).isEmpty();
    assertThat(results).hasSize(threadCount);
    assertThat(results.stream().flatMap(List::stream).map(Person::getId).distinct()).hasSize(1);
    assertThat(personRepository.findPersonBySourceId(sourceId)).isPresent();
  }
}
