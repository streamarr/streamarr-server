package com.streamarr.server.services.streaming;

import com.streamarr.server.domain.streaming.NetworkRoute;
import com.streamarr.server.domain.streaming.QualityVariant;
import com.streamarr.server.domain.streaming.StreamSession;
import com.streamarr.server.properties.StreamingProperties;
import com.streamarr.server.services.serversettings.ServerSettingsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.NonNull;
import org.springframework.stereotype.Service;

/**
 * The upload budget over the stream sessions of this server, with its time series: the internet
 * upload speed, the streams' share of it, the counted total, the active streams counted and not
 * counted, and the variants that served playlists leave out. No meter carries a per-session or
 * per-viewer tag.
 */
@Service
public class ServerUploadBudget implements UploadBudget {

  private static final String METRIC_PREFIX = "streamarr.streaming.upload_budget_";
  private static final String COUNTED = "counted";
  private static final String BITS_PER_SECOND = "bits_per_second";

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
        Counter.builder(METRIC_PREFIX + "withheld_variants")
            .description("Variants that the upload budget left out of served playlists")
            .register(meterRegistry);
    registerGauges(meterRegistry);
  }

  private void registerGauges(MeterRegistry meterRegistry) {
    Gauge.builder(
            METRIC_PREFIX + "internet_upload",
            this,
            budget ->
                zeroWhenOff(budget.serverSettings.uploadBudget().internetUploadBitsPerSecond()))
        .description("The internet upload speed that a ServerAdmin entered, or 0 while it is off")
        .baseUnit(BITS_PER_SECOND)
        .register(meterRegistry);
    Gauge.builder(
            METRIC_PREFIX + "stream_share",
            this,
            budget -> zeroWhenOff(budget.serverSettings.uploadBudget().streamShareBitsPerSecond()))
        .description("The share of the upload that counted stream sessions may use together")
        .baseUnit(BITS_PER_SECOND)
        .register(meterRegistry);
    Gauge.builder(METRIC_PREFIX + COUNTED, this, ServerUploadBudget::countedTotal)
        .description("The average bitrate of every stream session that counts against the budget")
        .baseUnit(BITS_PER_SECOND)
        .register(meterRegistry);
    Gauge.builder(METRIC_PREFIX + "active_streams", this, budget -> budget.activeStreams(true))
        .description("Active stream sessions, by whether they count against the upload budget")
        .tag(COUNTED, "true")
        .register(meterRegistry);
    Gauge.builder(METRIC_PREFIX + "active_streams", this, budget -> budget.activeStreams(false))
        .description("Active stream sessions, by whether they count against the upload budget")
        .tag(COUNTED, "false")
        .register(meterRegistry);
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
  public boolean isWithheldFromPlaylist(StreamSession session, String variantLabel) {
    return variantsWithheldFrom(session).contains(variantLabel);
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
   * never has nothing to play; a copy lists no other variant, so the budget never leaves it out.
   * When no variant that stays runs or can start now, as when the transcode limit leaves no place
   * for it, the budget leaves the playlist whole. A stream session that its client reached over the
   * local network sends nothing over the internet upload, so the budget leaves its playlist whole.
   */
  private Set<String> variantsWithheldFrom(StreamSession session) {
    var share = serverSettings.uploadBudget().streamShareBitsPerSecond();
    if (share.isEmpty() || session.getNetworkRoute() == NetworkRoute.LOCAL_NETWORK) {
      return Set.of();
    }

    var remaining = share.getAsLong() - countedBeside(session);
    var averages = listedVariantAverages(session);
    var withheld =
        averages.entrySet().stream()
            .filter(variant -> variant.getValue() > remaining)
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

  // Each listed variant's average bitrate, in the order that the playlist lists them.
  private Map<String, Long> listedVariantAverages(StreamSession session) {
    var averages = new LinkedHashMap<String, Long>();
    if (session.getVariants().isEmpty()) {
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

  private double countedTotal() {
    return (double) countedSessions().mapToLong(this::downloadedAverage).sum();
  }

  private double activeStreams(boolean counted) {
    var on = isOn();
    return (double)
        runtimeRegistry.findAll().stream()
            .filter(session -> !session.isSuspended())
            .filter(session -> (useOf(session, on) == UploadBudgetUse.COUNTED) == counted)
            .count();
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

  private static double zeroWhenOff(OptionalLong bitsPerSecond) {
    return (double) bitsPerSecond.orElse(0);
  }
}
