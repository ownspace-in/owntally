#!/usr/bin/env python3
"""Makes and checks `/.well-known/assetlinks.json` for the invite site (M9, T9.4). Python 3 only; no outside libraries.

  python3 assetlinks.py make --fingerprint 14:6D:... [--fingerprint ...] [--package in.ownspace.owntally] [--out .well-known/assetlinks.json]
  python3 assetlinks.py check --file .well-known/assetlinks.json [--fingerprint ...]
  python3 assetlinks.py check --url https://joinowntally.ownspace.in/.well-known/assetlinks.json [--fingerprint ...]   (needs the network)

The file tells Android that this app may open links of this site (Android App Links). It needs the SHA-256 fingerprint of the certificate the app is
signed with: see site/README.md for where to find it. Only the checks of the file's shape and the website's answer are made here; Android does its
own verification when the app is installed.
"""
import argparse, json, re, sys

DEFAULT_PACKAGE = "in.ownspace.owntally"
PLACEHOLDER = "REPLACE:WITH:THE:SHA-256:FINGERPRINT:OF:THE:SIGNING:CERTIFICATE"
RELATION = "delegate_permission/common.handle_all_urls"
PACKAGE_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$")


class Problem(Exception):
    pass


def normalise(text):
    """A SHA-256 fingerprint as `AA:BB:...` (64 hex digits in 32 pairs), from any of the usual ways of writing it."""
    t = text.strip()
    if t == PLACEHOLDER:
        raise Problem("that is the placeholder of the template, not a certificate fingerprint")
    t = re.sub(r"^(sha-?256)\s*[:=]?\s*", "", t, flags=re.IGNORECASE)
    digits = re.sub(r"[\s:]", "", t).upper()
    if not re.fullmatch(r"[0-9A-F]{64}", digits):
        raise Problem("not a SHA-256 fingerprint (it has 64 hex digits, written as 32 pairs): " + text.strip()[:80])
    if set(digits) == {"0"}:
        raise Problem("an all-zero fingerprint is not a certificate")
    return ":".join(digits[i:i + 2] for i in range(0, 64, 2))


def make(package, fingerprints):
    if not PACKAGE_NAME.match(package or ""):
        raise Problem("not an application id: " + repr(package))
    if not fingerprints:
        raise Problem("at least one certificate fingerprint is needed")
    unique = []
    for f in fingerprints:
        n = normalise(f)
        if n not in unique:
            unique.append(n)
    data = [{"relation": [RELATION], "target": {"namespace": "android_app", "package_name": package, "sha256_cert_fingerprints": unique}}]
    return json.dumps(data, indent=2) + "\n"


def check_text(text, package, fingerprints):
    """What is wrong with the file's content (an empty list if nothing)."""
    problems = []
    try:
        data = json.loads(text)
    except ValueError as e:
        return ["not JSON: " + str(e)]
    if not isinstance(data, list) or not data:
        return ["the file must be a list with at least one statement"]
    found = []
    for st in data:
        if not isinstance(st, dict) or RELATION not in (st.get("relation") or []):
            continue
        target = st.get("target") or {}
        if target.get("namespace") != "android_app":
            continue
        if target.get("package_name") == package:
            found += target.get("sha256_cert_fingerprints") or []
    if not found:
        problems.append("no statement gives " + package + " the relation " + RELATION)
    if PLACEHOLDER in found:
        problems.append("the fingerprint is still the placeholder of the template")
    for f in found:
        if f != PLACEHOLDER:
            try:
                if normalise(f) != f:
                    problems.append("fingerprint not written as upper case pairs: " + f)
            except Problem as e:
                problems.append(str(e))
    for want in fingerprints:
        if normalise(want) not in found:
            problems.append("the file does not hold the fingerprint " + normalise(want))
    return problems


def check_http(status, content_type, redirected):
    problems = []
    if redirected:
        problems.append("the address redirects: Android does not follow a redirect for this file")
    if status != 200:
        problems.append("the answer is %s, not 200" % status)
    if not (content_type or "").lower().startswith("application/json"):
        problems.append("the content type is %r, not application/json" % content_type)
    return problems


def main(argv):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    m = sub.add_parser("make")
    m.add_argument("--fingerprint", action="append", default=[])
    m.add_argument("--package", default=DEFAULT_PACKAGE)
    m.add_argument("--out", default=".well-known/assetlinks.json")
    c = sub.add_parser("check")
    c.add_argument("--file")
    c.add_argument("--url")
    c.add_argument("--fingerprint", action="append", default=[])
    c.add_argument("--package", default=DEFAULT_PACKAGE)
    a = p.parse_args(argv)
    try:
        if a.cmd == "make":
            text = make(a.package, a.fingerprint)
            import os
            os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
            with open(a.out, "w") as f:
                f.write(text)
            print("wrote", a.out)
            return 0
        problems = []
        if bool(a.file) == bool(a.url):
            raise Problem("give --file or --url")
        if a.file:
            text = open(a.file).read()
        else:
            import urllib.request
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self, *args, **kwargs): return None
            opener = urllib.request.build_opener(NoRedirect)
            try:
                r = opener.open(a.url, timeout=15)
                status, ctype, text, redirected = r.status, r.headers.get("Content-Type"), r.read().decode("utf-8", "replace"), False
            except urllib.error.HTTPError as e:
                status, ctype, text, redirected = e.code, e.headers.get("Content-Type"), "", 300 <= e.code < 400
            problems += check_http(status, ctype, redirected)
        problems += check_text(text, a.package, a.fingerprint) if text else []
        for pr in problems:
            print("PROBLEM:", pr)
        print("ok" if not problems else "%d problem(s)" % len(problems))
        return 0 if not problems else 1
    except Problem as e:
        print("PROBLEM:", e)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
