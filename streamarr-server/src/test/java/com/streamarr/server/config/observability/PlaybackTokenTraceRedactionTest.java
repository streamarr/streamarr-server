package com.streamarr.server.config.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.instrumentation.spring.webmvc.v6_0.SpringWebMvcTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@Tag("UnitTest")
@DisplayName("Playback Token Trace Redaction Tests")
class PlaybackTokenTraceRedactionTest {

  private static final AttributeKey<String> URL_QUERY = AttributeKey.stringKey("url.query");

  @Test
  @DisplayName("Should redact playback token from trace while preserving request parameter")
  void shouldRedactPlaybackTokenFromTraceWhilePreservingRequestParameter() throws Exception {
    var exporter = new CapturingSpanExporter();
    var tracerProvider =
        SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    var openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    var telemetryFilter = SpringWebMvcTelemetry.create(openTelemetry).createServletFilter();
    var request =
        new MockHttpServletRequest(
            "GET", "/api/stream/21a268d7-6e6a-48fd-a9cc-93bbc1ee85de/master.m3u8");
    request.setQueryString("quality=auto&t=secret-playback-token&offset=12");
    request.addParameter("quality", "auto");
    request.addParameter("t", "secret-playback-token");
    request.addParameter("offset", "12");
    var observedToken = new AtomicReference<String>();

    FilterChain terminal =
        (servletRequest, _) ->
            observedToken.set(((HttpServletRequest) servletRequest).getParameter("t"));

    try (var context = new AnnotationConfigApplicationContext()) {
      context.scan("com.streamarr.server.config.observability");
      context.refresh();
      var applicationFilters = new ArrayList<>(context.getBeansOfType(Filter.class).values());
      AnnotationAwareOrderComparator.sort(applicationFilters);
      var chain = chain(applicationFilters, telemetryFilter, terminal);

      chain.doFilter(request, new MockHttpServletResponse());
    } finally {
      tracerProvider.close();
    }

    assertThat(observedToken).hasValue("secret-playback-token");
    assertThat(exporter.spans())
        .extracting(span -> span.getAttributes().get(URL_QUERY))
        .containsExactly("quality=auto&offset=12");
  }

  private static FilterChain chain(
      List<Filter> applicationFilters, Filter telemetryFilter, FilterChain terminal) {
    FilterChain chain =
        (request, response) -> telemetryFilter.doFilter(request, response, terminal);

    for (var index = applicationFilters.size() - 1; index >= 0; index--) {
      var filter = applicationFilters.get(index);
      var next = chain;
      chain = (request, response) -> filter.doFilter(request, response, next);
    }

    return chain;
  }

  private static final class CapturingSpanExporter implements SpanExporter {

    private final List<SpanData> spans = new ArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
      this.spans.addAll(spans);
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
      return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
      return CompletableResultCode.ofSuccess();
    }

    List<SpanData> spans() {
      return List.copyOf(spans);
    }
  }
}
