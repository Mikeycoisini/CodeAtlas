import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from codeatlas_ai.local_config import service_token
from codeatlas_ai.settings import Settings


class LocalConfigTest(unittest.TestCase):
    def test_local_file_and_automatic_token(self):
        # Windows 上杀毒/索引进程可能短暂占用新建文件，清理失败不应使配置测试失败。
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temporary:
            project = Path(temporary) / "CodeAtlasAI"
            project.mkdir()
            first = Settings.load({}, directory=project)
            self.assertTrue((project / ".env").exists())
            self.assertEqual(len(first.service_token), 64)
            (project / ".env").write_text("CODEATLAS_LLM_API_KEY=local-key\nCODEATLAS_LLM_MODEL=local-model\n", encoding="utf-8")
            second = Settings.load({}, directory=project)
            self.assertEqual(second.api_key, "local-key")
            self.assertEqual(second.model, "local-model")
            self.assertEqual(first.service_token, second.service_token)
            override = Settings.load({"CODEATLAS_LLM_MODEL": "override"}, directory=project)
            self.assertEqual(override.model, "override")

    def test_concurrent_starts_share_complete_token(self):
        # Windows 上杀毒/索引进程可能短暂占用新建文件，清理失败不应使配置测试失败。
        with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as temporary:
            with ThreadPoolExecutor(max_workers=8) as pool:
                tokens = list(pool.map(lambda _: service_token(Path(temporary)), range(24)))
            self.assertEqual(len(set(tokens)), 1)
            self.assertEqual(len(tokens[0]), 64)


if __name__ == "__main__":
    unittest.main()
