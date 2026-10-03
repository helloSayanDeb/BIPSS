"""Registers newly written UI strings.

New screens are written as AppText.text(R.string.new_copy, "English", args...).
This script rewrites each R.string.new_copy to R.string.copy_<sha256(english)[:12]>
(the same key the original extraction used, so AppText.label finds it too) and adds
the English text to res/values/copy_new.xml. Strings that already exist in
copy.xml or copy_new.xml are reused. The 22 language folders have no entry for a
new key and fall back to this English text until it is translated; copy_new.xml
says so to lint with tools:ignore="MissingTranslation".

Run before building:  python tools/localization/register_new.py
"""
import hashlib, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java"
RES = ROOT / "app/src/main/res/values"
BS = chr(92)
CALL = re.compile("AppText" + BS + ".text" + BS + "(" + BS + "s*R" + BS + ".string" + BS + ".new_copy" + BS + "s*," + BS + "s*" + chr(34) + "((?:[^" + chr(34) + BS + BS + "]|" + BS + BS + ".)*)" + chr(34))
ENTRY = re.compile(r'<string name="(copy_[0-9a-f]{12})"')


def unescape(literal: str) -> str:
    out, i = [], 0
    while i < len(literal):
        c = literal[i]
        if c == "\\" and i + 1 < len(literal):
            n = literal[i + 1]
            out.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\", "$": "$", "'": "'"}.get(n, n))
            i += 2
        else:
            out.append(c); i += 1
    return "".join(out)


def key_for(text: str) -> str:
    return "copy_" + hashlib.sha256(text.encode("utf-8")).hexdigest()[:12]


def xml_escape(text: str) -> str:
    return (chr(34) + text.replace(BS, BS + BS).replace(chr(34), BS + chr(34)).replace(chr(39), BS + chr(39))
            .replace("&", "&amp;").replace("<", "&lt;").replace(chr(10), BS + "n") + chr(34))


def main() -> int:
    existing = set()
    for name in ("copy.xml", "copy_new.xml"):
        f = RES / name
        if f.exists():
            existing |= set(ENTRY.findall(f.read_text(encoding="utf-8")))
    added: dict[str, str] = {}
    changed = 0
    for path in SRC.rglob("*.kt"):
        text = path.read_text(encoding="utf-8")
        if "R.string.new_copy" not in text:
            continue

        def sub(m: re.Match) -> str:
            english = unescape(m.group(1))
            key = key_for(english)
            if key not in existing:
                added.setdefault(key, english)
            return m.group(0).replace("R.string.new_copy", "R.string." + key)

        new = CALL.sub(sub, text)
        if "R.string.new_copy" in new:
            print("Unconverted new_copy in", path, "(first argument must be a plain string literal)")
            return 1
        path.write_text(new, encoding="utf-8")
        changed += 1
    if added:
        f = RES / "copy_new.xml"
        body = f.read_text(encoding="utf-8") if f.exists() else (
            "<?xml version='1.0' encoding='utf-8'?>\n"
            "<resources xmlns:tools=\"http://schemas.android.com/tools\" tools:ignore=\"MissingTranslation\">\n</resources>\n")
        lines = "".join(f'  <string name="{k}" formatted="false">{xml_escape(v)}</string>\n'
                        for k, v in sorted(added.items()))
        body = body.replace("</resources>", lines + "</resources>")
        f.write_text(body, encoding="utf-8")
    print(f"{changed} file(s) rewritten, {len(added)} string(s) added")
    return 0


sys.exit(main())
