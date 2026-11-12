package com.streamarr.server.services.streaming;

import com.streamarr.server.config.StreamingProperties;
import com.streamarr.server.domain.streaming.NetworkRoute;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.domain.streaming.VideoQuality;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import org.springframework.stereotype.Service;

/**
 * The upload budget over the stream sessions of this server, with its time series: the active
 * streams by network route, the internet streams' total average bitrate, and the variants that
 * served playlists leave out. No gauge reads the server settings, and no meter carries a
 * per-session or per-viewer tag.
 */
@Service
public class ServerUploadBudget implements UploadBudget {

  private static final String ACTIVE_STREAMS = "streamarr.streaming.active_streams";

  private final ServerSettingsService serverSettings;
  private final RuntimeStreamSessionRegistry runtimeRegistry;
  private final ProducerLifecycleService producerLifecycle;
  private final StreamingProperties properties;
  private final Counter withheldVariants;

  public ServerUploadBudget(
      @NonNull ServerSettingsService serverSettings,
      @NonNull RuntimeStreamSessionRegistry runtimeRegistry,
      @NonNull ProducerLifecycleService producerLifecycle,
      @NonNull StreamingProperties properties,
      @NonNull MeterRegistry meterRegistry) {
    this.serverSettings = serverSettings;
    this.runtimeRegistry = runtimeRegistry;
    this.producerLifecycle = producerLifecycle;
    this.properties = properties;
    withheldVariants =
        Counter.builder("streamarr.streaming.upload_budget_withheld_variants")
            .description("Variants that the upload budget left out of served playlists")
            .register(meterRegistry);
    registerGauges(meterRegistry);
  }

  private void registerGauges(MeterRegistry meterRegistry) {
    Gauge.builder(
            "streamarr.streaming.internet_streams_bitrate",
            this,
            ServerUploadBudget::internetStreamsBitrate)
        .description(
            "The total average bitrate of the active stream sessions whose client reached the"
                + " server over the internet")
        .baseUnit("bits_per_second")
        .register(meterRegistry);
    for (var route : NetworkRoute.values()) {
      Gauge.builder(ACTIVE_STREAMS, this, budget -> budget.activeStreams(route).count())
          .description("Active stream sessions, by the network route that their client attested")
          .tag("network_route", route.name().toLowerCase(Locale.ROOT))
          .register(meterRegistry);
    }
  }

  /**
   * Counts each variant that it leaves out, once per served playlist. When it leaves out the
   * first-listed variant and no variant that stays has a job attempt, the top variant that stays
   * starts before the playlist is served, so no other stream session can take its place under the
   * transcode limit before the player requests it. When that variant cannot start, the playlist
   * stays whole.
   */
  @Override
  public Set<String> withholdFromPlaylist(StreamSession session) {
    var withheld = variantsWithheldFrom(session);
    if (withheld.contains(session.firstListedVariant())
        && !producerLifecycle.startTopListedVariantUnlessOneRuns(
            session, keptVariants(session, withheld))) {
      return Set.of();
    }

    withheldVariants.increment(withheld.size());
    return withheld;
  }

  // The variants of the ladder that stay, in the order that the playlist lists them. The lowest
  // variant always stays, so one stays when the first-listed variant is left out.
  private static List<String> keptVariants(StreamSession session, Set<String> withheld) {
    return session.getVariants().stream()
        .map(QualityVariant::label)
        .filter(variant -> !withheld.contains(variant))
        .toList();
  }

  @Override
  public String topKeptVariant(StreamSession session) {
    var withheld = variantsWithheldFrom(session);
    return listedVariantAverages(session).keySet().stream()
        .filter(variant -> !withheld.contains(variant))
        .findFirst()
        .orElseThrow();
  }

  @Override
  public boolean isALoneAutoCopyOverTheShare(StreamSession session) {
    if (!isALoneAutoCopy(session)) {
      return false;
    }

    var remaining = remainingShare(session);
    return remaining.isPresent()
        && average(session, StreamSession.defaultVariant()) > remaining.getAsLong();
  }

  // An HLS stream session without variants copies the video without lower variants, since every
  // encode lists its ladder. Original stays exempt, and a copy beside lower variants plays through
  // them when it does not fit.
  private static boolean isALoneAutoCopy(StreamSession session) {
    return session.getVariants().isEmpty()
        && ViewerLimits.of(session.getOptions()).quality() == VideoQuality.AUTO;
  }

  /**
   * A stream session that its client reached over the local network never counts, whether or not
   * the budget is on, so that reason comes first.
   */
  @Override
  public UploadBudgetUse useOf(StreamSession session) {
    return useOf(session, isOn());
  }

  /**
   * Leaves out each variant whose average bitrate would take the counted stream sessions over the
   * streams' share of the upload. When no variant fits, the lowest stays, so the stream session
   * never has nothing to play. In Auto, a copy that does not fit plays through its encoded lower
   * variants; a copy without them is the lowest variant and stays. When no variant that stays runs
   * or can start now, as when the transcode limit leaves no place for it, the budget leaves the
   * playlist whole. A stream session that its client reached over the local network sends nothing
   * over the internet upload, so the budget leaves its playlist whole.
   */
  private Set<String> variantsWithheldFrom(StreamSession session) {
    var remaining = remainingShare(session);
    if (remaining.isEmpty()) {
      return Set.of();
    }

    var averages = listedVariantAverages(session);
    var withheld =
        averages.entrySet().stream()
            .filter(variant -> variant.getValue() > remaining.getAsLong())
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    if (withheld.isEmpty()) {
      return withheld;
    }

    if (withheld.size() == averages.size()) {
      var lowest =
          averages.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow().getKey();
      withheld.remove(lowest);
    }

    var anyPlayable =
        averages.keySet().stream()
            .filter(variant -> !withheld.contains(variant))
            .anyMatch(variant -> producerLifecycle.isRunningOrStartable(session, variant));
    if (!anyPlayable) {
      return Set.of();
    }

    return withheld;
  }

  // The streams' share less what the other counted stream sessions send. Empty while the budget is
  // off, and for a stream session that its client reached over the local network.
  private OptionalLong remainingShare(StreamSession session) {
    var share = serverSettings.uploadBudget().streamShareBitsPerSecond();
    if (share.isEmpty() || session.getNetworkRoute() == NetworkRoute.LOCAL_NETWORK) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(share.getAsLong() - countedBeside(session));
  }

  // Each listed variant's average bitrate, in the order that the playlist lists them.
  private Map<String, Long> listedVariantAverages(StreamSession session) {
    var averages = new LinkedHashMap<String, Long>();
    if (session.listsDefaultVariant()) {
      averages.put(
          StreamSession.defaultVariant(), average(session, StreamSession.defaultVariant()));
    }

    session
        .getVariants()
        .forEach(variant -> averages.put(variant.label(), average(session, variant.label())));
    return averages;
  }

  /**
   * Each other counted stream session counts at the average bitrate of the variant that its player
   * downloads: a copy or a direct play at its measured average, or its probed average before the
   * bitrate scan has run, and an encoded variant at its target average.
   */
  private long countedBeside(StreamSession session) {
    return countedSessions()
        .filter(other -> !other.getSessionId().equals(session.getSessionId()))
        .mapToLong(this::downloadedAverage)
        .sum();
  }

  private double internetStreamsBitrate() {
    return (double) activeStreams(NetworkRoute.INTERNET).mapToLong(this::downloadedAverage).sum();
  }

  private Stream<StreamSession> activeStreams(NetworkRoute route) {
    return runtimeRegistry.findAll().stream()
        .filter(session -> !session.isSuspended())
        .filter(session -> session.getNetworkRoute() == route);
  }

  private Stream<StreamSession> countedSessions() {
    var on = isOn();
    return runtimeRegistry.findAll().stream()
        .filter(session -> useOf(session, on) == UploadBudgetUse.COUNTED);
  }

  private static UploadBudgetUse useOf(StreamSession session, boolean budgetOn) {
    if (session.getNetworkRoute() == NetworkRoute.LOCAL_NETWORK) {
      return UploadBudgetUse.LOCAL_NETWORK_ATTESTED;
    }

    if (!budgetOn) {
      return UploadBudgetUse.BUDGET_OFF;
    }

    if (session.isSuspended()) {
      return UploadBudgetUse.SUSPENDED;
    }

    return UploadBudgetUse.COUNTED;
  }

  private boolean isOn() {
    return serverSettings.uploadBudget().streamShareBitsPerSecond().isPresent();
  }

  private long downloadedAverage(StreamSession session) {
    return average(session, producerLifecycle.downloadedVariant(session));
  }

  private long average(StreamSession session, String variantLabel) {
    return VariantBandwidth.of(session, variantLabel, properties).average();
  }
}
