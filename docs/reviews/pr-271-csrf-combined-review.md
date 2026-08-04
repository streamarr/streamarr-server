# PR #271 combined CSRF review

Review date: 2026-08-04

Pull request: [#271 — Require CSRF for browser cookie-mode login](https://github.com/streamarr/streamarr-server/pull/271)

Reviewed range: `1a10a8bb775b1bfb70cf236600a48707ee32b3f8..74f60092d05a7d868e7057a32cb91eb665d49019`

## Verdict

The core fix is correct: treating any Streamarr cookie, including `XSRF-TOKEN`, as evidence of a cookie-keeping browser closes the previously uncovered login-CSRF population without blocking native clients that do not retain cookies.

Neither review established an exploitable cross-site login attack at this commit. A fresh browser has no CSRF marker and is exempt, but the two browser-sendable request media types tested (`text/plain` and `application/x-www-form-urlencoded`) are rejected with `415` by setup, login, and refresh. A JSON request requires preflight, and the application does not grant a hostile origin an `Access-Control-Allow-Origin` response. The original review's P1 exploit claim was therefore a false positive; its underlying observation is still a valuable regression warning because these protections are implicit and largely untested in the PR.

The merged review does reproduce several real issues: missing explicit CSRF cookie attributes, fixed rather than sliding CSRF-cookie expiry, the Bruno collection regression, request-parameter token fallback, an over-broad Bearer exemption, the cookie-deletion customizer trap, and the framework's live `GET /logout` endpoint. The request-format boundary, cookie attributes, lifetime decision, regression coverage, and Bruno harness affect the merge decision; the rest are bounded hardening or maintenance risks.

## TDD remediation outcome

All before-merge and immediate-hardening recommendations were implemented on 2026-08-04 with a red-green cycle for each behavioral defect. Passing review probes were retained as permanent regression tests. The optional HMAC-bound token and `__Host-` rename remain a separate coordinated server/web design decision, as originally classified.

| Finding | Resolution | Permanent proof |
|---|---|---|
| F1 | Setup, login, and refresh now explicitly consume JSON; bodyless cookie refresh remains supported. | Six non-JSON `415` cases, hostile-preflight test, existing bodyless-refresh IT |
| F2 | The CSRF cookie is explicitly `Secure`, `SameSite=Lax`, script-readable, and path `/`. | Cookie-attribute integration test |
| F3 | The repository reissues a loaded CSRF token with a fresh refresh-token lifetime. | Refresh-rotation lifecycle IT |
| F4 | All identified negative and end-to-end gaps are now pinned. | Wrong-token, filter-order, GraphQL-cookie, setup-marker, media-type, CORS, attribute, and renewal tests |
| F5 | Bruno login echoes a retained `XSRF-TOKEN` through its pre-request script. | Retained-cookie bearer-login IT; Bruno CLI execution still requires a configured local collection environment |
| F6 | `_csrf` request parameters are ignored; only `X-XSRF-TOKEN` resolves. | Request-handler unit test (red returned `raw-token`, green returned `null`) |
| F7 | Bearer exemption applies only where the resolver accepts the Authorization header. | Matcher and filter-chain tests for protected routes versus login |
| F8 | A dedicated repository keeps deletion at `Max-Age=0`, independent of normal lifetime customization. | Repository unit test (original behavior reproduced `2592000`) |
| F9 | Spring's default logout filter is disabled; the application retains `POST /api/auth/logout`. | Authenticated `GET /logout` absence test (red `302`, green `404`) |
| F10 | Javadocs, ADR claims, comments, names, and literals now describe the actual boundary and lifecycle. | Static review plus formatter/checkstyle |
| F11 | No implementation change; remains optional defense-in-depth requiring coordinated client design. | Not applicable |

Final repository verification:

```text
./mvnw spotless:apply  -> BUILD SUCCESS
./mvnw verify          -> BUILD SUCCESS
Unit tests             -> 1,531 passed; 0 failures; 0 errors
Integration tests      ->   469 passed; 0 failures; 0 errors
```

## How the claims were tested

Temporary tests were added one at a time, run in isolation, and then removed so the branch was not left with deliberately failing tests. Integration probes used the real Spring application and the repository's PostgreSQL Testcontainer. `PASS` below means the asserted security contract already holds and the review item is a missing regression test, not a current production defect. `RED` means the desired contract failed against the PR head.

| ID | Probe | Expected contract | Observed at PR head | Assessment |
|---|---|---|---|---|
| P01 | Wrong `X-XSRF-TOKEN` beside the real cookie | `403` | `403` (`PASS`) | Coverage gap only; no bypass reproduced |
| P02 | `text/plain` and form-urlencoded bodies against setup, login, and refresh | Six `415` responses | Six `415` responses (`PASS`) | No current simple-request login-CSRF vector; behavior is implicit |
| P03 | Hostile JSON login CORS preflight | No `Access-Control-Allow-Origin` | Header absent (`PASS`) | No current credentialed cross-origin JSON path |
| P04 | CSRF cookie attributes from `GET /api/auth/status` | `Secure=true`, `SameSite=Lax` | `Secure=false`, `SameSite=null` (`RED`) | Confirmed configuration gap |
| P05 | Rotate the refresh cookie, then inspect the same response for a renewed CSRF cookie | New `XSRF-TOKEN` cookie | No CSRF `Set-Cookie` (`RED`) | Confirms fixed-vs-sliding lifetime mismatch |
| P06 | Non-exempt `POST /graphql` with access cookie, no CSRF token, and a throwing decoder | CSRF `403` before JWT decoding | `403`; decoder not invoked (`PASS`) | Filter order works, but the PR's existing tripwire was vacuous |
| P07 | Real GraphQL POST with a valid access cookie and no CSRF token | `403` | `403` (`PASS`) | Coverage gap only |
| P08 | Setup POST with an `XSRF-TOKEN` cookie and no header | `403` | `403` (`PASS`) | Coverage gap only |
| P09 | Bruno's sequenced status request followed by its headerless bearer-mode login | Login remains `200` | Login returned `403` (`RED`) | Confirmed collection/E2E regression |
| P10 | Correct masked token supplied only as `_csrf` request parameter | Resolver returns `null` | Resolver returned the raw token (`RED`) | Confirms parameter fallback |
| P11 | `CookieCsrfTokenRepository.saveToken(null, ...)` with the PR's 30-day customizer | Deletion cookie has `Max-Age=0` | `Max-Age=2592000` (`RED`) | Confirmed future deletion trap; dead path today |
| P12 | Login carries `XSRF-TOKEN` plus a syntactically valid Bearer header that the auth resolver ignores on this path | CSRF matcher applies | Matcher returned `false` (`RED`) | Confirmed exemption mismatch; not cross-site exploitable without CORS |
| P13 | `GET /logout` through the configured filter chain | No framework endpoint (`404`) | `302` (`RED`) | Confirms surprising default logout surface |

Representative failure evidence:

```text
P04: expected Secure true but was false; expected SameSite "Lax" but was null
P05: expected refresh response XSRF-TOKEN cookie not to be null
P09: expected status 200 but was 403
P10: expected null but resolver returned "raw-token"
P11: expected Max-Age 0 but was 2592000
P12: expected matcher true but was false
P13: expected status 404 but was 302
```

The source-only observations (stale prose, absent `consumes`, unsigned double-submit, cookie naming, and comment/test wording) do not have honest failing runtime tests. Optional controls such as HMAC signing and a `__Host-` prefix were assessed as design hardening, not mislabeled as current defects.

After removing the temporary probes, the PR's original focused suites were rerun:

```shell
./mvnw -Dtest=SecurityConfigTest,StreamarrCookieCsrfMatcherTest \
  -Dit.test=SecurityFilterChainIT verify
```

Result: `BUILD SUCCESS`; 16 unit tests and 20 integration tests passed, with zero failures or errors.

## Combined findings

### F1 — The fresh-browser path is safe today, but its boundary is implicit

Status: confirmed regression risk; the original exploitable-P1 classification is withdrawn.

`StreamarrCookieCsrfMatcher` exempts a request when it carries no Streamarr cookie. That means first-contact login safety depends on the browser being unable to submit an accepted credential body cross-origin. The temporary probes showed that Spring currently rejects both simple cross-origin media types with `415` on all three public credential mutations, while a hostile JSON preflight receives no CORS grant.

The controller mappings do not declare `consumes = APPLICATION_JSON_VALUE`, however, so the boundary currently comes from message-converter behavior. A future converter, form binding, or CORS change could invalidate the security argument without touching the CSRF matcher. The ADR also overstates the marker cookie as the complete solution: the marker covers browsers that have already contacted the origin; JSON-only plus same-origin deployment covers first-contact browsers.

Impact now: no demonstrated exploit.

Impact after an unreviewed transport change: login CSRF could become possible for a fresh browser.

### F2 — The CSRF cookie has no explicit `Secure` or `SameSite` policy

Status: confirmed by P04; fix before merge.

The repository customizer sets only `Max-Age`. The emitted cookie on the integration-test HTTP request was:

```text
XSRF-TOKEN=<value>; Path=/; Max-Age=2592000
```

Its Servlet cookie had `secure=false` and no `SameSite` attribute. This differs from both auth cookies, which explicitly use `Secure; HttpOnly; SameSite=Strict`. Since `application.yml` defaults `server.forward-headers-strategy` to `none`, relying on `request.isSecure()` is fragile behind TLS termination.

The CSRF cookie is not a credential, so this is not equivalent to exposing the access or refresh cookie. It does make transport and cookie-planting behavior weaker than the documented browser contract.

### F3 — CSRF expiry is fixed while auth-cookie expiry slides

Status: confirmed by P05; important correctness issue.

The CSRF cookie is written when the repository finds no existing token. A present token is loaded rather than re-saved, so its 30-day expiry remains anchored to first issuance. Login and refresh reissue the auth cookies with a fresh full refresh-token TTL. The existing integration test compares `Max-Age` values at one instant and therefore cannot detect the divergence over time.

Impact: after the CSRF cookie expires while a rotated refresh cookie remains live, the next unsafe cookie request fails with `403` until a safe request obtains a new CSRF cookie. This is a session-liveness failure, not a CSRF bypass.

The ADR statement that the cookies remain “in step by construction” is false unless the CSRF cookie is renewed with credential rotation.

### F4 — Important negative and end-to-end paths are not pinned

Status: confirmed coverage gaps; current behavior passed P01, P06, P07, and P08.

Add permanent tests for:

- a wrong token returning `403`;
- non-JSON setup/login/refresh returning `415`;
- hostile JSON preflight receiving no CORS grant;
- CSRF rejection occurring before access-cookie JWT decoding on a non-exempt path;
- cookie-authenticated GraphQL POST without a token returning `403`;
- setup with a marker cookie but no token returning `403`;
- explicit CSRF cookie attributes;
- sliding CSRF-cookie renewal, once implemented.

The current throwing `JwtDecoder` in `SecurityConfigTest` is not an ordering tripwire because every probe targets `/api/auth/login`, a path on which `StreamarrBearerTokenResolver` returns before resolving any token. P06 demonstrates the missing falsifiable shape.

### F5 — The committed Bruno sequence now fails closed

Status: confirmed by P09; fix before merge if this collection is a supported E2E harness.

`Get Auth Status.bru` is sequence 1 and mints `XSRF-TOKEN`. `Login.bru` is sequence 3, retains `auth: none`, and does not echo the CSRF cookie in `X-XSRF-TOKEN`. Reproducing that cookie-jar sequence returned `403` before credentials were evaluated.

This is expected server behavior under the new contract. The defect is in the collection: it must echo the cookie value or deliberately clear/disable its cookie jar before native-style bearer login. Echoing the header exercises the browser contract and is the preferred E2E path.

### F6 — The custom handler accepts a request-parameter CSRF token

Status: confirmed by P10; highest-value defense-in-depth follow-up.

When the header is absent, `SpaCookieCsrfTokenRequestHandler` delegates resolution to `XorCsrfTokenRequestAttributeHandler`, which also accepts the `_csrf` request parameter. The probe supplied a valid masked parameter with no header and the handler resolved it to the raw token.

The SPA contract uses only `X-XSRF-TOKEN`. Restricting resolution to that header removes an unnecessary form-body carrier, keeps the “security filter before body parsing” argument precise, and reduces the usefulness of cookie tossing if another transport boundary later weakens.

No current exploit was reproduced because the accepted public credential endpoints still reject cross-site form media types and no hostile CORS origin is allowed.

### F7 — Bearer syntax exempts requests even where Bearer authentication is ignored

Status: confirmed by P12; bounded hardening issue.

The CSRF matcher exempts any unsafe request with a nonblank Bearer-shaped header. The authentication resolver deliberately ignores all credentials on status, setup, login, refresh, JWKS, and health paths. On login, therefore, `Authorization: Bearer ignored` suppresses CSRF even though it cannot authenticate the request.

This is not a browser exploit today because author-controlled `Authorization` requires preflight and the server grants no hostile CORS origin. Scope the exemption to paths where the resolver can actually consume the header, or explicitly document and test the CORS dependency.

### F8 — The cookie customizer overrides deletion semantics

Status: confirmed by P11; dead path today.

Spring applies the deletion cookie's `Max-Age=0` before invoking the configured cookie customizer. The unconditional 30-day customizer then overwrites it. Calling `saveToken(null, ...)` produced an empty cookie with `Max-Age=2592000`.

The PR removes the framework CSRF configurer and the application logout is hand-written, so no current application path calls this repository deletion behavior. It is still a maintenance trap for future logout or authentication-strategy wiring. Preserve zero-age deletion in a custom repository/wrapper or add an explicit constraint comment and tripwire.

### F9 — The framework default exposes `GET /logout`

Status: confirmed by P13; low-severity cleanup.

Because the normal CSRF configurer is removed and logout is not explicitly disabled, Spring's default logout filter handles `GET /logout` and returns `302`. Streamarr's real logout API is `POST /api/auth/logout`; the framework endpoint neither expresses that contract nor revokes the application's refresh-token family.

Disable the default logout filter and pin `/logout` as absent.

### F10 — Documentation and test wording have drifted

Status: confirmed by static inspection.

- `SecurityConfig` still says CSRF protects “exactly the cookie-authenticated requests,” while the new matcher intentionally protects the broader cookie-carrying population.
- ADR 0016 says the CSRF and auth-cookie lifetimes stay in step by construction; P05 disproves that over time.
- The ADR should state that same-origin deployment, JSON-only request binding, and absent hostile CORS are load-bearing for a first-contact browser.
- The matcher comment should not imply that `XSRF-TOKEN` alone covers a browser that has never contacted the origin.
- The “first try” `403` narrative is possible but not certain if the SPA performs a safe boot request first.
- Device-pairing text should be marked as forward-looking while its endpoint is not present at this commit.
- Several display names still say “no auth cookie” where the contract is “no Streamarr cookie”; one display name contains an unnecessary string concatenation; one test uses a literal cookie name instead of `AuthCookies.CSRF_COOKIE`.

### F11 — Signed double-submit and `__Host-` naming are optional hardening

Status: `__Host-` naming addressed in a coordinated follow-up; HMAC binding remains optional.

The design still uses a framework-generated, unsigned double-submit token. The cookie now uses `__Host-XSRF-TOKEN`, and the real filter chain pins `Secure`, `Path=/`, no `Domain`, and rejection of an attacker-chosen unprefixed cookie. The web client recognizes the new name while retaining the unprefixed development fallback; the server remains authoritative about which name is valid in each mode.

HMAC-binding the token remains a separate deliberate hardening decision.

## Merged recommendations and disposition

### Before merge

1. [x] Make the request-format boundary explicit and retain P02/P03 as permanent integration tests.
2. [x] Configure explicit `Secure` and `SameSite=Lax` attributes and test them.
3. [x] Add the passing security regression tests from F4.
4. [x] Repair Bruno login by echoing the retained cookie into `X-XSRF-TOKEN`; a configured collection run remains an environment-level verification step.
5. [x] Implement sliding CSRF-cookie renewal and replace the single-instant claim with lifecycle proof.
6. [x] Correct the stale `SecurityConfig` javadoc and load-bearing ADR claims.

### Immediate hardening follow-up

1. [x] Make `SpaCookieCsrfTokenRequestHandler` header-only and pin that `_csrf` parameters are ignored.
2. [x] Scope Bearer-based CSRF exemption to routes where `StreamarrBearerTokenResolver` can consume the header.
3. [x] Disable Spring's default logout endpoint and retain only `POST /api/auth/logout`.
4. [x] Preserve `Max-Age=0` and retain P11 as a repository-level tripwire.
5. [x] Clean up the comment and test wording listed in F10.

### Optional defense-in-depth

1. [x] Adopt `__Host-XSRF-TOKEN` as a coordinated server/web change, with the unprefixed name reserved for explicitly insecure development.
2. [ ] Evaluate HMAC-bound double-submit tokens independently.

Keep the auth cookies `SameSite=Strict`; do not change the CSRF cookie to `SameSite=None` merely to make it accompany cross-site requests. Cross-site cookie delivery is not the protection objective.

## Confirmed strengths

- The matcher now covers login when a browser already has only the marker cookie.
- A missing token is rejected before credential verification, while a correctly echoed token permits cookie-mode login even beside a stale access cookie.
- Native bearer-mode login and refresh remain usable when the client truly does not keep cookies.
- Cookie-name reuse through `AuthCookies.CSRF_COOKIE` prevents repository/matcher drift.
- CSRF failures use a fixed forbidden response and do not leak whether credentials were valid.
- The ADR records the major rejected alternatives and the Spring Security 7 cookie-lifetime API change.

## References

- [OWASP CSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)
- [Spring Security CSRF reference](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Spring Security `CookieCsrfTokenRepository` API](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/web/csrf/CookieCsrfTokenRepository.html)
- [Bruno cookie handling](https://docs.usebruno.com/send-requests/res-data-cookies/cookies)
