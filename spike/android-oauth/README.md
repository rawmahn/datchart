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

1. Pick a redirect (loopback is the default), tap **Sign in** on a server, log in in the Custom Tab.
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

Still to find out on the phone: whether the login pages work in a Custom Tab, token lifetimes,
whether refresh works and rotates the refresh token, which PostHog scopes the `exec` tool needs,
and whether the loopback redirect brings the app back by itself.
