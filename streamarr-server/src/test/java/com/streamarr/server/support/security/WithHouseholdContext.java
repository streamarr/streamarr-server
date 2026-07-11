package com.streamarr.server.support.security;

import com.streamarr.server.domain.auth.AccountRole;
import com.streamarr.server.services.auth.TokenScope;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithHouseholdContext.Factory.class)
public @interface WithHouseholdContext {

  class Factory implements WithSecurityContextFactory<WithHouseholdContext> {

    @Override
    public SecurityContext createSecurityContext(WithHouseholdContext annotation) {
      return StreamarrSecurityContextFactory.contextFor(TokenScope.HOUSEHOLD, AccountRole.USER);
    }
  }
}
