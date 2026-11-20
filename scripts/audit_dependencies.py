#!/usr/bin/env python3
"""Check the resolved Maven runtime graph against OSV; fail on findings or an incomplete scan."""
import datetime
import json
import pathlib
import re
import sys
import urllib.request


def request(url, data=None):
    body = None if data is None else json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json", "User-Agent": "bitcoin-java-dependency-audit/2"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def main():
    source = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "target/runtime-dependencies.txt")
    destination = pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else "target/dependency-audit.json")
    packages = set()
    for line in source.read_text(encoding="utf-8").splitlines():
        clean = re.sub(r"\x1b\[[0-9;]*m", "", line).strip()
        coordinate = clean.split(" ")[0].split(":")
        if len(coordinate) > 1 and (len(coordinate) not in (5, 6) or coordinate[-1] not in ("compile", "runtime", "test", "provided", "system")):
            raise ValueError("Unrecognized dependency record: " + clean)
        if len(coordinate) >= 5 and coordinate[-1] in ("compile", "runtime"):
            packages.add((coordinate[0] + ":" + coordinate[1], coordinate[-2]))
    if not packages:
        raise ValueError("No resolved runtime dependencies; refusing an empty scan")
    queries = [{"package": {"ecosystem": "Maven", "name": name}, "version": version} for name, version in sorted(packages)]
    response = request("https://api.osv.dev/v1/querybatch", {"queries": queries})
    if len(response.get("results", [])) != len(queries):
        raise ValueError("Incomplete OSV batch response")
    findings = []
    for query, result in zip(queries, response["results"]):
        vulnerabilities = list(result.get("vulns", []))
        # OSV 批量响应按依赖分别分页，每个依赖都要取完自己的后续页。
        while result.get("next_page_token"):
            next_query = dict(query, page_token=result["next_page_token"])
            result = request("https://api.osv.dev/v1/querybatch", {"queries": [next_query]})["results"][0]
            vulnerabilities.extend(result.get("vulns", []))
        for entry in vulnerabilities:
            identifier = entry["id"]
            if not re.fullmatch(r"[A-Za-z0-9_.-]+", identifier):
                raise ValueError("Invalid vulnerability identifier")
            detail = request("https://api.osv.dev/v1/vulns/" + identifier)
            if not detail.get("withdrawn"):
                findings.append({"package": query["package"]["name"], "version": query["version"], "advisory": detail})
    report = {"scannedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(), "source": "https://api.osv.dev",
              "packages": queries, "findings": findings, "complete": True}
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("OSV: {} resolved packages, {} findings; report: {}".format(len(queries), len(findings), destination))
    for finding in findings:
        print("{} {} {}".format(finding["package"], finding["version"], finding["advisory"]["id"]))
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
