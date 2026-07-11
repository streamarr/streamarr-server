package com.streamarr.server.support.security;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.services.auth.TokenScope;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithAccountContext.Factory.class)
public @interface WithAccountContext {

  class Factory implements WithSecurityContextFactory<WithAccountContext> {

    @Override
    public SecurityContext createSecurityContext(WithAccountContext annotation) {
      return StreamarrSecurityContextFactory.contextFor(TokenScope.ACCOUNT, AccountRole.USER);
    }
  }
}
