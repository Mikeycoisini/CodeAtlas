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


class TrainingQuestion(StrictModel):
    category: Literal["CODE_READING", "CALL_CHAIN", "ERROR_JUDGMENT"]
    format: Literal["SINGLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER"]
    prompt: str = Field(min_length=1, max_length=4000)
    options: list[Choice] = Field(max_length=6)
    correctAnswer: str | StrictBool
    referenceExplanation: str = Field(min_length=1, max_length=8000)
    hints: list[Annotated[str, Field(min_length=1, max_length=1000)]] = Field(max_length=5)

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


class TrainingQuestions(StrictModel):
    title: str = Field(min_length=1, max_length=200)
    goal: str = Field(min_length=1, max_length=4000)
    constraints: str = Field(max_length=4000)
    acceptanceCriteria: str = Field(min_length=1, max_length=4000)
    questions: list[TrainingQuestion] = Field(min_length=1, max_length=5)
    citations: list[Citation] = Field(max_length=24)
    uncertainties: list[TextPoint] = Field(max_length=20)


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
