package com.streamarr.server.graphql;

import com.streamarr.server.web.ClientIpAddressResolver;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class ClientIpAddressGraphQlInterceptor implements WebGraphQlInterceptor {

  private static final String CONTEXT_KEY = ClientIpAddressGraphQlInterceptor.class.getName();

  private final ClientIpAddressResolver clientIpAddressResolver;

  @Override
  public Mono<WebGraphQlResponse> intercept(
      WebGraphQlRequest request, WebGraphQlInterceptor.Chain chain) {
    var ipAddress = clientIpAddressResolver.resolve();
    request.configureExecutionInput(
        (input, builder) ->
            builder.graphQLContext(context -> context.put(CONTEXT_KEY, ipAddress)).build());
    return chain.next(request);
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
