package com.streamarr.server.support.security;

import com.streamarr.server.services.auth.TokenScope;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

@Retention(RetentionPolicy.RUNTIME)
@WithSecurityContext(factory = WithProfileContext.Factory.class)
public @interface WithProfileContext {

  /** The token's ServerAdmin display snapshot; never authority (the live fact is). */
  boolean serverAdmin() default false;

  class Factory implements WithSecurityContextFactory<WithProfileContext> {

    @Override
    public SecurityContext createSecurityContext(WithProfileContext annotation) {
      return StreamarrSecurityContextFactory.contextFor(
          TokenScope.PROFILE, annotation.serverAdmin());
    }
  }
}
