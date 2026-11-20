# Datchart

An Android home-screen widget for dashboards (KPIs, line and bar charts), set up by your own
AI agent. The widget gets its data straight from the MCP servers your agent already uses
(PostHog, Tally, observability tools): the phone signs in to each server itself, and the
agent only writes a small recipe once (which tool to call, plus a jq filter that turns the
result into chart data). No backend, no API keys pasted around, no LLM on the phone.

Early days: the app doesn't exist yet. What's here:

- [specs/0001-widget-recipe.md](specs/0001-widget-recipe.md): the recipe format, the link
  that carries a recipe to the phone, the link page and the import safety rules.
- [examples/](examples/): real recipes with the tool results they were written against.
- [tools/link.py](tools/link.py): `encode` a recipe into a link, `decode` a link back.
- [tools/check_examples.py](tools/check_examples.py): checks the examples (needs `jq`).
- [site/i/](site/i/): the link page, served at `https://rawmahn.github.io/datchart/i/`.

```bash
python3 tools/check_examples.py
python3 tools/link.py encode examples/xpl-exports-24h.json
```

Licensed under [Apache-2.0](LICENSE). `site/i/qrcode.js` is
[qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator) by Kazuhiko Arase, MIT.
