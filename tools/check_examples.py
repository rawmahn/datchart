#!/usr/bin/env python3
"""Check every recipe in examples/ against specs/0001-widget-recipe.md.

For each examples/<id>.json: the fields are valid, the jq filter turns the
saved tool result (examples/<id>.sample.json) into the shape its kind needs,
and the link round-trips and stays within the size budget. Needs `jq` on PATH.
"""
import json
import pathlib
import re
import subprocess
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import link  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
KINDS = ("kpi", "line", "bar")
ID_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,63}$")


def num(x):
    return isinstance(x, (int, float)) and not isinstance(x, bool)


def check_recipe(r):
    errs = []
    if r.get("v") != 0:
        errs.append("v must be 0")
    if not ID_RE.match(str(r.get("id", ""))):
        errs.append("id must match " + ID_RE.pattern)
    if not isinstance(r.get("title"), str) or not 0 < len(r["title"]) <= 40:
        errs.append("title must be 1-40 characters")
    if r.get("kind") not in KINDS:
        errs.append(f"kind must be one of {KINDS}")
    src = r.get("source") or {}
    if not str(src.get("url", "")).startswith("https://"):
        errs.append("source.url must be https")
    if not isinstance(src.get("tool"), str) or not src["tool"]:
        errs.append("source.tool missing")
    if not isinstance(src.get("args", {}), dict):
        errs.append("source.args must be an object")
    if not isinstance(r.get("jq"), str) or not r["jq"]:
        errs.append("jq missing")
    if not isinstance(r.get("format", {}), dict):
        errs.append("format must be an object")
    return errs


def check_output(kind, out):
    if kind == "kpi":
        if not isinstance(out, dict) or not num(out.get("value")):
            return ["kpi output needs a numeric value"]
        if out.get("previous") is not None and not num(out["previous"]):
            return ["kpi previous must be a number or null"]
        return []
    if not isinstance(out, dict) or not isinstance(out.get("x"), list) or not out["x"]:
        return [f"{kind} output needs a non-empty x array"]
    series = out.get("series")
    if not isinstance(series, list) or not series:
        return [f"{kind} output needs a non-empty series array"]
    errs = []
    for s in series:
        vals = s.get("values") if isinstance(s, dict) else None
        if not isinstance(s.get("name"), str):
            errs.append("each series needs a name")
        if not isinstance(vals, list) or len(vals) != len(out["x"]):
            errs.append("series values must match x in length")
        elif not all(v is None or num(v) for v in vals):
            errs.append("series values must be numbers or null")
    return errs


def main():
    failed = False
    recipes = sorted(p for p in (ROOT / "examples").glob("*.json") if not p.name.endswith(".sample.json"))
    if not recipes:
        print("no recipes in examples/")
        return 1
    for path in recipes:
        r = json.loads(path.read_text(encoding="utf-8"))
        errs = check_recipe(r)
        if path.stem != r.get("id"):
            errs.append("file name must be <id>.json")
        sample = path.with_name(path.stem + ".sample.json")
        if not sample.exists():
            errs.append(f"missing {sample.name}")
        elif not errs:
            p = subprocess.run(["jq", "-c", r["jq"], str(sample)], capture_output=True, text=True)
            if p.returncode:
                errs.append("jq failed: " + p.stderr.strip())
            else:
                errs += check_output(r["kind"], json.loads(p.stdout))
        url = link.encode(r)
        if link.decode(url) != r:
            errs.append("link does not round-trip")
        if len(url) > link.MAX_LINK:
            errs.append(f"link is {len(url)} bytes, budget {link.MAX_LINK}")
        status = "FAIL" if errs else "ok"
        print(f"{status:4} {path.name}: {r.get('kind')}, link {len(url)} bytes")
        for e in errs:
            print("     - " + e)
        failed |= bool(errs)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
