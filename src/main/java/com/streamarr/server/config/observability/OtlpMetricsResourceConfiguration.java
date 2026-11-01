package com.streamarr.server.config.observability;

import io.micrometer.registry.otlp.OtlpConfig;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.resources.Resource;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exports metrics under the resource that the OpenTelemetry starter's SDK gives spans and logs: the
 * same service, instance, host, process and container attributes, so a backend can join the three
 * signals. Micrometer sends each attribute as a string, an array as a JSON array, and leaves out
 * {@code telemetry.*}, which names the SDK that sent an export. With the SDK disabled, metrics keep
 * Spring Boot's resource attributes.
 */
@Configuration(proxyBeanMethods = false)
class OtlpMetricsResourceConfiguration {

  private static final String TELEMETRY_NAMESPACE = "telemetry.";
  private static final String RESOURCE_ATTRIBUTES = "resourceAttributes";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private OtlpMetricsResourceConfiguration() {}

  @Bean
  static SdkResourceCapture otelSdkResourceCapture() {
    return new SdkResourceCapture();
  }

  @Bean
  static BeanPostProcessor otlpMetricsUnderTheSdkResource(
      ObjectProvider<OpenTelemetry> openTelemetry, ObjectProvider<SdkResourceCapture> capture) {
    return new BeanPostProcessor() {
      @Override
      public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof OtlpConfig config)) {
          return bean;
        }

        return withResourceAttributes(
            config,
            () -> {
              // The SDK hands its resource to the capture while the OpenTelemetry bean is built.
              openTelemetry.getIfAvailable();
              return capture
                  .getObject()
                  .resource()
                  .map(OtlpMetricsResourceConfiguration::attributesAsText)
                  .orElseGet(config::resourceAttributes);
            });
      }
    };
  }

  private static OtlpConfig withResourceAttributes(
      OtlpConfig config, Supplier<Map<String, String>> resourceAttributes) {
    var factory = new ProxyFactory(config);
    factory.addAdvice(
        (MethodInterceptor)
            invocation ->
                isResourceAttributes(invocation.getMethod())
                    ? resourceAttributes.get()
                    : invocation.proceed());
    // The proxy implements every interface of the config, OtlpConfig among them.
    return (OtlpConfig) factory.getProxy();
  }

  private static boolean isResourceAttributes(Method method) {
    return RESOURCE_ATTRIBUTES.equals(method.getName()) && method.getParameterCount() == 0;
  }

  private static Map<String, String> attributesAsText(Resource resource) {
    var attributes = new LinkedHashMap<String, String>();
    resource
        .getAttributes()
        .forEach(
            (key, value) -> {
              if (!key.getKey().startsWith(TELEMETRY_NAMESPACE)) {
                attributes.put(key.getKey(), text(value));
              }
            });
    return Map.copyOf(attributes);
  }

  // OpenTelemetry's string form for an exporter without typed attributes: an array becomes a JSON
  // array.
  private static String text(Object value) {
    if (value instanceof List<?> items) {
      return JSON.writeValueAsString(items);
    }

    return String.valueOf(value);
  }

  /**
   * Receives the resource that the starter's SDK builds, after every other customizer has run. The
   * starter loads each {@link AutoConfigurationCustomizerProvider} bean while it builds the SDK.
   */
  static final class SdkResourceCapture implements AutoConfigurationCustomizerProvider {

    private volatile Resource resource;

    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
      customizer.addResourceCustomizer(
          (built, config) -> {
            resource = built;
            return built;
          });
    }

    @Override
    public int order() {
      return Integer.MAX_VALUE;
    }

    Optional<Resource> resource() {
      return Optional.ofNullable(resource);
    }
  }
}
