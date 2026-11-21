# OAuth spike: the phone as its own MCP client

A throwaway Android app that proves the phone can sign in to remote MCP servers by itself
(OAuth 2.1: discovery, dynamic client registration, PKCE, Custom Tabs) and call their tools with the
official [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk), with no API keys
pasted by hand. It talks to PostHog (`https://mcp.posthog.com/mcp`) and Tally (`https://api.tally.so/mcp`).
The real app will be built separately; this code is a reference, not its base.

## Get it

CI builds a debug APK on every push that touches this folder and attaches it to a
[pre-release](https://github.com/rawmahn/datchart/releases) `spike-oauth-<n>`. Each build has its own
debug key, so uninstall the previous one before installing a new one.

Locally: `./gradlew testDebugUnitTest assembleDebug` (JDK 17+, Android SDK 35).

## What to try on the phone

1. Check the title says the build you installed. Pick a redirect (loopback is the default), tap
   **Sign in** on a server, log in in the Custom Tab. For Tally, if loopback doesn't come back, try the
   **https bounce page**.
2. **List tools**, then **Call tool** (the defaults are one read-only call per server).
3. **Refresh token** to check refresh; the app also refreshes by itself when the access token has expired.
4. **Copy log** and paste it into the issue.

For PostHog, try both redirects: whether the app comes back by itself after a loopback redirect
(Android may block that background start; the page then shows a "Back to Datchart" link) is one of the
questions.

## Findings so far (checked from a server on 2026-11-20, before the phone run)

| | PostHog | Tally |
|---|---|---|
| 401 on an unauthenticated `initialize` | `WWW-Authenticate: Bearer resource_metadata=…` | same, plus `scope="mcp"` |
| Protected-resource metadata | `/.well-known/oauth-protected-resource/mcp` | same |
| Authorization server | `https://oauth.posthog.com` | issuer `https://api.tally.so/mcp`, metadata at `/.well-known/oauth-authorization-server/mcp` (RFC 8414 path insertion) |
| DCR (`registration_endpoint`) | yes, public client (`token_endpoint_auth_method: none`) | yes, public client |
| PKCE | S256 only | S256 only |
| Custom scheme redirect (`com.example:/oauth`, `app://…`) | accepted | **rejected**: "https:// or http://localhost or urn:ietf:wg:oauth:2.0:oob" |
| Loopback `http://127.0.0.1:<port>/…` and `http://localhost:<port>/…` | accepted | accepted |
| https redirect | accepted | accepted |
| Scopes | ~150 fine-grained (`query:read`, `insight:read`, …) | `mcp` |
| Tool shape | one `exec` tool taking CLI-style commands (`call --json execute-sql {…}`) | one tool per action (`list_forms`, …) |

So the redirect that works for both is either **loopback** (the app listens on `127.0.0.1:47823`)
or an **https App Link**. An App Link needs `/.well-known/assetlinks.json` at the root of its host,
which a project page on `rawmahn.github.io/datchart` can't serve (it would need the
`rawmahn.github.io` repo or a custom domain).

## First phone run (2026-11-21, build 1)

The PostHog sign-in reached the token exchange and failed with `Unable to resolve host
"oauth.posthog.com": No address associated with hostname`. Discovery against the same host had worked a
moment earlier. Most likely cause: with the loopback redirect, build 1 swapped the code for tokens right
when the redirect arrived, while the app was still behind the Custom Tab, and Android cuts a background
app's network (Data Saver, battery "restricted", newer Android's own limits). That shows up as this DNS error.
Since build 2 the redirect is only stored. The exchange runs when the app is back in front (`onResume`),
and is retried on the next resume if the network fails. The log is kept on disk and starts with the
phone's Android version and its Data Saver / background settings.
The real app has to do the same: no token calls from the background without a foreground service or WorkManager.

## Second phone run (2026-11-21, still build 1)

The log pasted for "build 2" came from build 1: it has build 1's `loopback: got /callback` line, no
`app started (build …)` header, and reuses the PostHog client registered in the first run, which a
fresh install could not have. Build 2 most likely never installed: each build is signed with its own
debug key, so Android refuses it until the old one is uninstalled. What this run still showed:

- **PostHog works end to end over loopback.** Token exchange succeeded this time (so the background
  network cut is intermittent, not a rule), `expires_in=604800` (7 days), a refresh token is issued, the
  granted scopes are exactly the requested ones, and `exec` with `execute-sql` ran in ~1 s with
  `query:read` + `project:read` (among the requested set). Refresh was not tried.
- **Tally never redirected back.** Three sign-ins, each ~24 s in the tab, and the loopback listener got
  no `/callback` (build 1 did not log other connections). How Tally's flow works, read from its pages:
  `api.tally.so/oauth/authorize` 302s to `tally.so/oauth/authorize`, which sends a signed-out browser to
  `/login` and keeps the request in a `returnTo` cookie for 10 minutes. The consent page then POSTs a
  form to `api.tally.so/oauth/authorize`, which should redirect to the `redirect_uri`; if the page has not
  been left 5 s after **Accept**, it switches to a "success" message on its own. So a "success" page
  with nothing arriving in the app means the browser never reached `127.0.0.1`.

Build 3 makes the next run decisive: the build number is in the app's title and on the first line of
a copied log; the loopback listener logs every connection (path and parameter names, never values);
coming back to the app while a sign-in still waits logs how long the tab was open; and a third redirect,
**https bounce page** (`https://rawmahn.github.io/datchart/oauth/`, `site/oauth/index.html`), passes the
result to the app through its scheme. It needs no App Link (so no `assetlinks.json`), and it skips the
browser-to-loopback hop. The code passes through a public static page, which PKCE makes useless to
anyone else.

Still to find out on the phone: whether the login pages work in a Custom Tab, token lifetimes,
whether refresh works and rotates the refresh token, which PostHog scopes the `exec` tool needs,
and whether the loopback redirect brings the app back by itself.
