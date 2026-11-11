#!/usr/bin/env python3
"""Runs the SHIPPED release build (code shrunk and renamed, nothing kept for tests) on a connected emulator or phone and drives it through a real
first run (M10, T10.2). Python 3 only; needs adb and the Android build tools.

  python3 tools/smoke_release.py [--apk app/app/build/outputs/apk/release/app-release-unsigned.apk] [--build]

It signs a copy of the APK with the debug key (never the release key), installs it, clears its data, and then, through the screens like a person would:
a tapped invite link before setup (kept), first-run setup with the recovery phrase, the invite link opening its join form afterwards, a new group, an
expense, the Members and Invite screens (a new invitation is made and its QR drawn), the Sync tab, and a wait during which Iroh and the relay start.
After each step the app must still be running; at the end the log must hold no crash and none of the errors that shrinking causes (missing classes or
methods, native library not found), and WorkManager's job must be scheduled. Exit code 0 only if everything passed.
"""
import argparse, base64, glob, os, re, subprocess, sys, time, xml.etree.ElementTree as ET, zlib, struct

PKG = "in.ownspace.owntally"
ACTIVITY = PKG + "/owntally.app.MainActivity"


def find_adb():
    import shutil
    found = shutil.which("adb")
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.expanduser("~/Android/Sdk")
    candidate = os.path.join(sdk, "platform-tools", "adb")
    if os.path.exists(candidate):
        return candidate
    raise SystemExit("adb not found: put the Android platform-tools on the PATH or set ANDROID_HOME")


ADB = find_adb()


def adb(*args, check=True, text=True):
    r = subprocess.run([ADB, *args], capture_output=True, text=text)
    if check and r.returncode != 0:
        raise SystemExit("adb %s failed: %s" % (" ".join(args), r.stderr.strip() or r.stdout.strip()))
    return r.stdout


def sign(apk):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.expanduser("~/Android/Sdk")
    tools = sorted(glob.glob(os.path.join(sdk, "build-tools", "*")))
    if not tools:
        raise SystemExit("no Android build tools found under " + sdk)
    signer = os.path.join(tools[-1], "apksigner")
    keystore = os.path.expanduser("~/.android/debug.keystore")
    if not os.path.exists(keystore):
        raise SystemExit("no debug keystore at " + keystore + " (build a debug app once, or make one with keytool)")
    out = os.path.join(os.environ.get("TMPDIR", "/tmp"), "owntally-release-smoke.apk")
    subprocess.run([signer, "sign", "--ks", keystore, "--ks-pass", "pass:android", "--out", out, apk], check=True)
    return out


def valid_ticket():
    """A ticket the app reads (docs/WIRE_FORMAT.md; Ticket.kt): made up, for a group on a phone that is not there, expiring in 8 hours."""
    body = bytes([1]) + os.urandom(16) + os.urandom(32) + os.urandom(16) + struct.pack(">I", 1) + os.urandom(32) + struct.pack(">q", int(time.time()) + 8 * 3600) + bytes([0])
    return base64.urlsafe_b64encode(body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)).decode().rstrip("=")


class Screen:
    def __init__(self):
        self.nodes = []

    def refresh(self):
        adb("shell", "uiautomator", "dump", "/sdcard/owntally-ui.xml", check=False)
        xml = adb("exec-out", "cat", "/sdcard/owntally-ui.xml")
        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            self.nodes = []
            return self
        self.nodes = [n.attrib for n in root.iter("node")]
        return self

    def find(self, text=None, desc=None, desc_prefix=None, contains=None):
        for n in self.nodes:
            t, d = n.get("text", ""), n.get("content-desc", "")
            if text is not None and t == text: return n
            if desc is not None and d == desc: return n
            if desc_prefix is not None and d.startswith(desc_prefix): return n
            if contains is not None and (contains in t or contains in d): return n
        return None


def field_below(screen, label):
    """The first text field under the label with this text (a label is not the field: tapping it does not focus anything)."""
    lab = screen.find(text=label)
    if not lab:
        return None
    ly = int(re.findall(r"\d+", lab["bounds"])[3])
    fields = [n for n in screen.nodes if n.get("class", "").endswith("EditText") and int(re.findall(r"\d+", n["bounds"])[1]) >= ly - 5]
    return min(fields, key=lambda n: int(re.findall(r"\d+", n["bounds"])[1])) if fields else None


def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node["bounds"]))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(node):
    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.7)


def hide_keyboard():
    """Closes the on-screen keyboard if it is showing (a Back press with no keyboard would leave the screen)."""
    if "mInputShown=true" in adb("shell", "dumpsys", "input_method", check=False):
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.7)


def type_into(node, text):
    tap(node)
    adb("shell", "input", "text", text.replace(" ", "%s"))
    time.sleep(0.5)
    hide_keyboard()


def alive():
    return PKG in adb("shell", "pidof", PKG, check=False) or bool(adb("shell", "pidof", PKG, check=False).strip())


def wait_for(screen, what, seconds=40, **find):
    start = time.time()
    end = start + seconds
    while time.time() < end:
        # The process takes a moment to appear after a launch; once it has been seen, or after a grace period, its absence is a crash.
        if alive():
            wait_for.seen = True
        elif getattr(wait_for, "seen", False) or time.time() - start > 20:
            fail("the app is no longer running while waiting for " + what)
        n = screen.refresh().find(**find)
        if n:
            return n
        time.sleep(1.5)
    fail("never saw " + what + " (waited %ds); on screen: %s" % (seconds, [x.get("text") or x.get("content-desc") for x in screen.nodes if (x.get("text") or x.get("content-desc"))][:25]))


failures = []


SHOTS = None


def shot(name):
    """Saves a screenshot of the phone when the run was started with --screenshots DIR."""
    if SHOTS:
        os.makedirs(SHOTS, exist_ok=True)
        data = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout
        with open(os.path.join(SHOTS, name + ".png"), "wb") as f:
            f.write(data)


def fail(msg):
    print("FAIL:", msg)
    dump_log()
    sys.exit(1)


def ok(msg):
    print("ok:  ", msg)


BAD = re.compile(r"FATAL EXCEPTION|NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|ClassNotFoundException|UnsatisfiedLinkError|IncompatibleClassChangeError|AbstractMethodError|R8|ProGuard|keep rule", re.I)


def log_problems():
    log = adb("logcat", "-d", "-v", "brief", check=False)
    return [l for l in log.splitlines() if BAD.search(l) and ("owntally" in l.lower() or "AndroidRuntime" in l or "computer.iroh" in l or "jna" in l.lower())]


def dump_log():
    for l in log_problems()[:15]:
        print("  log:", l[:220])


RESTORE_PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"  # a valid BIP39 test phrase, not anyone's


def restore_flow(a):
    """The shipped release build restored from a recovery phrase (M11): welcome, "I already have a recovery phrase", eleven words (told how many),
    twelve words, then the Groups screen; a group is made and opened; the log holds no crash and none of the errors shrinking causes."""
    signed = sign(a.apk)
    adb("install", "-r", signed)
    adb("shell", "pm", "clear", PKG)
    adb("logcat", "-c")
    scr = Screen()
    adb("shell", "am", "start", "-n", ACTIVITY)
    wait_for(scr, "the welcome screen", contains="Keep your data")
    type_into(wait_for(scr, "the name field", text="For example, Asha"), "Rohan")
    type_into(scr.refresh().find(text="+91 98765 43210"), "9876543210")
    tap(scr.refresh().find(text="I already have a recovery phrase") or fail("no restore link on the welcome screen"))
    wait_for(scr, "the restore screen", text="Restore with your recovery phrase")
    shot("restore")
    field = next((n for n in scr.refresh().nodes if n.get("class", "").endswith("EditText")), None) or fail("no field for the words")
    type_into(field, " ".join(RESTORE_PHRASE.split()[:11]))
    tap(wait_for(scr, "Restore", text="Restore"))
    wait_for(scr, "the count message", contains="You entered 11")
    ok("eleven words: told how many, still on the restore screen")
    tap(next(n for n in scr.refresh().nodes if n.get("class", "").endswith("EditText")))
    adb("shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")  # select everything in the field,
    adb("shell", "input", "keyevent", "KEYCODE_DEL")  # delete it,
    adb("shell", "input", "text", RESTORE_PHRASE.replace(" ", "%s"))  # and type all twelve words
    hide_keyboard()
    tap(wait_for(scr, "Restore", text="Restore"))
    wait_for(scr, "the Groups screen", 40, text="New group")
    shot("restored")
    ok("twelve words: restored, on the Groups screen")
    tap(scr.find(text="New group"))
    wait_for(scr, "the group name label", text="Group name")
    type_into(field_below(scr.refresh(), "Group name"), "Goa Trip")
    tap(wait_for(scr, "Create group", text="Create group"))
    wait_for(scr, "the group card", text="Goa Trip")
    tap(scr.find(text="Goa Trip"))
    wait_for(scr, "the group screen", text="You are the owner")
    ok("a group was made and opened on the restored phone")
    problems = log_problems()
    if problems:
        for l in problems[:15]: print("  log:", l[:220])
        fail("the log holds crashes or the errors that shrinking causes")
    ok("no crash and no shrinking error in the log")
    print("PASS: the shipped release build restores from a recovery phrase")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--restore", action="store_true", help="drive the restore-from-recovery-phrase path instead of a new first run")
    ap.add_argument("--apk", default="app/app/build/outputs/apk/release/app-release-unsigned.apk")
    ap.add_argument("--build", action="store_true", help="run ./gradlew :app:app:assembleRelease first")
    ap.add_argument("--screenshots", help="save a screenshot of each main screen into this folder (for looking at a theme)")
    a = ap.parse_args()
    global SHOTS
    SHOTS = a.screenshots
    if a.build:
        subprocess.run(["./gradlew", ":app:app:assembleRelease"], check=True)
    if not os.path.exists(a.apk):
        raise SystemExit("no APK at " + a.apk + " (run with --build)")
    if a.restore:
        return restore_flow(a)
    signed = sign(a.apk)
    adb("install", "-r", signed)
    adb("shell", "pm", "clear", PKG)
    adb("logcat", "-c")
    ok("installed the shrunk release build (signed with the debug key) and cleared its data")
    scr = Screen()

    adb("shell", "am", "start", "-n", ACTIVITY)
    wait_for(scr, "the welcome screen", contains="Keep your data")
    shot("welcome")
    ok("cold start: the welcome screen shows")

    # A link tapped before setup waits (decision A1).
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "https://joinowntally.ownspace.in/j#" + valid_ticket(), "-n", ACTIVITY)
    wait_for(scr, "the welcome screen after the link", contains="Keep your data")
    ok("an invite link tapped before setup leaves the welcome screen (it waits)")

    # First run.
    type_into(wait_for(scr, "the name field", text="For example, Asha"), "Asha")
    type_into(scr.refresh().find(text="+91 98765 43210"), "9876543210")
    tap(wait_for(scr, "Continue", text="Continue"))
    wait_for(scr, "the recovery phrase screen", text="Save your recovery phrase")
    scr.refresh()
    words = []
    for n in range(1, 13):
        d = scr.find(desc_prefix="Word %d: " % n)
        if not d:
            fail("word %d of the recovery phrase is not on the screen" % n)
        words.append(d["content-desc"].split(": ", 1)[1])
    shot("phrase")
    ok("first run: the recovery phrase shows 12 words")
    tap(scr.find(text="I've written it down"))
    wait_for(scr, "the check screen", text="Check you wrote it down")
    for node in [x for x in scr.refresh().nodes if x.get("text", "").startswith("Type word ")]:
        n = int(node["text"].split()[-1])
        type_into(node, words[n - 1])
    tap(scr.refresh().find(text="Finish"))
    wait_for(scr, "the invite form (the link that waited)", 60, text="You were invited")
    shot("join-form")
    ok("setup done; the invite link that waited opened its join form")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    wait_for(scr, "the Groups screen", text="New group")

    # A group and an expense.
    tap(scr.find(text="New group"))
    wait_for(scr, "the group name label", text="Group name")
    type_into(field_below(scr.refresh(), "Group name"), "Goa Trip")
    tap(wait_for(scr, "Create group", text="Create group"))
    wait_for(scr, "the group card", text="Goa Trip")
    tap(scr.find(text="Goa Trip"))
    wait_for(scr, "the group screen", text="You are the owner")
    shot("group")
    ok("a group was made and opened")
    tap(scr.find(desc="Add expense"))
    amount = wait_for(scr, "the amount field", text="Save expense")
    scr.refresh()
    type_into(scr.find(desc="Amount") or scr.find(contains="Amount"), "2400")
    type_into(scr.refresh().find(desc="What was it for?") or scr.find(contains="What was it for"), "Lunch")
    tap(scr.refresh().find(text="Save expense"))
    wait_for(scr, "the saved expense", 30, contains="Lunch")
    shot("expense-saved")
    ok("an expense was saved (Room and Keystore work in the shrunk build)")

    # Members, Invite.
    tap(scr.find(desc="Members"))
    wait_for(scr, "the Members screen", contains="Goa Trip")
    tap(wait_for(scr, "the Invite button", desc="Invite"))
    wait_for(scr, "the invite screen with a link", 30, text="Share invite link")
    shot("invite")
    ok("a new invitation was made and shown (Tink, Room, the QR code)")
    adb("shell", "input", "keyevent", "KEYCODE_BACK"); adb("shell", "input", "keyevent", "KEYCODE_BACK"); adb("shell", "input", "keyevent", "KEYCODE_BACK")

    # Sync (Iroh and the relay start while the app is in front).
    wait_for(scr, "the Groups tab", desc="Sync")
    tap(scr.find(desc="Sync"))
    time.sleep(25)
    if not alive():
        fail("the app stopped while Iroh and the relay were starting")
    shot("sync")
    ok("the app kept running for 25 s with Iroh and the relay starting")

    jobs = adb("shell", "dumpsys", "jobscheduler", check=False)
    if PKG not in jobs or "SystemJobService" not in jobs:
        fail("WorkManager's background job is not scheduled")
    ok("WorkManager's background job is scheduled")

    problems = log_problems()
    if problems:
        for l in problems[:15]: print("  log:", l[:220])
        fail("the log holds crashes or the errors that shrinking causes")
    ok("no crash and no shrinking error in the log")
    print("PASS: the shipped release build works through a first run")


if __name__ == "__main__":
    main()
