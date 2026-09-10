package com.streamarr.server.graphql;

import com.streamarr.server.web.ClientIpAddressNormalizer;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Keeps the client address available to data fetchers running on other threads. */
@Component
@RequiredArgsConstructor
public class ClientIpAddressGraphQlInterceptor implements WebGraphQlInterceptor {

  private static final String CONTEXT_KEY = ClientIpAddressGraphQlInterceptor.class.getName();

  private final ClientIpAddressNormalizer normalizer;

  @Override
  public Mono<WebGraphQlResponse> intercept(
      WebGraphQlRequest request, WebGraphQlInterceptor.Chain chain) {
    var ipAddress = clientAddress(request);
    request.configureExecutionInput(
        (input, builder) ->
            builder.graphQLContext(context -> context.put(CONTEXT_KEY, ipAddress)).build());
    return chain.next(request);
  }

  private String clientAddress(WebGraphQlRequest request) {
    var remoteAddress = request.getRemoteAddress();
    if (remoteAddress == null) {
      return normalizer.normalize(null);
    }

    var address = remoteAddress.getAddress();
    return normalizer.normalize(
        address == null ? remoteAddress.getHostString() : address.getHostAddress());
  }

  public static String resolve(DataFetchingEnvironment environment) {
    String ipAddress = environment.getGraphQlContext().get(CONTEXT_KEY);
    if (ipAddress == null) {
      throw new IllegalStateException(
          "The client address was not captured for this GraphQL request.");
    }

    return ipAddress;
  }
}
