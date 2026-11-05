#!/usr/bin/env python3
"""Checks the libraries that ship in the app against OSV (https://osv.dev), the public database of known vulnerabilities.

Usage: python3 tools/check_vulnerabilities.py [--self-test]   (run from the repository root; needs network and Java for Gradle)
       --self-test only proves the check can flag a known-vulnerable library (needs network, not Gradle).

It asks Gradle for the libraries on each shipped classpath, asks OSV about every group:name:version, and exits with 1 if any
vulnerability is found that is not in tools/vulnerability-ignores.txt. Exit 3 means OSV could not be reached (the check did not run).
It sends only public library names and versions, never anything of ours. This is a build tool: the app itself makes no such call.
(Owner decision, 4 Nov 2026, clarification C9.)
"""
import json, re, subprocess, sys, time, urllib.request

OSV_BATCH = "https://api.osv.dev/v1/querybatch"
CONFIGURATIONS = [
    (":app:app", "releaseRuntimeClasspath"),
    (":app:core-model", "runtimeClasspath"),
    (":app:crypto", "runtimeClasspath"),
    (":app:sync", "runtimeClasspath"),
]
LINE = re.compile(r"^[|+\\\- ]*(?P<text>\S.*)$")
COORD = re.compile(r"^(?P<group>[\w.\-]+):(?P<name>[\w.\-]+)(?::(?P<declared>[^\s]+))?(?: -> (?P<resolved>[^\s]+))?(?P<rest>.*)$")


def parse_dependencies(text):
    """The (group, name, version) of every library on the classpath in `gradle dependencies` output.
    Project modules and constraint lines (marked "(c)", which are not on the classpath) are left out; a version that Gradle
    replaced ("a -> b") counts as b, the one that really ships."""
    found = set()
    for raw in text.splitlines():
        m = LINE.match(raw)
        if not m:
            continue
        text_ = m.group("text")
        if text_.startswith("project ") or ":" not in text_:
            continue
        c = COORD.match(text_)
        if not c:
            continue
        if "(c)" in c.group("rest"):
            continue
        version = c.group("resolved") or c.group("declared")
        if not version or version.startswith("{"):
            continue
        found.add((c.group("group"), c.group("name"), version))
    return found


def load_ignores(text):
    """Ids to leave out, one per line: `GHSA-xxxx  # why, and until when`. Blank lines and comments are skipped."""
    ids = set()
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            ids.add(line.split()[0])
    return ids


def findings(deps, results, ignored):
    """[(library, [ids])] for libraries with vulnerabilities that are not ignored. [results] lines up with [deps]."""
    out = []
    for dep, result in zip(deps, results):
        ids = sorted({v["id"] for v in result.get("vulns", [])} - ignored)
        if ids:
            out.append((dep, ids))
    return out


def gradle_dependencies():
    text = ""
    for project, configuration in CONFIGURATIONS:
        text += subprocess.run(["./gradlew", f"{project}:dependencies", "--configuration", configuration, "-q"], capture_output=True, text=True, check=True).stdout + "\n"
    return sorted(parse_dependencies(text))


def query_osv(deps, tries=3):
    body = json.dumps({"queries": [{"package": {"ecosystem": "Maven", "name": f"{g}:{n}"}, "version": v} for g, n, v in deps]}).encode()
    last = None
    for attempt in range(tries):
        try:
            req = urllib.request.Request(OSV_BATCH, data=body, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=60) as response:
                return json.load(response)["results"]
        except Exception as e:  # network trouble: try again, then say plainly that the check did not run
            last = e
            time.sleep(2 * (attempt + 1))
    print(f"Could not reach OSV: {last}. The vulnerability check did NOT run.", file=sys.stderr)
    sys.exit(3)


def self_test():
    """Proves the check can find something: gson 2.8.0 has a published vulnerability (fixed in 2.8.9). If OSV stops flagging it, or
    its answer changes shape, this fails loudly instead of the real check quietly passing."""
    deps = [("com.google.code.gson", "gson", "2.8.0")]
    bad = findings(deps, query_osv(deps), set())
    if not bad:
        print("Self-test FAILED: OSV did not flag gson 2.8.0, so a clean result could not be trusted.", file=sys.stderr)
        sys.exit(2)
    print("Self-test passed: gson 2.8.0 is flagged (%s)." % ", ".join(bad[0][1]))


def main():
    if "--self-test" in sys.argv:
        self_test()
        return
    deps = gradle_dependencies()
    print(f"{len(deps)} libraries on the shipped classpaths.")
    try:
        ignored = load_ignores(open("tools/vulnerability-ignores.txt").read())
    except FileNotFoundError:
        ignored = set()
    bad = findings(deps, query_osv(deps), ignored)
    for (g, n, v), ids in bad:
        print(f"{g}:{n}:{v}  " + "  ".join(f"{i} (https://osv.dev/{i})" for i in ids))
    if bad:
        print(f"{len(bad)} libraries have known vulnerabilities. Update them, or list an id in tools/vulnerability-ignores.txt with a reason.")
        sys.exit(1)
    print("No known vulnerabilities (ignored ids: %d)." % len(ignored))


if __name__ == "__main__":
    main()
