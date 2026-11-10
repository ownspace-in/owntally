#!/usr/bin/env python3
"""How much memory the shipped release build uses with 1, 5 and 15 groups, while syncing (M10, T10.7). Python 3 only; needs adb.

  python3 tools/memory_check.py [--apk ...] [--counts 1,5,15] [--wait 40]

Starts from a clean install: it runs the first-run steps of tools/smoke_release.py (setup, one group, an expense), then adds groups until each count
is reached. At each count the app is put on the Sync tab, left for --wait seconds (Iroh and the relay start, one Iroh endpoint for each group while the
app is open), and `dumpsys meminfo` of the app and of the whole phone is read. It prints a table and exits 0; it does not judge the numbers (the owner
decides what is too much, docs/tasks/M10-hardening-release.md T10.7). Run it on an emulator or phone that is as small as the phones it is for.
"""
import argparse, os, re, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import smoke_release as sr


def kb(pattern, text):
    m = re.search(pattern, text)
    return int(m.group(1).replace(",", "")) if m else None


def measure():
    app = sr.adb("shell", "dumpsys", "meminfo", sr.PKG, check=False)
    phone = sr.adb("shell", "dumpsys", "meminfo", check=False)
    threads = sr.adb("shell", "ls", "/proc/%s/task" % sr.adb("shell", "pidof", sr.PKG, check=False).strip().split()[0], check=False).split()
    return {
        "pss": kb(r"TOTAL PSS:\s+([\d,]+)", app) or kb(r"TOTAL\s+([\d,]+)", app),
        "rss": kb(r"TOTAL RSS:\s+([\d,]+)", app),
        "java": kb(r"Java Heap:\s+([\d,]+)", app),
        "native": kb(r"Native Heap:\s+([\d,]+)", app),
        "code": kb(r"Code:\s+([\d,]+)", app),
        "threads": len(threads),
        "free": kb(r"Free RAM:\s+([\d,]+)K", phone),
        "kills": sr.adb("logcat", "-d", "-b", "events", check=False).count("lmkd") if False else sr.adb("logcat", "-d", check=False).count("lowmemorykiller: Kill 'in.ownspace.owntally"),
    }


def add_group(scr, name):
    groups = scr.refresh().find(desc="Groups") or scr.find(text="Groups")
    if groups:
        sr.tap(groups)
    sr.tap(sr.wait_for(scr, "New group", text="New group"))
    sr.wait_for(scr, "the group name label", text="Group name")
    sr.type_into(sr.field_below(scr.refresh(), "Group name"), name)
    sr.tap(sr.wait_for(scr, "Create group", text="Create group"))
    sr.wait_for(scr, "the Groups screen again", text="New group")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default="app/app/build/outputs/apk/release/app-release-unsigned.apk")
    ap.add_argument("--counts", default="1,5,15")
    ap.add_argument("--wait", type=int, default=40)
    a = ap.parse_args()
    counts = [int(c) for c in a.counts.split(",")]
    sr.adb("shell", "dumpsys", "meminfo", check=False)
    signed = sr.sign(a.apk)
    sr.adb("install", "-r", signed)
    sr.adb("shell", "pm", "clear", sr.PKG)
    sr.adb("logcat", "-c")
    scr = sr.Screen()
    sr.adb("shell", "am", "start", "-n", sr.ACTIVITY)
    sr.type_into(sr.wait_for(scr, "the name field", text="For example, Asha"), "Asha")
    sr.type_into(scr.refresh().find(text="+91 98765 43210"), "9876543210")
    sr.tap(sr.wait_for(scr, "Continue", text="Continue"))
    sr.wait_for(scr, "the recovery phrase screen", text="Save your recovery phrase")
    scr.refresh()
    words = [scr.find(desc_prefix="Word %d: " % n)["content-desc"].split(": ", 1)[1] for n in range(1, 13)]
    sr.tap(scr.find(text="I've written it down"))
    sr.wait_for(scr, "the check screen", text="Check you wrote it down")
    for node in [x for x in scr.refresh().nodes if x.get("text", "").startswith("Type word ")]:
        sr.type_into(node, words[int(node["text"].split()[-1]) - 1])
    sr.tap(scr.refresh().find(text="Finish"))
    sr.wait_for(scr, "the Groups screen", text="New group")

    rows, have = [], 0
    for target in counts:
        while have < target:
            have += 1
            add_group(scr, "Trip %d" % have)
        sr.tap(sr.wait_for(scr, "the Sync tab", desc="Sync"))
        time.sleep(a.wait)
        if not sr.alive():
            sr.fail("the app stopped with %d groups" % have)
        m = measure()
        rows.append((have, m))
        print("groups=%d" % have, m, flush=True)
    print()
    print("| groups | PSS MB | RSS MB | Java MB | Native MB | Code MB | threads | phone free MB | killed by Android |")
    print("|---|---|---|---|---|---|---|---|---|")
    mb = lambda v: "?" if v is None else "%.0f" % (v / 1024)
    for g, m in rows:
        print("| %d | %s | %s | %s | %s | %s | %d | %s | %d |" % (g, mb(m["pss"]), mb(m["rss"]), mb(m["java"]), mb(m["native"]), mb(m["code"]), m["threads"], mb(m["free"]), m["kills"]))
    problems = sr.log_problems()
    if problems:
        for l in problems[:15]: print("  log:", l[:220])
        sr.fail("the log holds crashes or errors")


if __name__ == "__main__":
    main()
