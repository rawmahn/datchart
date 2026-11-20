#!/usr/bin/env python3
"""Encode a widget recipe into a Datchart link, or decode a link back.

    python3 tools/link.py encode examples/xpl-exports-24h.json
    python3 tools/link.py decode 'https://rawmahn.github.io/datchart/i/#...'

Encoding (specs/0001-widget-recipe.md, "Link"): minified UTF-8 JSON ->
raw DEFLATE (RFC 1951, no zlib header) -> base64url without padding, in the
URL fragment. Standard library only, so any agent can run it.
"""
import base64
import json
import sys
import zlib

BASE = "https://rawmahn.github.io/datchart/i/"
MAX_LINK = 1500  # bytes; keeps the QR scannable off a laptop screen


def encode(recipe: dict, base: str = BASE) -> str:
    raw = json.dumps(recipe, separators=(",", ":"), ensure_ascii=False).encode()
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    packed = c.compress(raw) + c.flush()
    return base + "#" + base64.urlsafe_b64encode(packed).decode().rstrip("=")


def decode(link: str) -> dict:
    frag = link.split("#", 1)[1] if "#" in link else link
    packed = base64.urlsafe_b64decode(frag + "=" * (-len(frag) % 4))
    return json.loads(zlib.decompress(packed, -15).decode())


def main(argv):
    if len(argv) != 3 or argv[1] not in ("encode", "decode"):
        print(__doc__, file=sys.stderr)
        return 2
    if argv[1] == "encode":
        with open(argv[2], encoding="utf-8") as f:
            link = encode(json.load(f))
        print(link)
        if len(link) > MAX_LINK:
            print(f"warning: link is {len(link)} bytes, over the {MAX_LINK} budget", file=sys.stderr)
            return 1
        return 0
    print(json.dumps(decode(argv[2]), indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
