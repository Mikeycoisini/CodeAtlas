"""Run static HTTP acceptance against a local, fixed Spring PetClinic checkout."""
import hashlib
import json
import pathlib
import sys
import time
import urllib.parse
import urllib.request

root = pathlib.Path(sys.argv[1]).resolve()
base = "http://127.0.0.1:8080/api"
token = ""


def request(path, body=None):
    headers = {"Content-Type": "application/json", "X-CodeAtlas-Token": token}
    data = None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request(base + path, data=data, headers=headers), timeout=30) as response:
        return json.load(response)


def await_task(task):
    deadline = time.monotonic() + 120
    while task["status"] in ("QUEUED", "RUNNING"):
        if time.monotonic() > deadline:
            raise TimeoutError(task["id"])
        time.sleep(0.1)
        task = request("/tasks/" + task["id"])
    return task


def source_hashes():
    return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in root.rglob("*") if path.is_file() and ".git" not in path.relative_to(root).parts}


before = source_hashes()
token = request("/session")["token"]
started = time.monotonic()
imported = await_task(request("/imports", {"path": str(root)}))
assert imported["status"] == "SUCCEEDED", imported
import_ms = round((time.monotonic() - started) * 1000)
snapshot = imported["snapshotId"]
started = time.monotonic()
indexed = await_task(request(f"/snapshots/{snapshot}/index", {"rebuild": True}))
index_ms = round((time.monotonic() - started) * 1000)
assert indexed["status"] in ("SUCCEEDED", "PARTIAL_SUCCESS"), indexed
params = urllib.parse.urlencode({"query": "VetRepository.findAll", "kind": "METHOD"})
methods = request(f"/snapshots/{snapshot}/symbols?{params}")
assert methods["total"] == 2, methods
assert sorted((m["startLine"], m["endLine"]) for m in methods["items"]) == [(44, 46), (54, 56)], methods
for method in methods["items"]:
    assert "Transactional" in method["annotations"], method
    content = request(f"/snapshots/{snapshot}/files/{method['fileId']}/content")
    assert content["snapshotId"] == snapshot
    assert "@Transactional" in content["content"].splitlines()[method["startLine"] - 1]
repeat = await_task(request("/imports", {"path": str(root)}))
assert repeat["snapshotId"] == snapshot
assert source_hashes() == before, "Original source changed"
files = request(f"/snapshots/{snapshot}/files?limit=1000")
symbols = request(f"/snapshots/{snapshot}/symbols?limit=1000")
diagnostics = request(f"/tasks/{indexed['id']}/diagnostics?limit=1000")
project = next(p for p in request("/projects") if pathlib.Path(p["sourcePath"]) == root)
metadata = next(s for s in request(f"/projects/{project['id']}/snapshots") if s["id"] == snapshot)
result = {"source": str(root), "commit": metadata["commitHash"], "sourceKind": metadata["sourceKind"],
          "snapshotId": snapshot, "manifestHash": metadata["manifestHash"], "files": files["total"],
          "bytes": sum(f["byteSize"] for f in files["items"]), "symbols": symbols["total"],
          "status": indexed["status"], "diagnostics": diagnostics["total"],
          "importRoundTripMs": import_ms, "indexRoundTripMs": index_ms,
          "transactionMethods": methods["items"], "originalSourceUnchanged": True,
          "repeatImportReusedSnapshot": True}
output = pathlib.Path("target/petclinic-acceptance.json")
output.parent.mkdir(exist_ok=True)
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, ensure_ascii=False, indent=2))
