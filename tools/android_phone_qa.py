"""Guarded, local ADB UI checks for the user's authorized PixelPlayer phone session."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ADB = r"C:\Users\Hoa Vo\AppData\Local\Android\Sdk\platform-tools\adb.exe"
DEVICE = "59240DLCH004G6"
PACKAGE = "com.theveloper.pixelplay.debug"
OUT = Path(__file__).resolve().parents[1] / "build" / "phone-qa"

def adb(*args):
    result = subprocess.run([ADB, "-s", DEVICE, *args], capture_output=True, timeout=45)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors="replace"))
    return result.stdout

def guard():
    windows = adb("shell", "dumpsys", "window").decode(errors="replace")
    focus = next((x for x in windows.splitlines() if "mCurrentFocus=" in x), "")
    if PACKAGE not in focus:
        raise RuntimeError("PixelPlayer is not foreground; no test input was sent.")

def ui():
    guard()
    result = adb("shell", "uiautomator", "dump", "/sdcard/pixelplay-qa.xml").decode(errors="replace")
    if "dumped to:" not in result:
        raise RuntimeError("UI snapshot failed; refusing to use stale coordinates: " + result)
    guard()
    raw = adb("shell", "cat", "/sdcard/pixelplay-qa.xml")
    root = ET.fromstring(raw)
    foreign = {n.get("package") for n in root.iter("node") if n.get("package") and n.get("package") != PACKAGE}
    if foreign:
        raise RuntimeError("Another app or overlay is visible; no test input or UI capture was saved.")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "latest-ui.xml").write_bytes(raw)
    return root

def describe(root):
    rows = [{k: n.get(k, "") for k in ("text", "content-desc", "bounds", "class")}
            for n in root.iter("node") if n.get("text") or n.get("content-desc")]
    print(json.dumps(rows, ensure_ascii=True))

def center(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
    return (str((x1+x2)//2), str((y1+y2)//2))

def find(root, label):
    nodes = [n for n in root.iter("node") if n.get("text") == label or n.get("content-desc") == label]
    if len(nodes) != 1:
        raise RuntimeError(f"Expected exactly one visible {label!r}; found {len(nodes)}")
    return nodes[0]

def drag_handle(root, label):
    node = find(root, label)
    parents = {child: parent for parent in root.iter() for child in parent}
    while node is not None:
        handles = [x for x in node.iter("node") if "Drag to reorder" in x.get("content-desc", "")]
        if len(handles) == 1:
            return handles[0]
        if len(handles) > 1:
            break
        node = parents.get(node)
    raise RuntimeError("Could not identify the selected row's unique drag handle")

def session():
    text = adb("shell", "dumpsys", "media_session").decode(errors="replace")
    m = re.search(r"package=com\.theveloper\.pixelplay\.debug[\s\S]*?queueTitle[^\n]*", text)
    print("\n".join(x for x in m.group(0).splitlines() if "state=PlaybackState" in x or "metadata:" in x) if m else "No PixelPlayer media session")

parser = argparse.ArgumentParser()
parser.add_argument("action", choices=["start", "inspect", "tap", "drag", "back", "screenshot", "session"])
parser.add_argument("labels", nargs="*")
args = parser.parse_args()
if args.action == "start":
    print(adb("shell", "am", "start", "-W", "-n", PACKAGE + "/com.theveloper.pixelplay.MainActivity").decode())
    describe(ui())
elif args.action == "inspect":
    describe(ui())
elif args.action == "back":
    guard(); adb("shell", "input", "keyevent", "4"); describe(ui())
elif args.action == "tap":
    root = ui(); point = center(find(root, args.labels[0])); guard()
    adb("shell", "input", "tap", *point)
    describe(ui())
elif args.action == "drag":
    root = ui(); start = center(drag_handle(root, args.labels[0])); end = center(drag_handle(root, args.labels[1])); guard()
    adb("shell", "input", "swipe", *start, *end, "800")
    describe(ui())
elif args.action == "screenshot":
    guard(); OUT.mkdir(parents=True, exist_ok=True)
    target = OUT / "latest-screen.png"
    target.write_bytes(adb("exec-out", "screencap", "-p"))
    print(target)
else:
    session()
