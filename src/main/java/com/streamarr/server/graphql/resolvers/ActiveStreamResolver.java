package com.streamarr.server.graphql.resolvers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.DgsSubscription;
import com.netflix.graphql.dgs.DgsTypeResolver;
import com.streamarr.server.domain.media.Episode;
import com.streamarr.server.domain.media.Movie;
import com.streamarr.server.graphql.dto.ActiveStreamDto;
import com.streamarr.server.graphql.subscriptions.ActiveStreamBroadcaster;
import com.streamarr.server.services.authorization.AuthorizationService;
import com.streamarr.server.services.streaming.ActiveStreamService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;

@DgsComponent
@RequiredArgsConstructor
public class ActiveStreamResolver {

  private final AuthorizationService authorizationService;
  private final ActiveStreamService activeStreams;
  private final ActiveStreamBroadcaster broadcaster;

  @DgsQuery
  public List<ActiveStreamDto> activeStreams() {
    return activeStreams.activeStreams(authorizationService.currentIdentity()).stream()
        .map(ActiveStreamDto::from)
        .toList();
  }

  // ServerAdmin authority is live, so each delivery checks it again and a subscriber that lost it
  // ends with the error before the change reaches it.
  @DgsSubscription
  public Publisher<ActiveStreamDto.ChangeDto> activeStreamsChanged() {
    var identity = authorizationService.currentIdentity();
    activeStreams.requireViewActiveStreams(identity);
    return broadcaster
        .getChanges()
        .register()
        .doOnNext(_ -> activeStreams.requireViewActiveStreams(identity));
  }

  @DgsTypeResolver(name = "ActiveStreamMedia")
  public String resolveActiveStreamMedia(Object media) {
    return switch (media) {
      case Movie _ -> "Movie";
      case Episode _ -> "Episode";
      default ->
          throw new IllegalArgumentException(
              "Unknown active stream media type: " + media.getClass().getSimpleName());
    };
  }
}
