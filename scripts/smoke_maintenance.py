"""Exercise the packaged maintenance launchers against a stopped regression database.

Only new directories under target are created. The source is never deleted.
Run after Maven verify: python scripts/smoke_maintenance.py SOURCE --expected-height 7
"""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--expected-height", type=int, required=True)
    parser.add_argument("--jar", type=Path, default=Path("target/bitcoin_java-0.0.1-SNAPSHOT.jar"))
    parser.add_argument("--report", type=Path, default=Path("target/maintenance-launcher.json"))
    args = parser.parse_args()
    java = shutil.which("java")
    if not java:
        raise RuntimeError("Java is required")
    jar, source = args.jar.resolve(strict=True), args.source.resolve(strict=True)
    root = Path(tempfile.mkdtemp(prefix="maintenance-smoke-", dir="target")).resolve()
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    report = {"testedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
              "expectedHeight": args.expected_height, "commands": [], "nodes": []}

    def launcher(main_class, arguments, name):
        command = [java, "-Dloader.main=" + main_class, "-cp", str(jar),
                   "org.springframework.boot.loader.launch.PropertiesLauncher"] + arguments
        with (root / (name + ".log")).open("wb") as output:
            result = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT,
                                    timeout=90, creationflags=flags)
        report["commands"].append({"name": name, "exitCode": result.returncode})
        return result.returncode

    for name, origin, destination in [
        ("checkpoint", source, root / "backup"),
        ("restore", root / "backup", root / "restored"),
        ("reindex", source, root / "rebuilt"),
    ]:
        if launcher("com.bit.coin.ops.DatabaseTool",
                    [name, str(origin), str(destination), "RegressionNetParams"], name) != 0:
            raise RuntimeError(name + " failed; inspect " + str(root))

    # Both results must reopen through the real application and retain the same identity/tip.
    for name in ("restored", "rebuilt"):
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            port = reserved.getsockname()[1]
        base = "http://127.0.0.1:" + str(port)
        token = secrets.token_hex(32)
        environment = os.environ.copy()
        environment["NODE_ADMIN_TOKEN"] = token
        environment.pop("NODE_READ_TOKEN", None)
        command = [java, "-Xms64m", "-Xmx384m", "-XX:ActiveProcessorCount=2", "-jar", str(jar),
                   "--spring.profiles.active=local", "--server.address=127.0.0.1",
                   "--server.port=" + str(port), "--system.path=" + str(root / name),
                   "--system.networking-enabled=false", "--system.readiness-min-peers=0",
                   "--node.api.public-read=false", "--logging.file.path=" + str(root / (name + "-logs"))]
        with (root / (name + "-node.log")).open("wb") as output:
            process = subprocess.Popen(command, env=environment, stdout=output,
                                       stderr=subprocess.STDOUT, creationflags=flags)
            try:
                deadline = time.monotonic() + 50
                info = None
                while time.monotonic() < deadline and process.poll() is None:
                    try:
                        request = urllib.request.Request(base + "/node/info",
                                                        headers={"Authorization": "Bearer " + token})
                        with urllib.request.urlopen(request, timeout=2) as response:
                            info = json.load(response)
                        if info["ready"]:
                            break
                    except (OSError, ValueError):
                        pass
                    time.sleep(0.2)
                if not info or not info["ready"] or info["height"] != args.expected_height:
                    raise RuntimeError("Restored node state is unexpected; inspect " + str(root))
                if launcher("com.bit.coin.ops.NodeProbe", [base + "/actuator/health/readiness"],
                            name + "-healthy-probe") != 0:
                    raise RuntimeError("Readiness probe failed")
                if launcher("com.bit.coin.ops.NodeProbe", [base + "/missing-probe-endpoint"],
                            name + "-unhealthy-probe") != 1:
                    raise RuntimeError("Probe did not fail closed")
                report["nodes"].append({"name": name, "height": info["height"],
                                        "tip": info["tip"], "peerId": info["peerId"],
                                        "readiness": "UP"})
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
    if any(report["nodes"][0][key] != report["nodes"][1][key] for key in ("height", "tip", "peerId")):
        raise RuntimeError("Restore and reindex disagree")
    report["passed"] = True
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print("PASS: packaged checkpoint/restore/reindex, restored identity/tip, readiness and failing probe; "
          + str(args.report))


if __name__ == "__main__":
    main()
