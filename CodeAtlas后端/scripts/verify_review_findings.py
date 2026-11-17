"""Record D01/D02 HTTP observations using synthetic sources on an isolated server."""
import argparse
import json
from pathlib import Path
import tempfile
import time
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("--base", default="http://127.0.0.1:18080/api")
parser.add_argument("--output", default="target/independent-review-http.json")
args = parser.parse_args()
observations = []
token = ""


def call(route, body=None):
    request = urllib.request.Request(args.base + route, data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "X-CodeAtlas-Token": token})
    with urllib.request.urlopen(request, timeout=30) as response:
        result = json.load(response)
        recorded = {key: value for key, value in result.items() if key != "token"} if isinstance(result, dict) else result
        observations.append({"method": request.get_method(), "route": route, "request": body,
            "status": response.status, "response": recorded})
        return result


def wait(task):
    deadline = time.monotonic() + 30
    while task["status"] in ("QUEUED", "RUNNING"):
        assert time.monotonic() < deadline, "Task timeout"
        time.sleep(.05)
        task = call("/tasks/" + task["id"])
    assert task["status"] == "SUCCEEDED", task
    return task


token = call("/session")["token"]
root = Path(tempfile.mkdtemp(prefix="review-fixture-", dir=Path("target").resolve()))
files = {
    "sample/Api.java": 'package sample; public interface Api { String run(); }',
    "sample/Impl.java": 'package sample; public class Impl implements Api { public String run() { return "old-value"; } }',
    "sample/Caller.java": 'package sample; public class Caller { public String call(Api api) { return api.run(); } }',
    "sample/TxService.java": 'package sample; import org.springframework.transaction.annotation.Transactional; public class TxService { @Transactional public void pay() {} }',
    "org/springframework/transaction/annotation/Transactional.java": 'package org.springframework.transaction.annotation; public @interface Transactional {}'
}
for name, content in files.items():
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
snapshot = wait(call("/imports", {"path": str(root)}))["snapshotId"]
wait(call("/snapshots/" + snapshot + "/index", {"rebuild": False}))
page = call("/snapshots/" + snapshot + "/symbols?limit=1000")
symbol = next(item for item in page["items"] if item["qualifiedName"] == "sample.Impl.run")
source = call("/sources", {"snapshotId": snapshot, "generationId": page["generationId"], "symbolId": symbol["id"]})
knowledge = call("/knowledge", {"title": "Independent D01 knowledge", "body": "Synthetic original source",
    "sourceIds": [source["id"]], "idempotencyKey": str(uuid.uuid4())})
assert source["snapshotOutdated"] is False
assert call("/sources/" + source["id"] + "/check-current", {})["status"] == "MATCHED"
path = root / "sample/Impl.java"
path.write_text(path.read_text(encoding="utf-8").replace("old-value", "new-value"), encoding="utf-8")
after_change = call("/sources/" + source["id"])
knowledge_after_change = call("/knowledge/" + knowledge["id"])
checked_change = call("/sources/" + source["id"] + "/check-current", {})
assert checked_change["status"] == "CHANGED"
assert "old-value" in after_change["content"] and "new-value" not in after_change["content"]
assert len(call("/projects/" + source["projectId"] + "/snapshots")) == 1
new_snapshot = wait(call("/imports", {"path": str(root)}))["snapshotId"]
assert new_snapshot != snapshot
after_import = call("/sources/" + source["id"])
assert after_import["snapshotOutdated"] is True
assert after_import["content"] == source["content"]
relations = []
for item in page["items"]:
    result = call("/snapshots/" + snapshot + "/symbols/" + item["id"] + "/relations?limit=1000&generationId=" + page["generationId"])
    relations.extend(result["items"])
unique = list({json.dumps(item, sort_keys=True): item for item in relations}.values())
summary = {"D01": {"modifiedWithoutImport_snapshotOutdated": after_change["snapshotOutdated"],
    "knowledge_sourceOutdated": knowledge_after_change["sources"][0]["snapshotOutdated"],
    "afterReimport_snapshotOutdated": after_import["snapshotOutdated"], "historicalBytesPreserved": True},
    "D02": {"statuses": sorted({item["status"] for item in unique}),
        "inferredCount": sum(item["status"] == "INFERRED" for item in unique)},
    "snapshotId": snapshot, "knowledgeId": knowledge["id"]}
summary["D01"]["explicitCheckStatus"] = checked_change["status"]
assert summary["D02"]["inferredCount"] > 0
Path(args.output).write_text(json.dumps({"summary": summary, "http": observations}, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(summary, ensure_ascii=True))
