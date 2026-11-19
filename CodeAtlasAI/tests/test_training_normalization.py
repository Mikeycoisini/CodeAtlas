import unittest
from pydantic import ValidationError
from codeatlas_ai.schemas import TrainingQuestion

class AnswerNormalizationTests(unittest.TestCase):
    def question(self, answer):
        return TrainingQuestion.model_validate(dict(category="CODE_READING", format="TRUE_FALSE", prompt="Returns one?", correctAnswer=answer, referenceExplanation="Read return"))
    def test_unknown_boolean_strings_are_rejected(self):
        for value in ["unknown", "", "maybe", "2"]:
            with self.subTest(value=value), self.assertRaises(ValidationError): self.question(value)
    def test_explicit_boolean_strings_keep_their_meaning(self):
        for value in ["false", "no", "0"]: self.assertIs(self.question(value).correctAnswer, False)
        for value in ["true", "yes", "1"]: self.assertIs(self.question(value).correctAnswer, True)
