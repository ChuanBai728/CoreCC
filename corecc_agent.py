"""
Custom Harbor agent that wraps CoreCC (Java AI coding agent)
for Terminal-Bench 2.0 evaluation.
"""
import os
import shlex
from pathlib import Path

from harbor.agents.installed.base import (
    BaseInstalledAgent,
    with_prompt_template,
)
from harbor.environments.base import BaseEnvironment
from harbor.models.agent.context import AgentContext


class CoreCCAgent(BaseInstalledAgent):
    """CoreCC — lightweight local AI coding agent (Java)."""

    _JAR_NAME = "corecc-0.3.0.jar"
    _INSTALL_DIR = "/opt/corecc"
    _CAPABILITY_DIR = "/opt/corecc/capabilities"

    @staticmethod
    def name() -> str:
        return "corecc"

    def version(self) -> str | None:
        return "0.3.0"

    async def install(self, environment: BaseEnvironment) -> None:
        """Ensure a compatible Java runtime is available in the task container."""
        if os.environ.get("CORECC_SKIP_JAVA_INSTALL", "").lower() in {"1", "true", "yes"}:
            await self.exec_as_root(
                environment,
                command="java -version",
                timeout_sec=30,
            )
            return

        install_command = os.environ.get("CORECC_JAVA_INSTALL_COMMAND")
        if not install_command:
            install_command = (
                "unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY; "
                "if command -v apt-get >/dev/null 2>&1; then "
                "  for i in 1 2 3; do "
                "    apt-get update -o Acquire::Retries=3 && "
                "    (DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openjdk-17-jre-headless || "
                "     DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openjdk-21-jre-headless || "
                "     DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openjdk-21-jre || "
                "     DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends default-jre-headless) && "
                "    break || sleep $((i * 10)); "
                "  done; "
                "elif command -v apk >/dev/null 2>&1; then "
                "  apk add --no-cache openjdk17-jre || apk add --no-cache openjdk21-jre; "
                "elif command -v dnf >/dev/null 2>&1; then "
                "  dnf install -y java-17-openjdk-headless || dnf install -y java-21-openjdk-headless; "
                "elif command -v yum >/dev/null 2>&1; then "
                "  yum install -y java-17-openjdk-headless || yum install -y java-21-openjdk-headless; "
                "else "
                "  echo 'No supported package manager found for Java install' >&2; exit 127; "
                "fi"
            )

        await self.exec_as_root(
            environment,
            command=(
                "if command -v java >/dev/null 2>&1; then "
                "java -version; "
                "else "
                f"{install_command}; "
                "if command -v java >/dev/null 2>&1; then "
                "java -version; "
                "else "
                "echo '[CoreCC wrapper warning] Java install failed; agent run will be skipped if java remains unavailable' >&2; "
                "fi"
                "; fi"
            ),
            timeout_sec=300,
        )

    async def setup(self, environment: BaseEnvironment) -> None:
        """Install Java and upload the pre-built CoreCC fat JAR."""
        # Call parent setup() which triggers install()
        await super().setup(environment)

        await self.exec_as_root(
            environment,
            command=f"mkdir -p {self._INSTALL_DIR}",
        )

        # Find the JAR on the host
        jar_path = Path(__file__).parent / "target" / self._JAR_NAME
        if not jar_path.exists():
            raise FileNotFoundError(
                f"CoreCC JAR not found at {jar_path}. "
                "Run 'mvn package -DskipTests' first."
            )

        await environment.upload_file(
            source_path=jar_path,
            target_path=f"{self._INSTALL_DIR}/{self._JAR_NAME}",
        )

    @with_prompt_template
    async def run(
        self,
        instruction: str,
        environment: BaseEnvironment,
        context: AgentContext,
    ) -> None:
        """Run CoreCC in single-shot mode with the task instruction."""
        escaped_instruction = shlex.quote(instruction)
        capability_env = await self._prepare_capabilities(environment)

        # Build environment export prefix for the command
        env_exports = []
        api_key = (
            os.environ.get("OPENAI_API_KEY")
            or os.environ.get("CORECC_API_KEY")
            or ""
        )
        if api_key:
            env_exports.append(f"export OPENAI_API_KEY={shlex.quote(api_key)}")

        base_url = os.environ.get("OPENAI_BASE_URL") or os.environ.get("CORECC_BASE_URL")
        if base_url:
            env_exports.append(f"export OPENAI_BASE_URL={shlex.quote(base_url)}")

        model = os.environ.get("CORECC_MODEL", "gpt-4o")
        env_exports.append(f"export CORECC_MODEL={shlex.quote(model)}")

        max_tokens = os.environ.get("CORECC_MAX_TOKENS", "32768")
        bench_max_tokens = os.environ.get("CORECC_BENCH_MAX_TOKENS", "8192")
        if os.environ.get("CORECC_DISABLE_BENCH_TOKEN_CAP", "").lower() not in {"1", "true", "yes"}:
            try:
                max_tokens = str(min(int(max_tokens), int(bench_max_tokens)))
            except ValueError:
                max_tokens = bench_max_tokens
        env_exports.append(f"export CORECC_MAX_TOKENS={shlex.quote(max_tokens)}")

        temperature = os.environ.get("CORECC_TEMPERATURE", "0")
        env_exports.append(f"export CORECC_TEMPERATURE={shlex.quote(temperature)}")

        llm_retries = os.environ.get("CORECC_LLM_RETRIES", "4")
        env_exports.append(f"export CORECC_LLM_RETRIES={shlex.quote(llm_retries)}")

        recovery_rounds = os.environ.get("CORECC_TRANSIENT_LLM_RECOVERY_ROUNDS", "2")
        env_exports.append(
            "export CORECC_TRANSIENT_LLM_RECOVERY_ROUNDS="
            f"{shlex.quote(recovery_rounds)}"
        )

        for key in ("CORECC_SKILLS", "CORECC_MCP_CONFIG", "CORECC_MCP_TIMEOUT_SEC"):
            value = capability_env.get(key) or os.environ.get(key)
            if value:
                env_exports.append(f"export {key}={shlex.quote(value)}")

        env_exports.append("export CORECC_BENCH_MODE=1")
        env_exports.append("unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY all_proxy ALL_PROXY")

        env_prefix = " && ".join(env_exports) + " && " if env_exports else ""

        # Run CoreCC in single-shot mode
        await self.exec_as_agent(
            environment,
            command=(
                f"{env_prefix}"
                f"mkdir -p /logs/agent && "
                f"if [ -d /app ]; then cd /app; else "
                f"echo '[CoreCC wrapper warning] /app missing; using current directory' | tee /logs/agent/corecc.txt; "
                f"fi && "
                f"java -jar {self._INSTALL_DIR}/{self._JAR_NAME} "
                f"-p {escaped_instruction} "
                f"> /tmp/corecc-run.log 2>&1; "
                f"status=$?; "
                f"cat /tmp/corecc-run.log | tee -a /logs/agent/corecc.txt; "
                f"echo \"[CoreCC wrapper] java exit code: $status\" | tee -a /logs/agent/corecc.txt; "
                f"exit 0"
            ),
        )

    async def _prepare_capabilities(self, environment: BaseEnvironment) -> dict[str, str]:
        """Upload host-local skill/config paths and return container env overrides."""
        result: dict[str, str] = {}

        skills = os.environ.get("CORECC_SKILLS")
        if skills:
            prepared = []
            for idx, raw_path in enumerate(self._split_host_path_list(skills)):
                source = Path(raw_path).expanduser()
                if source.exists():
                    if source.is_file() and source.name == "SKILL.md":
                        source = source.parent
                    remote = f"{self._CAPABILITY_DIR}/skills/{idx}-{self._safe_remote_name(source.name)}"
                    await self._upload_directory(environment, source, remote)
                    prepared.append(remote)
                else:
                    prepared.append(raw_path)
            if prepared:
                result["CORECC_SKILLS"] = ":".join(prepared)

        mcp_config = os.environ.get("CORECC_MCP_CONFIG")
        if mcp_config:
            source = Path(mcp_config).expanduser()
            if source.exists() and source.is_file():
                remote = f"{self._CAPABILITY_DIR}/mcp/{self._safe_remote_name(source.name)}"
                await self.exec_as_root(
                    environment,
                    command=f"mkdir -p {shlex.quote(remote.rsplit('/', 1)[0])}",
                )
                await environment.upload_file(
                    source_path=source,
                    target_path=remote,
                )
                result["CORECC_MCP_CONFIG"] = remote
            else:
                result["CORECC_MCP_CONFIG"] = mcp_config

        timeout = os.environ.get("CORECC_MCP_TIMEOUT_SEC")
        if timeout:
            result["CORECC_MCP_TIMEOUT_SEC"] = timeout

        return result

    async def _upload_directory(
        self,
        environment: BaseEnvironment,
        source: Path,
        remote: str,
    ) -> None:
        if not source.is_dir():
            return

        await self.exec_as_root(
            environment,
            command=f"mkdir -p {shlex.quote(remote)}",
        )
        for child in source.rglob("*"):
            rel = child.relative_to(source).as_posix()
            target = f"{remote}/{rel}"
            if child.is_dir():
                await self.exec_as_root(
                    environment,
                    command=f"mkdir -p {shlex.quote(target)}",
                )
            else:
                await self.exec_as_root(
                    environment,
                    command=f"mkdir -p {shlex.quote(target.rsplit('/', 1)[0])}",
                )
                await environment.upload_file(
                    source_path=child,
                    target_path=target,
                )

    @staticmethod
    def _split_host_path_list(value: str) -> list[str]:
        sep = ";" if ";" in value else os.pathsep
        return [part.strip() for part in value.split(sep) if part.strip()]

    @staticmethod
    def _safe_remote_name(value: str) -> str:
        cleaned = "".join(ch if ch.isalnum() or ch in "._-" else "_" for ch in value)
        return cleaned or "capability"
