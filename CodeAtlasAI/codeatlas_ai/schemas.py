from datetime import datetime
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StrictBool, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class GenerationRequest(StrictModel):
    operation: Literal["EXPLANATION", "TRAINING_QUESTIONS", "TRAINING_FEEDBACK", "LEARNING_GUIDE", "PRACTICE_FEEDBACK"] = "EXPLANATION"
    requestId: str = Field(min_length=1, max_length=100)
    contextJson: str = Field(min_length=2, max_length=200_000)
    contextDigest: str = Field(min_length=1, max_length=128)
    providerFingerprint: str = Field(min_length=1, max_length=128)
    expiresAt: datetime


class Citation(StrictModel):
    sourceId: str = Field(min_length=1, max_length=100)
    filePath: str = Field(min_length=1, max_length=2048)
    startLine: int = Field(ge=1)
    endLine: int = Field(ge=1)
    explanation: str = Field(default="", max_length=2000)

    @model_validator(mode="after")
    def valid_range(self):
        if self.endLine < self.startLine:
            raise ValueError("Reversed citation range")
        return self


class GuideStep(StrictModel):
    stepId: Literal["BUSINESS", "SOURCE", "PRINCIPLE", "CODE", "VERIFY", "REFLECT"]
    reason: str = Field(min_length=1, max_length=2000)
    actionAdvice: str = Field(min_length=1, max_length=4000)


class GuideSuggestions(StrictModel):
    summary: str = Field(min_length=1, max_length=4000)
    steps: list[GuideStep] = Field(min_length=6, max_length=6)
    citations: list[Citation] = Field(max_length=24)
    uncertainties: list[Annotated[str, Field(min_length=1, max_length=2000)]] = Field(max_length=20)

    @model_validator(mode="after")
    def exact_steps(self):
        if len({step.stepId for step in self.steps}) != 6:
            raise ValueError("Every existing step must appear exactly once")
        return self


TextPoint = Annotated[str, Field(min_length=1, max_length=2000)]


class Explanation(StrictModel):
    title: str = Field(min_length=1, max_length=200)
    summary: str = Field(min_length=1, max_length=4000)
    keyPoints: list[TextPoint] = Field(max_length=20)
    explanation: str = Field(min_length=1, max_length=32_000)
    citations: list[Citation] = Field(max_length=24)
    uncertainties: list[TextPoint] = Field(max_length=20)


class Choice(StrictModel):
    id: str = Field(min_length=1, max_length=40)
    text: str = Field(min_length=1, max_length=500)


class TrainingQuestion(BaseModel):
    model_config = ConfigDict(extra="ignore")

    category: str = Field(min_length=1, max_length=40)
    format: Literal["SINGLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER"]
    prompt: str = Field(min_length=1, max_length=4000)
    options: list[Choice] = Field(default_factory=list, max_length=6)
    correctAnswer: str | StrictBool
    referenceExplanation: str = Field(min_length=1, max_length=8000)
    hints: list[Annotated[str, Field(min_length=1, max_length=1000)]] = Field(default_factory=list, max_length=5)

    @model_validator(mode="before")
    @classmethod
    def normalize(cls, data):
        if not isinstance(data, dict):
            return data
        raw = dict(data)
        raw.pop("id", None)
        category = str(raw.get("category") or "").strip().upper()
        if category not in {"CODE_READING", "CALL_CHAIN", "ERROR_JUDGMENT"}:
            raw["category"] = "CODE_READING"
        fmt = raw.get("format")
        options = raw.get("options") or []
        if fmt == "SINGLE_CHOICE":
            normalized = []
            for index, option in enumerate(options):
                letter = chr(ord("A") + index)
                if isinstance(option, str):
                    normalized.append({"id": letter, "text": option})
                elif isinstance(option, dict):
                    oid = str(option.get("id") or letter)
                    text = option.get("text") or option.get("label") or option.get("value") or ""
                    normalized.append({"id": oid, "text": str(text)})
            raw["options"] = normalized
            ids = [item["id"] for item in normalized]
            answer = raw.get("correctAnswer", raw.get("correct_answer"))
            if isinstance(answer, str) and answer not in ids:
                key = answer.strip()
                if len(key) == 1 and key.isalpha():
                    position = ord(key.upper()) - ord("A")
                    if 0 <= position < len(ids):
                        raw["correctAnswer"] = ids[position]
                elif key.isdigit() and 1 <= int(key) <= len(ids):
                    raw["correctAnswer"] = ids[int(key) - 1]
        else:
            raw["options"] = []
            if fmt == "TRUE_FALSE" and isinstance(raw.get("correctAnswer"), str):
                raw["correctAnswer"] = raw["correctAnswer"].strip().lower() in {"true", "yes", "1", "对", "正确"}
        if raw.get("hints") is None:
            raw["hints"] = []
        return raw

    @model_validator(mode="after")
    def valid_answer(self):
        if self.format == "TRUE_FALSE":
            if not isinstance(self.correctAnswer, bool) or self.options:
                raise ValueError("Boolean answer required")
        elif self.format == "SINGLE_CHOICE":
            ids = [choice.id for choice in self.options]
            if len(ids) < 2 or len(ids) != len(set(ids)) or self.correctAnswer not in ids:
                raise ValueError("Unique choices and a matching answer required")
        elif self.options or not isinstance(self.correctAnswer, str) or not self.correctAnswer.strip() or len(self.correctAnswer) > 4000:
            raise ValueError("Short reference answer required")
        if not self.prompt.strip() or not self.referenceExplanation.strip():
            raise ValueError("Question text cannot be blank")
        return self


class KnowledgePoint(BaseModel):
    model_config = ConfigDict(extra="ignore")

    title: str = ""
    body: str = ""
    code: str = ""
    analysis: str = ""
    sourceId: str = ""
    startLine: int = 1
    endLine: int = 1

    @model_validator(mode="before")
    @classmethod
    def normalize(cls, data):
        if not isinstance(data, dict):
            return data
        raw = dict(data)
        limits = {"title": 200, "body": 16_000, "code": 8_000, "analysis": 8_000}
        aliases = {"body": ("summary", "content", "knowledge"), "code": ("codeSnippet", "snippet"),
                   "analysis": ("codeAnalysis", "explanation")}
        for key, limit in limits.items():
            value = raw.get(key)
            if value is None:
                for alias in aliases.get(key, ()):
                    value = raw.get(alias)
                    if value is not None:
                        break
            raw[key] = str(value).strip()[:limit] if value is not None else ""
        raw["sourceId"] = str(raw.get("sourceId") or raw.get("source_id") or "").strip()[:100]
        for key in ("startLine", "endLine"):
            try:
                raw[key] = max(1, int(raw.get(key) or 1))
            except (TypeError, ValueError):
                raw[key] = 1
        if raw["endLine"] < raw["startLine"]:
            raw["endLine"] = raw["startLine"]
        return raw


class TrainingQuestions(BaseModel):
    model_config = ConfigDict(extra="ignore")

    title: str = Field(min_length=1, max_length=200)
    goal: str = Field(min_length=1, max_length=4000)
    constraints: str = Field(default="", max_length=4000)
    acceptanceCriteria: str = Field(min_length=1, max_length=4000)
    knowledge: list[KnowledgePoint] = Field(default_factory=list, max_length=8)
    questions: list[TrainingQuestion] = Field(min_length=1, max_length=10)
    citations: list[Citation] = Field(default_factory=list, max_length=24)
    uncertainties: list[TextPoint] = Field(default_factory=list, max_length=20)

    @model_validator(mode="before")
    @classmethod
    def normalize_knowledge(cls, data):
        if not isinstance(data, dict):
            return data
        raw = dict(data)
        points = raw.get("knowledge")
        if not isinstance(points, list):
            raw["knowledge"] = []
        else:
            raw["knowledge"] = [item for item in points if isinstance(item, dict)][:8]
        return raw


class TrainingFeedback(StrictModel):
    strengths: list[TextPoint] = Field(max_length=20)
    gaps: list[TextPoint] = Field(max_length=20)
    explanation: str = Field(min_length=1, max_length=8000)
    suggestedVerdict: Literal["CORRECT", "PARTIAL", "INCORRECT", "UNSURE"]
    citations: list[Citation] = Field(max_length=24)
    uncertainties: list[TextPoint] = Field(max_length=20)


class PracticeFeedback(StrictModel):
    strengths: list[TextPoint] = Field(max_length=20)
    gaps: list[TextPoint] = Field(max_length=20)
    explanation: str = Field(min_length=1, max_length=8000)
    suggestedVerdict: Literal["PASS", "PARTIAL", "NEEDS_WORK", "UNSURE"]
    citations: list[Citation] = Field(max_length=24)
    uncertainties: list[TextPoint] = Field(max_length=20)
