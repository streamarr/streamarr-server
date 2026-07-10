# Streamarr Bruno collection

Use the `local` environment, then set `AUTH_EMAIL`, `AUTH_PASSWORD`, and `TMDB_TOKEN`
as secrets in Bruno. `AUTH_NEW_PASSWORD` is only needed for the manual password-change
request. Replace the non-secret resource IDs and local library path as needed.

For a configured server, run `Auth/Public/Login` before protected requests. On a fresh
server, run `Auth/Public/Setup (one time)` instead. Both requests keep the returned access and refresh
tokens in Bruno runtime variables; issued tokens are never persisted in the collection.
`Auth/Public/Refresh Tokens` replaces the access token and captures a rotated refresh token when
the response includes one.

Requests under `GraphQL` inherit `Bearer {{ACCESS_TOKEN}}`. The `TMDB` folder has its own
inherited bearer configuration backed by the separate `TMDB_TOKEN` secret.
The token in a stream session's `streamUrl` is a short-lived playback token, not the API
access token.

Requests tagged `manual` or `destructive` intentionally change durable state. Exclude
them from broad collection runs unless that behavior is desired.
