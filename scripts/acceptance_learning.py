"""Explicit real-model acceptance restricted to the fixed public PetClinic snapshot."""
import argparse
import json
from pathlib import Path
import time
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("--generate-public-petclinic", action="store_true", required=True)
parser.add_argument("--output", default="target/v02-real-result.json")
args = parser.parse_args()
base = "http://127.0.0.1:8080/api"
token = json.load(urllib.request.urlopen(base + "/session"))["token"]

def call(route, body=None, method=None):
    request = urllib.request.Request(base + route, data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "X-CodeAtlas-Token": token}, method=method)
    with urllib.request.urlopen(request, timeout=90) as response:
        return json.load(response)

project = next(item for item in call("/projects") if item["name"] == "spring-petclinic")
snapshot = next(item for item in call("/projects/" + project["id"] + "/snapshots")
    if item["commitHash"] == "500158f732419217507c7656904b8e6aa1bcc0d6" and item["sourceKind"] == "GIT_CLEAN")
page = call("/snapshots/" + snapshot["id"] + "/symbols?" + urllib.parse.urlencode({"query": "VetRepository.findAll", "kind": "METHOD"}))
symbol = next(item for item in page["items"] if item["startLine"] == 44)
provider = call("/ai/provider")
assert provider["provider"] == "deepseek" and provider["model"] == "deepseek-flash"
assert provider["baseUrl"] == "https://api.deepseek.com" and provider["maxTokens"] <= 4096
context = call("/ai/contexts", {"snapshotId": snapshot["id"], "generationId": page["generationId"], "symbolId": symbol["id"],
    "question": "解释 findAll 方法声明、readOnly 事务注解及静态片段能确定和不能确定的行为。", "depth": "BRIEF", "purpose": "METHOD_EXPLANATION"})
assert all(item["filePath"] == "src/main/java/org/springframework/samples/petclinic/vet/VetRepository.java" for item in context["sources"])
assert all(item["snapshotId"] == snapshot["id"] for item in context["sources"])
print(json.dumps({"preview": [{"path": source["filePath"], "start": source["startLine"], "end": source["endLine"]} for source in context["sources"]],
    "characters": context["characterCount"], "bytes": context["byteCount"], "model": provider["model"]}), flush=True)
consent = call("/ai/consents", {"contextId": context["id"], "decision": "ALLOW"})
started = time.monotonic()
task = call("/ai/generations", {"consentId": consent["id"], "idempotencyKey": str(uuid.uuid4())})
while task["status"] in {"QUEUED", "RUNNING"}:
    if time.monotonic() - started > 120:
        raise TimeoutError(task["id"])
    time.sleep(.5)
    task = call("/ai/generations/" + task["id"])
assert task["status"] == "SUCCEEDED", (task["status"], task["errorCode"], task["message"])
result = task["result"]
assert result["citations"] and all(item["verificationStatus"] == "VERIFIED" for item in result["citations"])
body = result["summary"] + "\n\n" + result["explanation"] + "\n\n人工笔记：运行时代理和具体数据库效果需要结合实际配置验证。"
def save(kind, fields):
    return call("/" + kind, {**fields, "idempotencyKey": str(uuid.uuid4())})
document = save("documents", {"title": "PetClinic findAll 真实联调", "body": body, "generationId": task["id"], "tags": ["PetClinic", "事务"]})
knowledge = save("knowledge", {"title": "只读事务的静态证据", "body": body, "generationId": task["id"], "tags": ["事务"]})
card = save("cards", {"question": "PetClinic findAll 的只读事务能说明什么？", "answer": result["summary"], "explanation": result["explanation"],
    "codeExample": context["sources"][0]["content"], "notes": "真实模型结果已核对固定源码引用", "knowledgeId": knowledge["id"], "sourceIds": knowledge["sourceIds"]})
output = {"provider": provider, "commit": snapshot["commitHash"], "snapshotId": snapshot["id"], "generationId": page["generationId"],
    "contextId": context["id"], "taskId": task["id"], "elapsedSeconds": round(time.monotonic() - started, 2), "characterCount": context["characterCount"], "byteCount": context["byteCount"],
    "result": result, "records": {"documents": document, "knowledge": knowledge, "cards": card}}
Path(args.output).write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"status": task["status"], "elapsedSeconds": output["elapsedSeconds"], "usage": result["usage"],
    "citations": len(result["citations"]), "records": {kind: record["id"] for kind, record in output["records"].items()}}), flush=True)
