# 0008: Refresh token in an HttpOnly cookie, handled by the gateway

Date: 2026-10-06 · Status: accepted

## Context

identity-service returns `refreshToken` in JSON and expects it back in JSON (ADR 0003). A browser app that
keeps it in `localStorage`/`sessionStorage` hands a 7-day credential to any XSS bug. Keeping it in memory
only means a page reload logs the user out.

## Decision

- The **access token** lives only in JavaScript memory (15 min, sent as `Authorization: Bearer`).
- The **refresh token** lives in a cookie: `refresh_token`, **HttpOnly** (no JS access), **Secure**,
  **SameSite=Strict** (never sent cross-site), **Path=/auth** (not sent with normal API calls), Max-Age 7 days.
- The **gateway** translates (`RefreshTokenCookie` filter on login/refresh/switch-org/logout):
  - response: `refreshToken` is removed from the JSON body and set as the cookie;
  - request: if the body has no `refreshToken`, the cookie's value is added;
  - a 401 from refresh/switch-org, and every logout, deletes the cookie.
- identity-service doesn't change: it stays a plain JSON API, and cookies are a browser concern handled at the edge.
- On page load the app calls `POST /auth/refresh` (the cookie is sent automatically) to get an access token.

## Why not other options

- `localStorage`/`sessionStorage`: simplest, but readable by XSS.
- Cookie set by identity-service: mixes browser concerns into the service. A future mobile client would
  want JSON.
- A full BFF (server-side session, no tokens in the browser at all): strongest, but a stateful session store
  in the gateway is more than this project needs now.

## Consequences

- CSRF: the cookie endpoints act on a cookie the browser sends automatically. SameSite=Strict stops
  cross-site requests from carrying it, and CORS rejects disallowed origins. Same-*site* origins
  (other ports on localhost) could still send it; that's acceptable locally.
- Through the gateway, the refresh token is never in a response body. curl users keep it in a cookie jar
  (`curl -c jar -b jar`). Behind the gateway, the identity API still uses JSON.
- An XSS bug can still act as the user while the page is open; it just can't take a long-lived token away.
- `REFRESH_COOKIE_SECURE=false` exists for plain-HTTP non-localhost setups; browsers allow Secure on `http://localhost`.
