#!/usr/bin/env python3
"""Exercise real force-stop/relaunch and a signed in-place release APK upgrade.

The old app's welcome preference is written through its actual UI, not run-as or
an injected release backdoor. Android must preserve it across restart + upgrade.
This is a continuity smoke, not a full library/download migration test.
"""
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.vidora.app"
ACTIVITY = PACKAGE + "/.MainActivity"
REPORT = pathlib.Path("app/build/reports/upgrade-gate.txt")
REPORT.parent.mkdir(parents=True, exist_ok=True)


def command(*args):
    result = subprocess.run(args, check=False, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=45)
    with REPORT.open("a") as out:
        out.write("$ " + " ".join(args) + "\n" + result.stdout + "\n")
    if result.returncode:
        raise RuntimeError("Command failed: " + " ".join(args) + ": " + result.stdout[-1500:])
    return result.stdout


def adb(*args):
    return command("adb", *args)


def snapshot():
    adb("shell", "rm", "-f", "/sdcard/vidora-upgrade.xml")
    adb("shell", "uiautomator", "dump", "--compressed", "/sdcard/vidora-upgrade.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/vidora-upgrade.xml"))


def wait_for(predicate, description):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        try:
            root = snapshot()
        except (RuntimeError, ET.ParseError, subprocess.TimeoutExpired):
            # UIAutomator can report no idle root during launch/window transitions.
            time.sleep(1)
            continue
        later = find(root, "text", "Later")
        if later is not None:
            tap(later)
            continue
        result = predicate(root)
        if result is not None:
            return result
        time.sleep(1)
    raise AssertionError("Timed out: " + description)


def find(root, attribute, value):
    return next((node for node in root.iter("node") if node.get(attribute) == value), None)


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def launch():
    adb("shell", "am", "start", "-W", "-n", ACTIVITY)


def assert_existing_guest():
    def ready(root):
        if find(root, "text", "Welcome to Vidora") is not None:
            raise AssertionError("Previously completed guest welcome was lost")
        return find(root, "content-desc", "Search")
    wait_for(ready, "existing guest Home after process restart/upgrade")


try:
    old, new = sys.argv[1:]
    # Fresh API34 emulator; never run against a person's device/library.
    adb("shell", "input", "keyevent", "224")
    adb("shell", "input", "keyevent", "82")
    adb("install", "--no-streaming", old)
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    launch()
    button = wait_for(lambda root: find(root, "text", "Continue without signing in"), "old release guest welcome")
    tap(button)
    wait_for(lambda root: find(root, "content-desc", "Search"), "old release Home")
    # A synchronous UI observation ensures the skip event has run before force-stop.
    time.sleep(1)
    uid_before = re.search(r"(?:userId|appId)=(\d+)", adb("shell", "dumpsys", "package", PACKAGE)).group(1)
    adb("shell", "am", "force-stop", PACKAGE)
    if adb("shell", "pidof " + PACKAGE + " || true").strip():
        raise AssertionError("Old release process did not stop")
    launch()
    assert_existing_guest()
    adb("shell", "am", "force-stop", PACKAGE)
    # No uninstall/clear/downgrade: Android verifies signer and version continuity.
    adb("install", "--no-streaming", "-r", new)
    uid_after = re.search(r"(?:userId|appId)=(\d+)", adb("shell", "dumpsys", "package", PACKAGE)).group(1)
    assert uid_before == uid_after, "Package UID changed across upgrade"
    launch()
    assert_existing_guest()
    adb("shell", "am", "force-stop", PACKAGE)
    launch()
    assert_existing_guest()
    REPORT.write_text(REPORT.read_text() + "PASS: signed upgrade + real process restart + guest preference retention\n")
except Exception as error:
    detail = str(error) + "\n" + REPORT.read_text(errors="replace")[-6000:]
    message = detail.replace("%", "%25").replace("\n", "%0A").replace("\r", "")
    print("::error title=Signed upgrade gate::" + message)
    raise
