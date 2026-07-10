package com.streamarr.server.services.auth;

import com.streamarr.server.config.security.AuthTokenProperties;
import com.streamarr.server.exceptions.HouseholdAccessDeniedException;
import com.streamarr.server.exceptions.ProfileAccessDeniedException;
import com.streamarr.server.repositories.auth.AccountProfileRepository;
import com.streamarr.server.repositories.auth.HouseholdMembershipRepository;
import com.streamarr.server.repositories.auth.ProfileRepository;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AccessTokenIssuer {

  private final JwtEncoder jwtEncoder;
  private final AuthTokenProperties properties;
  private final Clock clock;
  private final HouseholdMembershipRepository membershipRepository;
  private final ProfileRepository profileRepository;
  private final AccountProfileRepository accountProfileRepository;

  public AccessToken issue(TokenContext context) {
    var scope = resolveScope(context);
    // JWT timestamps carry whole seconds; truncate so expiresAt matches the encoded exp claim.
    var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    var expiresAt = now.plus(properties.accessTokenTtl());

    var claims =
        JwtClaimsSet.builder()
            .issuer(TokenContract.ISSUER)
            .id(UUID.randomUUID().toString())
            .subject(context.account().getId().toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim(TokenClaims.ROLE, context.account().getAccountRole().name())
            .claim(TokenClaims.SESSION_ID, context.session().getId().toString())
            .claim(TokenClaims.SESSION_VERSION, context.session().getSessionVersion())
            .claim(TokenClaims.SCOPE, scope.claimValue());

    if (scope != TokenScope.ACCOUNT) {
      addHouseholdClaims(claims, context);
    }
    if (scope == TokenScope.PROFILE) {
      addProfileClaims(claims, context);
    }

    var jwt =
        jwtEncoder.encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.ES256).build(), claims.build()));

    return AccessToken.builder()
        .value(jwt.getTokenValue())
        .expiresAt(expiresAt)
        .scope(scope)
        .build();
  }

  private TokenScope resolveScope(TokenContext context) {
    // TokenContext's constructor guarantees a profile id always rides a household id.
    if (context.profileId() != null) {
      return TokenScope.PROFILE;
    }

    if (context.householdId() != null) {
      return TokenScope.HOUSEHOLD;
    }

    return TokenScope.ACCOUNT;
  }

  private void addHouseholdClaims(JwtClaimsSet.Builder claims, TokenContext context) {
    var membership =
        membershipRepository
            .findByAccountIdAndHouseholdId(context.account().getId(), context.householdId())
            .orElseThrow(HouseholdAccessDeniedException::new);

    claims
        .claim(TokenClaims.HOUSEHOLD_ID, context.householdId().toString())
        .claim(TokenClaims.HOUSEHOLD_ROLE, membership.getHouseholdRole().name())
        .claim(TokenClaims.MEMBERSHIP_VERSION, membership.getMembershipVersion());
  }

  private void addProfileClaims(JwtClaimsSet.Builder claims, TokenContext context) {
    // A single account_profile row structurally implies membership AND profile-in-household.
    accountProfileRepository
        .findByAccountIdAndHouseholdIdAndProfileId(
            context.account().getId(), context.householdId(), context.profileId())
        .orElseThrow(ProfileAccessDeniedException::new);

    var profile =
        profileRepository
            .findById(context.profileId())
            .orElseThrow(ProfileAccessDeniedException::new);

    claims
        .claim(TokenClaims.PROFILE_ID, context.profileId().toString())
        .claim(TokenClaims.POLICY_VERSION, profile.getPolicyVersion());
  }
}
