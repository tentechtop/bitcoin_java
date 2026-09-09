#!/usr/bin/env python3
"""Verify resolved JARs against Maven Central's HTTPS checksums, including existing local cache entries."""
import concurrent.futures
import datetime
import hashlib
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request


def verify(task):
    repository, coordinate = task
    group, artifact, kind = coordinate[:3]
    version, scope = coordinate[-2:]
    if kind != "jar":
        raise ValueError("Unsupported runtime artifact type: " + kind)
    classifier = "-" + coordinate[3] if len(coordinate) == 6 else ""
    relative = group.replace(".", "/") + "/" + artifact + "/" + version + "/" + artifact + "-" + version + classifier + ".jar"
    local = pathlib.Path(repository) / relative
    data = local.read_bytes()
    for algorithm in ("sha256", "sha1"):
        try:
            with urllib.request.urlopen("https://repo.maven.apache.org/maven2/" + relative + "." + algorithm, timeout=30) as response:
                expected = response.read(4096).decode("ascii").strip().split()[0].lower()
            if not re.fullmatch("[0-9a-f]{" + str(hashlib.new(algorithm).digest_size * 2) + "}", expected):
                raise ValueError("Invalid upstream checksum: " + relative)
            if hashlib.new(algorithm, data).hexdigest() != expected:
                raise ValueError("CHECKSUM MISMATCH: " + relative)
            return {"artifact": relative, "verifiedWith": algorithm, "sha256": hashlib.sha256(data).hexdigest()}
        except urllib.error.HTTPError as failure:
            if failure.code != 404:
                raise
    raise ValueError("No trusted upstream checksum: " + relative)


def main():
    if len(sys.argv) != 3:
        raise ValueError("Usage: verify_artifacts.py DEPENDENCY_LIST MAVEN_LOCAL_REPOSITORY")
    tasks = []
    for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
        clean = re.sub(r"\x1b\[[0-9;]*m", "", line).strip().split(" ")[0]
        parts = clean.split(":")
        if len(parts) in (5, 6) and parts[-1] in ("compile", "runtime"):
            tasks.append((sys.argv[2], parts))
    if not tasks:
        raise ValueError("Refusing empty integrity verification")
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        artifacts = list(executor.map(verify, tasks))
    report = {"verifiedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "complete": True, "artifacts": artifacts}
    pathlib.Path("target/artifact-integrity.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print("Verified {} runtime JARs against Maven Central HTTPS checksums".format(len(artifacts)))


if __name__ == "__main__":
    main()
