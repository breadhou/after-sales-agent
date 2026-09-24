"""Launch the Agent without giving it supermall's backend credentials."""

import os
import subprocess
from pathlib import Path
from typing import Mapping


MODEL_KEYS = ("MODEL_BASE_URL", "MODEL_API_KEY", "MODEL_NAME")
RUNTIME_KEYS = (
    "PATH", "PATHEXT", "SystemRoot", "WINDIR", "TEMP", "TMP", "TMPDIR",
    "JAVA_HOME", "USERPROFILE", "HOME", "LANG", "LC_ALL",
)


def _read_env_file(path: Path, allowed_keys: set[str]) -> dict[str, str]:
    if not path.is_file():
        return {}
    values: dict[str, str] = {}
    with path.open(encoding="utf-8-sig") as source:
        for line in source:
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            key, separator, value = stripped.partition("=")
            key = key.strip()
            if key not in allowed_keys:
                continue
            if not separator:
                raise ValueError(f"无效的环境变量行，文件：{path.name}")
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            values[key] = value
    return values


def _required(key: str, value: str | None) -> str:
    if value is None or not value.strip() or value.strip().startswith("${"):
        raise ValueError(f"缺少有效的 {key}；请检查本地凭据文件或进程环境")
    return value


def load_agent_environment(repository_root: Path,
                           parent_environment: Mapping[str, str]) -> dict[str, str]:
    """Build an allowlisted environment for the Java Agent process."""
    project_values = _read_env_file(
        repository_root / ".env", set(MODEL_KEYS) | {"SUPERMALL_BASE_URL"})
    token_values = _read_env_file(
        repository_root / "agent" / "target" / "task6.env", {"SUPERMALL_TOKEN"})
    if not token_values.get("SUPERMALL_TOKEN"):
        token_values = _read_env_file(repository_root / "agent-token.env", {"SUPERMALL_TOKEN"})
    child = {key: parent_environment[key] for key in RUNTIME_KEYS
             if key in parent_environment}
    for key in MODEL_KEYS:
        child[key] = _required(key, project_values.get(key))
    child["SUPERMALL_TOKEN"] = _required(
        "SUPERMALL_TOKEN",
        parent_environment.get("SUPERMALL_TOKEN") or token_values.get("SUPERMALL_TOKEN"),
    )
    base_url = project_values.get("SUPERMALL_BASE_URL") or parent_environment.get("SUPERMALL_BASE_URL")
    if base_url:
        child["SUPERMALL_BASE_URL"] = base_url
    return child


def build_java_command(jar: Path) -> list[str]:
    return ["java", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", str(jar)]


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    child_environment = load_agent_environment(root, os.environ)
    jar = root / "agent" / "target" / "agent.jar"
    if not jar.is_file():
        raise FileNotFoundError("找不到 agent/target/agent.jar；请先在仓库根目录运行 Maven package")
    return subprocess.run(build_java_command(jar), cwd=root,
                          env=child_environment, check=False).returncode


if __name__ == "__main__":
    raise SystemExit(main())
