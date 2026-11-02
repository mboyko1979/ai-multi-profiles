#!/usr/bin/env python3
"""Compare Marketplace Air's draft pick method with the shapes this plugin can call.

The nightly workflow downloads the newest stable Air build and fails when
AgentPromptLaunchDraftState.pick is no longer one of the descriptors below.
It does not edit the plugin or publish a Marketplace update.
"""

import io
import json
import re
import struct
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

PLUGIN_ID = 33314
UPDATES_URL = f"https://plugins.jetbrains.com/api/plugins/{PLUGIN_ID}/updates?size=80"
FILES_URL = "https://plugins.jetbrains.com/files/"
CLASS_SUFFIX = "com/intellij/air/frontend/prompt/ui/AgentPromptLaunchDraftState.class"
PROFILE = "Lcom/intellij/air/shared/prompt/AgentPromptLaunchProfile;"
SEED = "Lcom/intellij/air/shared/prompt/AirSessionPreferencesSeedKind;"
# Keep these aligned with NativeRecoveryContinuation.call: exact arity, or a
# trailing null that Air 521 removed from the Air 497 Long parameter.
SUPPORTED = {
    f"({PROFILE}Ljava/lang/String;{SEED}ZLjava/lang/Long;)V": "Air 262.8665.497 optional Long",
    f"({PROFILE}Ljava/lang/String;{SEED}Z)V": "Air 262.8665.521",
}
RESULT = Path("air-pick-result.json")
ISSUE = Path("air-pick-issue.md")
VERSION_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)")


def request(url):
    opener = urllib.request.Request(url, headers={"User-Agent": "ai-multi-profiles-air-pick-check"})
    with urllib.request.urlopen(opener, timeout=120) as response:
        return response.read()


def version_key(update):
    match = VERSION_RE.match(update.get("version") or "")
    return tuple(int(part) for part in match.groups()) if match else None


def stable(update):
    if update.get("hidden") or update.get("listed") is False:
        return False
    return update.get("channel") in (None, "", "default") and version_key(update) is not None


def preference(update):
    version = update["version"]
    linux = 0 if version.endswith("-linux-x86_64") else 1
    return (linux, -int(update["id"]))


def better(left, right):
    if version_key(left) != version_key(right):
        return version_key(left) > version_key(right)
    return preference(left) < preference(right)


def choose_lines(updates):
    """The current release line (until N.*) and the single newest stable build.

    Marketplace also publishes a build for every EAP number. Those older
    micro-lines are not what a release IDE installs, so they are not downloaded.
    """
    candidates = [update for update in updates if stable(update) and version_key(update)[0] >= 262]
    if not candidates:
        raise RuntimeError("Marketplace returned no stable Air updates")
    newest_key = max(version_key(update) for update in candidates)
    # 262.* is the release line for this plugin's since-build. A newer 263 EAP
    # uses a narrower until-build and is still selected as the newest build.
    release = [update for update in candidates if update.get("until") == "262.*"]
    selected = []
    if release:
        release_key = max(version_key(update) for update in release)
        selected.append(min((update for update in release if version_key(update) == release_key), key=preference))
    selected.append(min((update for update in candidates if version_key(update) == newest_key), key=preference))
    unique = []
    seen = set()
    for update in selected:
        if update["id"] not in seen:
            seen.add(update["id"])
            unique.append(update)
    return unique


def parse_constant_pool(data, index):
    count = struct.unpack_from(">H", data, index)[0]
    index += 2
    pool = [None]
    item = 1
    while item < count:
        tag = data[index]
        index += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, index)[0]
            index += 2
            pool.append(data[index:index + length].decode("utf-8", "replace"))
            index += length
        elif tag in (7, 8, 16, 19, 20):
            index += 2
            pool.append(None)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            index += 4
            pool.append(None)
        elif tag in (5, 6):
            index += 8
            pool.append(None)
            pool.append(None)
            item += 1
        elif tag == 15:
            index += 3
            pool.append(None)
        else:
            raise RuntimeError(f"unknown class-file tag {tag}")
        item += 1
    return pool, index


def skip_attributes(data, index):
    count = struct.unpack_from(">H", data, index)[0]
    index += 2
    for _ in range(count):
        index += 2
        length = struct.unpack_from(">I", data, index)[0]
        index += 4 + length
    return index


def pick_methods(data):
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise RuntimeError("Air draft class is not a class file")
    pool, index = parse_constant_pool(data, 8)
    index += 6
    interfaces = struct.unpack_from(">H", data, index)[0]
    index += 2 + 2 * interfaces
    fields = struct.unpack_from(">H", data, index)[0]
    index += 2
    for _ in range(fields):
        index += 6
        index = skip_attributes(data, index)
    methods = struct.unpack_from(">H", data, index)[0]
    index += 2
    found = []
    for _ in range(methods):
        index += 2
        name_index = struct.unpack_from(">H", data, index)[0]
        index += 2
        descriptor_index = struct.unpack_from(">H", data, index)[0]
        index += 2
        name = pool[name_index]
        descriptor = pool[descriptor_index]
        if not name.startswith("<") and "$" not in name:
            found.append({"name": name, "descriptor": descriptor, "supported": SUPPORTED.get(descriptor),
                          "pick": name.startswith("pick-")})
        index = skip_attributes(data, index)
    return found


def class_bytes_from_zip(raw):
    found = []

    def walk(blob):
        try:
            archive = zipfile.ZipFile(io.BytesIO(blob))
        except zipfile.BadZipFile:
            return
        for info in archive.infolist():
            if info.is_dir() or info.file_size > 80_000_000:
                continue
            name = info.filename.replace("\\", "/")
            payload = archive.read(info)
            if name.endswith(CLASS_SUFFIX):
                found.append(payload)
            elif name.endswith(".jar"):
                walk(payload)

    walk(raw)
    return found


def readable(descriptor):
    args = descriptor[1:descriptor.index(")")]
    parts = []
    index = 0
    while index < len(args):
        if args.startswith("L", index):
            end = args.index(";", index)
            parts.append(args[index + 1:end].rsplit("/", 1)[-1])
            index = end + 1
        elif args[index] == "Z":
            parts.append("boolean")
            index += 1
        else:
            parts.append(args[index])
            index += 1
    return "(" + ", ".join(parts) + ")"


def methods_in(raw):
    classes = class_bytes_from_zip(raw)
    if not classes:
        raise RuntimeError(f"Air archive does not contain {CLASS_SUFFIX}")
    methods = []
    seen = set()
    for data in classes:
        for method in pick_methods(data):
            identity = (method["name"], method["descriptor"])
            if identity not in seen:
                seen.add(identity)
                methods.append(method)
    return methods


def line_supported(methods):
    picks = [method for method in methods if method["pick"]]
    return bool(picks) and all(method["supported"] for method in picks)


def report(lines):
    supported = all(line["supported"] for line in lines)
    body = ["Air release line and the newest stable Marketplace build.", ""]
    for line in lines:
        state = "supported" if line["supported"] else "not supported"
        body.append(f"- `{line['version']}` until `{line['until']}` ({state}, update {line['updateId']})")
        shown = [method for method in line["methods"] if method["pick"] or not line["supported"]]
        if shown:
            body.extend(f"  - `{method['name']} {readable(method['descriptor'])}`" for method in shown)
        else:
            body.append("  - no methods")
    body.extend(["", "Shapes this plugin can call:", ""])
    body.extend(f"- `{readable(descriptor)}` ({label})" for descriptor, label in SUPPORTED.items())
    body.extend([
        "",
        "`NativeRecoveryContinuation.call` still selects an exact argument count and only drops a trailing null.",
        "A new shape needs an adapter change. The check does not modify the plugin or publish an update.",
    ])
    RESULT.write_text(json.dumps({"supported": supported, "lines": lines}, indent=2), encoding="utf-8")
    for line in lines:
        state = "PASS" if line["supported"] else "FAIL"
        print(f"{state}: Air {line['version']} until {line['until']}")
    if supported:
        ISSUE.unlink(missing_ok=True)
        return 0
    ISSUE.write_text("\n".join(body) + "\n", encoding="utf-8")
    print(ISSUE.read_text(encoding="utf-8"), file=sys.stderr)
    return 2


def inspect(raw, version, update_id, until="local"):
    methods = methods_in(raw)
    return report([{
        "version": version,
        "updateId": update_id,
        "until": until,
        "supported": line_supported(methods),
        "methods": methods,
    }])


def marketplace():
    updates = json.loads(request(UPDATES_URL).decode("utf-8"))
    lines = []
    for selected in choose_lines(updates):
        print(f"download Air {selected['version']}", flush=True)
        archive = request(FILES_URL + selected["file"].lstrip("/"))
        try:
            methods = methods_in(archive)
        except RuntimeError as error:
            raise RuntimeError(f"Air {selected['version']}: {error}") from error
        lines.append({
            "version": selected["version"],
            "updateId": selected["id"],
            "until": selected.get("until") or selected.get("since") or "unbounded",
            "supported": line_supported(methods),
            "methods": methods,
        })
    return report(lines)


def publish_issue():
    if not ISSUE.is_file():
        return 0
    version = ", ".join(line["version"] for line in json.loads(RESULT.read_text(encoding="utf-8"))["lines"]
                         if not line["supported"])
    listed = json.loads(subprocess.check_output(
        ["gh", "issue", "list", "--state", "open", "--limit", "20", "--json", "number,title",
         "--search", "Air pick signature changed in:title"],
        text=True))
    for issue in listed:
        if version in issue["title"]:
            print(f"open issue #{issue['number']} already records Air {version}")
            return 0
    if listed:
        subprocess.check_call(["gh", "issue", "comment", str(listed[0]["number"]), "--body-file", str(ISSUE)])
        print(f"commented on issue #{listed[0]['number']}")
        return 0
    subprocess.check_call(["gh", "issue", "create", "--title", f"Air pick signature changed ({version})",
                           "--body-file", str(ISSUE)])
    print("opened Air pick issue")
    return 0


def self_test():
    updates = [
        {"id": 1, "version": "262.8665.520-windows-x86_64", "channel": "", "until": "262.*", "file": "old"},
        {"id": 2, "version": "262.8665.521-linux-x86_64", "channel": "", "until": "262.*", "file": "linux"},
        {"id": 3, "version": "262.8665.521-windows-x86_64", "channel": "", "until": "262.*", "file": "windows"},
        {"id": 4, "version": "263.1.1-linux-x86_64", "channel": "eap", "until": "263.*", "file": "eap"},
        {"id": 6, "version": "263.1.2", "channel": "", "until": "263.*", "file": "next"},
        {"id": 5, "version": "262.8665.521-linux-arm64", "channel": "", "until": "262.*", "hidden": True, "file": "hidden"},
    ]
    selected = [update["file"] for update in choose_lines(updates)]
    if selected != ["linux", "next"]:
        raise SystemExit(f"compatibility lines were {selected}")
    four = f"({PROFILE}Ljava/lang/String;{SEED}Z)V"
    five = f"({PROFILE}Ljava/lang/String;{SEED}ZLjava/lang/Long;)V"
    if SUPPORTED[four] != "Air 262.8665.521" or SUPPORTED[five] != "Air 262.8665.497 optional Long":
        raise SystemExit("supported pick descriptors drifted")
    print("PASS: air pick check self-test")


def main(argv):
    if "--self-test" in argv:
        self_test()
        return 0
    if "--publish-issue" in argv:
        return publish_issue()
    if "--jar" in argv:
        path = Path(argv[argv.index("--jar") + 1])
        return inspect(path.read_bytes(), path.name, 0)
    return marketplace()


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
