package com.streamarr.server.services;

import com.streamarr.server.config.ArtworkPrioritySchedulers;
import com.streamarr.server.domain.media.ImageEntityType;
import com.streamarr.server.domain.media.ImageType;
import com.streamarr.server.domain.metadata.Person;
import com.streamarr.server.repositories.PersonRepository;
import com.streamarr.server.services.metadata.ImageRefreshMode;
import com.streamarr.server.services.metadata.events.ImageSource.TmdbImageSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the required artwork tests after an earlier test class left a background artwork step that
 * became due after the context's artwork schedulers last checked for due executions. A step whose
 * download failed waits five seconds to retry, so the step of a class that ran shortly before can
 * become due while these tests run.
 */
@DisplayName("Required Artwork Integration Tests After A Leftover Background Artwork Step")
class RequiredArtworkAfterLeftoverStepIT extends RequiredArtworkIT {

  @BeforeAll
  static void leaveADueBackgroundArtworkStep(
      @Autowired ArtworkPrioritySchedulers artworkPrioritySchedulers,
      @Autowired PersonRepository personRepository,
      @Autowired ArtworkRequestService artworkRequestService,
      @Autowired TransactionTemplate transactionTemplate) {
    artworkPrioritySchedulers.stop();
    var person =
        personRepository.saveAndFlush(
            Person.builder()
                .name("Leftover Person")
                .sourceId(UUID.randomUUID().toString())
                .build());
    var artwork =
        ArtworkSources.builder()
            .targetId(person.getId())
            .targetType(ImageEntityType.PERSON)
            .sources(List.of(new TmdbImageSource(ImageType.PROFILE, "/leftover.jpg")))
            .build();

    transactionTemplate.executeWithoutResult(
        _ -> artworkRequestService.requestBackgroundArtwork(artwork, ImageRefreshMode.PRESERVE));
  }
}
