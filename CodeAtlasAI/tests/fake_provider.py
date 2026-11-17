"""Deterministic loopback-only upstream for end-to-end acceptance."""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

state = {"calls": 0, "mode": "NORMAL"}
lock = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def respond(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/calls":
            with lock:
                self.respond(200, dict(state))
        else:
            self.respond(404, {})

    def do_POST(self):
        size = int(self.headers.get("Content-Length", "0"))
        if size > 1_048_576:
            return self.respond(413, {})
        body = json.loads(self.rfile.read(size))
        if self.path == "/reset":
            with lock:
                state["calls"] = 0
                state["mode"] = body.get("mode", "NORMAL")
            return self.respond(200, {"reset": True})
        if self.path != "/chat/completions" or self.headers.get("Authorization") != "Bearer fixture-key":
            return self.respond(401, {})
        with lock:
            state["calls"] += 1
            mode = state["mode"]
        if mode == "ERROR":
            return self.respond(429, {"error": "fixture rate limit"})
        context = json.loads(body["messages"][-1]["content"])
        if context.get("operation") == "LEARNING_GUIDE":
            steps = [{"stepId": step["id"], "reason": "结合所选目标与资料", "actionAdvice": "阅读资料并记录自己的理解，可随时跳过。"} for step in reversed(context["guide"]["steps"])]
            if mode == "FORGED_STEP":
                steps[0]["stepId"] = "invented"
            result = {"summary": "建议先从复盘目标着手，按需调整学习顺序。", "steps": steps, "citations": [], "uncertainties": ["固定验收建议，需要用户核对学习目标。"]}
            return self.respond(200, {"choices": [{"message": {"content": json.dumps(result, ensure_ascii=False)}, "finish_reason": "stop"}],
                                      "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}})
        source = context["sources"][0]
        citation = {"sourceId": source["sourceId"], "filePath": source["filePath"] if mode != "FORGED" else "invented.java",
                    "startLine": source["startLine"], "endLine": source["endLine"], "explanation": "固定样本的方法声明"}
        result = {"title": "事务方法解释", "summary": "基于预览片段的测试解释", "keyPoints": ["固定快照中的方法声明"],
                  "explanation": "该结果用于确定性验收，源码关系由静态证据提供。", "citations": [citation],
                  "uncertainties": ["运行时事务配置需要结合项目核对"]}
        if context.get("operation") == "TRAINING_QUESTIONS":
            parameters = context["training"]
            questions = []
            for index in range(parameters["questionCount"]):
                kind = parameters["formats"][index % len(parameters["formats"]) ]
                question = {"category": "CODE_READING", "format": kind, "prompt": "阅读 run 方法，判断其返回值", "options": [],
                            "correctAnswer": True, "referenceExplanation": "固定源码使用 return 1，需结合片段核对。", "hints": ["查看 return 语句"]}
                if kind == "SINGLE_CHOICE":
                    question["options"] = [{"id": "A", "text": "返回 1"}, {"id": "B", "text": "返回 2"}]
                    question["correctAnswer"] = "A"
                elif kind == "SHORT_ANSWER":
                    question["correctAnswer"] = "返回固定整数 1"
                questions.append(question)
            result = {"title": "源码理解训练", "goal": parameters["goal"], "constraints": "依据固定源码作答", "acceptanceCriteria": "说明返回值并关联源码证据",
                      "questions": questions, "citations": [citation], "uncertainties": ["确定性验收候选，需核对实际源码"]}
        elif context.get("operation") == "TRAINING_FEEDBACK":
            result = {"strengths": ["已经提交独立回答"], "gaps": ["可以补充源码行依据"], "explanation": "请将回答与固定来源逐项核对。此反馈为确定性验收数据。",
                      "suggestedVerdict": "PARTIAL", "citations": [citation], "uncertainties": ["辅助建议需要人工核对"]}
        elif context.get("operation") == "PRACTICE_FEEDBACK":
            result = {"strengths": ["已经提交独立代码修改"], "gaps": ["可以补充测试与边界说明"], "explanation": "请结合代码差异与用户报告逐项核对。此反馈为确定性验收数据。",
                      "suggestedVerdict": "PARTIAL", "citations": [citation], "uncertainties": ["外部报告由用户提供，需人工核对"]}
        self.respond(200, {"choices": [{"message": {"content": json.dumps(result, ensure_ascii=False)}, "finish_reason": "stop"}],
                           "usage": {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}})


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 8002), Handler).serve_forever()
