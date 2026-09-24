import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts import run_agent


class RunAgentEnvironmentTest(unittest.TestCase):
    def test_child_gets_only_model_and_user_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".env").write_text(
                "MODEL_BASE_URL=https://models.example/v1\n"
                "MODEL_API_KEY=synthetic=key#part\n"
                "MODEL_NAME=test-model\n"
                "MERCHANT_JWT_SECRET=backend-synthetic\n"
                "SPRING_DATASOURCE_PASSWORD=db-synthetic\n"
                "SPRING_RABBITMQ_PASSWORD=queue-synthetic\n",
                encoding="utf-8",
            )
            parent = {
                "PATH": os.environ.get("PATH", ""),
                "SUPERMALL_TOKEN": "synthetic-user-token",
                "MODEL_API_KEY": "stale-parent-model-key",
                "MERCHANT_JWT_SECRET": "parent-backend-synthetic",
                "SPRING_DATASOURCE_PASSWORD": "parent-db-synthetic",
                "SPRING_RABBITMQ_PASSWORD": "parent-queue-synthetic",
                "AWS_SECRET_ACCESS_KEY": "unrelated-parent-synthetic",
            }

            child = run_agent.load_agent_environment(root, parent)
            probe = """
import json, os
print(json.dumps({
  "model_key": os.getenv("MODEL_API_KEY"),
  "token": os.getenv("SUPERMALL_TOKEN"),
  "backend_present": any(os.getenv(k) for k in (
    "MERCHANT_JWT_SECRET", "SPRING_DATASOURCE_PASSWORD", "SPRING_RABBITMQ_PASSWORD")),
  "unrelated_present": bool(os.getenv("AWS_SECRET_ACCESS_KEY"))
}))
"""
            observed = json.loads(subprocess.check_output(
                [sys.executable, "-c", probe], env=child, text=True))

            self.assertEqual("synthetic=key#part", observed["model_key"])
            self.assertEqual("synthetic-user-token", observed["token"])
            self.assertFalse(observed["backend_present"])
            self.assertFalse(observed["unrelated_present"])

    def test_ignored_token_file_supplies_token_when_parent_does_not(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".env").write_text(
                "MODEL_BASE_URL=https://models.example/v1\n"
                "MODEL_API_KEY=synthetic-model-key\n"
                "MODEL_NAME=test-model\n",
                encoding="utf-8",
            )
            (root / "agent-token.env").write_text(
                "SUPERMALL_TOKEN=synthetic-file-token\n", encoding="utf-8")

            child = run_agent.load_agent_environment(root, {"PATH": os.environ.get("PATH", "")})

            self.assertEqual("synthetic-file-token", child["SUPERMALL_TOKEN"])

    def test_missing_model_configuration_stops_before_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".env").write_text(
                "MODEL_BASE_URL=https://models.example/v1\nMODEL_NAME=test-model\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "MODEL_API_KEY"):
                run_agent.load_agent_environment(root, {"SUPERMALL_TOKEN": "synthetic-user-token"})

    def test_task6_token_file_can_supply_fresh_user_token(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / ".env").write_text(
                "MODEL_BASE_URL=https://models.example/v1\n"
                "MODEL_API_KEY=synthetic-model-key\n"
                "MODEL_NAME=test-model\n",
                encoding="utf-8",
            )
            (root / "agent" / "target").mkdir(parents=True)
            (root / "agent" / "target" / "task6.env").write_text(
                "SUPERMALL_TOKEN=synthetic-task6-token\n", encoding="utf-8")

            child = run_agent.load_agent_environment(root, {"PATH": os.environ.get("PATH", "")})

            self.assertEqual("synthetic-task6-token", child["SUPERMALL_TOKEN"])

    def test_java_command_forces_utf8_before_jar(self):
        command = run_agent.build_java_command(Path("agent/target/agent.jar"))

        self.assertEqual("java", command[0])
        self.assertEqual(["-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"], command[1:3])
        self.assertEqual("-jar", command[3])
        self.assertEqual(Path("agent/target/agent.jar"), Path(command[4]))


if __name__ == "__main__":
    unittest.main()
