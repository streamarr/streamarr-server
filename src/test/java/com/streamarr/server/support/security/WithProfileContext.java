package com.streamarr.server.support.security;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.services.auth.TokenScope;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithProfileContext.Factory.class)
public @interface WithProfileContext {

  class Factory implements WithSecurityContextFactory<WithProfileContext> {

    @Override
    public SecurityContext createSecurityContext(WithProfileContext annotation) {
      return StreamarrSecurityContextFactory.contextFor(TokenScope.PROFILE, AccountRole.USER);
    }
  }
}
