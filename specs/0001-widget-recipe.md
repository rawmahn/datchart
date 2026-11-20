# 0001: Widget recipe v0 and the link that carries it

Status: draft v0 (2026-11-20). Nothing reads it yet: the app comes later (OAuth spike first).

Datchart is an Android home-screen widget that shows dashboards. The user's own agent
(Claude Code, Codex, ChatGPT...) sets each widget up once by writing a **recipe**: which
MCP server and tool to call, with what arguments, and a **jq** filter that turns the tool's
result into chart data. The phone signs in to that MCP server itself and replays the recipe
on every refresh, with no LLM involved. The recipe gets to the phone as a **link**: tapped on
the phone it opens the app, opened on a laptop it shows a QR code for the phone's camera.

## Recipe

One JSON object per widget:

```json
{
  "v": 0,
  "id": "xpl-exports-24h",
  "title": "Exports, 24 h",
  "kind": "kpi",
  "source": {
    "url": "https://mcp.posthog.com/mcp",
    "tool": "exec",
    "args": { "command": "call --json execute-sql {\"query\": \"SELECT ...\"}" }
  },
  "jq": ".results | split(\"\\n\")[1] | split(\"|\") | map(tonumber) | {value: .[0], previous: .[1]}",
  "format": { "number": "plain", "delta": "percent" }
}
```

| Field | Required | Meaning |
|---|---|---|
| `v` | yes | Format version, `0` for this spec. The app refuses a version it doesn't know. |
| `id` | yes | `[a-z0-9][a-z0-9-]{0,63}`. Importing a recipe with an `id` the app already has **replaces** that widget; a new `id` adds one. |
| `title` | yes | 1-40 characters, shown on the widget. |
| `kind` | yes | `kpi`, `line` or `bar`. |
| `source.url` | yes | Streamable HTTP endpoint of the MCP server, `https://` only. |
| `source.tool` | yes | Tool name, exactly as the server's `tools/list` gives it. |
| `source.args` | no | The tool's `arguments` object, sent as is. Default `{}`. |
| `jq` | yes | jq program applied to the tool result (below). Must produce exactly one value. |
| `format` | no | How to print numbers (below). Default `{}`. |

Unknown top-level fields are ignored, so later versions can add fields without breaking
older apps as long as `v` stays the same.

### What jq gets

The app calls `tools/call` with `source.tool` and `source.args`, then feeds jq one JSON value:

1. `result.structuredContent`, when the server sends it;
2. otherwise the text of the `text` items of `result.content`, joined with `\n`, parsed as
   JSON when it parses;
3. otherwise that text as a JSON string.

A result with `isError: true` never reaches jq: the widget shows the error instead.

The agent writes the jq against what this exact call returns: it runs the tool and the jq
itself before making the link, so the shape is known, not guessed. jq runs on the phone in
[jackson-jq](https://github.com/eiiches/jackson-jq), which implements most of jq 1.6: keep to
core filters (paths, `map`, `split`, `tonumber`, `select`, `sort_by`, `group_by`, `as`
bindings, object and array construction). The examples below ran in jq 1.7; checking them in
jackson-jq is part of the app work.

### What jq must produce

**`kpi`**: one number and, optionally, the one to compare it with.

```json
{ "value": 24, "previous": 19 }
```

`value` is a number. `previous` is a number or `null`/missing (then no delta is shown).

**`line`** and **`bar`**: labelled series. Both kinds take the same shape, so switching a
widget between them is a one-word change.

```json
{ "x": ["2026-10-21", "2026-10-22", "..."], "series": [{ "name": "users", "values": [8, 9, "..."] }] }
```

`x` is a non-empty array of labels (strings; `YYYY-MM-DD` dates are drawn as a time axis).
`series` is a non-empty array; each `values` array is as long as `x` and holds numbers or
`null` (a gap). v1 draws at most 4 series; more are cut off with a note.

### `format`

| Key | Values | Default |
|---|---|---|
| `number` | `plain` (1234), `compact` (1.2K), `percent` (value × 100 + `%`) | `compact` |
| `decimals` | 0-4 | 0 for `plain`/`compact` under 1000, else 1 |
| `prefix`, `suffix` | short strings, e.g. `"$"`, `" ms"` | none |
| `delta` | KPI only: `percent`, `absolute`, `none` | `percent` |
| `good` | KPI only: `up` or `down`, which direction is drawn green | `up` |

## Link

```
https://rawmahn.github.io/datchart/i/#<payload>
```

`payload` = base64url without padding (RFC 4648 §5) of the **raw DEFLATE** stream
(RFC 1951, no zlib or gzip header) of the recipe as minified UTF-8 JSON.

- Raw DEFLATE is what all three ends have built in: Java `Inflater(true)`, the browser's
  `DecompressionStream('deflate-raw')`, Python `zlib` with `wbits=-15`.
- The payload sits in the **fragment**, which browsers never send to a server, so the recipe
  (queries, event names, server URLs) is never logged by GitHub Pages.
- **One link = one widget.** Import adds it or replaces the widget with the same `id`. There
  are no remote updates in v0: a changed widget is a new link.
- **Size budget: 1500 bytes for the whole link.** That keeps the QR scannable off a laptop
  screen (roughly version 30 at error correction L in the worst case; the three examples are
  ~500-580 bytes, version ~17 at M). `tools/link.py encode` warns over budget; a recipe that
  doesn't fit needs a shorter query, not a longer link.
- The trailing slash in `/i/` matters: GitHub Pages redirects `/i` to `/i/`, and the QR on
  the link page is drawn from the page's own address, so the canonical link already has it.

`python3 tools/link.py encode <recipe.json>` makes a link and `decode <link>` reads one back
(standard library only, so any agent can run it).

## Link page

`site/i/` is a static page on GitHub Pages (deployed by `.github/workflows/pages.yml`):

- It decodes the fragment **on the device** and shows title, kind, server host and tool, with
  the full recipe behind a fold. Its Content-Security-Policy allows no network requests
  (`connect-src 'none'`), and it sends no referrer.
- **On a desktop** it draws a QR code of its own address (vendored
  [qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator), MIT): error
  correction M while the code stays at version 25 or below, L above that. A "Copy link"
  button sits under it.
- **On a phone** the link should never reach this page: the app claims it as an
  [App Link](https://developer.android.com/training/app-links). If the page does load there,
  the app isn't installed, so it points to GitHub Releases.

**App Link verification needs the host root.** Android reads
`https://<host>/.well-known/assetlinks.json` from the root of the host, and a project site
like `rawmahn.github.io/datchart` can't serve that path: it belongs to the user site
(`rawmahn/rawmahn.github.io`) or to a custom domain. Until one of those serves the file with
the app's package name and signing-key SHA-256, the app registers the link with
`autoVerify` off and Android shows a chooser instead of opening the app directly. Decide this
when the first signed APK exists (the fingerprint goes in the file).

## Import confirmation

Tokens live on the phone, so a link from someone else could ask the app to call a tool that
changes data. Import therefore always shows a confirmation screen before the first call:

- **Always shown:** title, kind, the server's host, the tool name and the arguments (full
  text behind a fold), and whether this adds a widget or replaces one (`id` match, with the
  old title).
- **Write warning:** the app reads the tool's entry from the server's `tools/list`. Unless
  its `annotations.readOnlyHint` is `true`, the screen says in red that this tool may
  change data on that server, and the confirm button is not the default one.
- **New server:** when the app has no token for the host yet, confirming starts its sign-in
  (MCP OAuth) first; the widget is saved only after the sign-in and a first successful call.
- **Gateway tools.** Some servers expose one tool that runs others. PostHog's MCP server, as
  seen from an agent on 2026-11-20, offers a single `exec` tool taking a CLI-style `command`
  (`call [--json] <tool> <json>`), so `exec` itself can't be read-only. For `exec` on a
  PostHog host the app parses the command, refuses `--confirm` (PostHog's flag for
  destructive tools), and applies the rule above to the inner tool's annotations (from
  `exec` `info --json <tool>`). Other gateways get the plain rule (always warn).

## Examples

Three real widgets for Explainium's PostHog project, in `examples/`, each as `<id>.json` with
the tool result it was written against in `<id>.sample.json` (shape from a real run on
2026-11-20 through PostHog's MCP server; numbers changed). `python3 tools/check_examples.py`
checks every recipe's fields, runs its jq on the sample, checks the output shape for its
kind, and checks that the link round-trips and fits the budget. CI runs it on every push.

| Recipe | Kind | What | Link |
|---|---|---|---|
| `xpl-exports-24h` | kpi | Finished video exports in the last 24 h vs the 24 h before | 498 B |
| `xpl-agent-users-30d` | line | People using the agent API per day, last 30 complete days | 542 B |
| `xpl-create-push-export` | bar | People who created a project, pushed code, finished an export (30 d) | 581 B |

What the PostHog runs showed:

- With `--json`, `execute-sql` returns `{"query": ..., "results": "<header>\n<row>\n..."}`,
  where `results` is a **pipe-separated table in a string**, not a JSON array. The jq splits
  it on `\n` and `|` (see the examples). Values containing `|` or newlines would break that,
  so recipes select numbers, dates and short labels only.
- The query runs in the MCP session's active PostHog project. How the phone pins a project
  (a header, a URL parameter, or a `switch-project` call) is still open: check it during the
  OAuth spike, then add it to `source` if needed.

## Open questions (for the app work)

- Does the phone's MCP client see the same `exec`-only tool list as an agent, or individual
  tools (then recipes would call `execute-sql` directly)? Check during the OAuth spike.
- jackson-jq compatibility of the example filters (not run yet).
- Where `assetlinks.json` lives (user site or a custom domain), see **Link page**.
