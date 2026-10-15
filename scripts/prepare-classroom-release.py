#!/usr/bin/env python3
"""将已经构建完成的课堂产物打包为 Linux 发布归档，不构建、不安装、不部署。

完整包在归档根目录提供 app.jar；--overlay 只省略该 JAR，其他校验相同。
归档没有额外的 release-id 顶层目录，应解压到独立 releases/<release-id>/。
示例：py -3.13 scripts/prepare-classroom-release.py --release-id 20261015-educationagent
"""

from __future__ import annotations

import argparse
import gzip
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import tarfile
from typing import Iterator
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
RELEASE_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}\Z")
PACKAGES = ("dsl", "generation", "renderer", "editor")
ASSET_DIRECTORIES = ("templates", "snippets", "prompts-pbl")
SKIP_DIRECTORIES = {"node_modules", ".git", ".svn", "__pycache__", ".cache"}
PRIVATE_DIRECTORIES = {"data", "logs", "uploads", "backups", "secrets", "private"}
PRIVATE_SUFFIXES = {".pem", ".key", ".p12", ".pfx", ".keystore", ".jks", ".db", ".sqlite", ".sqlite3"}
USER_DOCUMENT_SUFFIXES = {".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".pdf", ".csv", ".zip", ".7z"}
REQUIRED_FILES = (
    "package.json", "package-lock.json",
    "desktop-app/dist/index.html",
    "desktop-app/dist/educationagent-export/player.js",
    "desktop-app/dist/educationagent-export/player.css",
    "course-engine/package.json",
    "course-engine/dist/worker.js", "course-engine/dist/engine.js", "course-engine/dist/content-policy.js",
    "course-engine/dist/runtime/three.js", "course-engine/dist/runtime/code-runner.js",
    "deploy/linux/install-release.sh", "deploy/linux/educationagent.service", "deploy/linux/Caddyfile",
    "deploy/linux/runtime-config.py",
)


class ReleaseError(Exception):
    """发布输入不完整或越过打包边界。"""


def validate_release_id(value: str) -> str:
    """与 install-release.sh 的 ID 规则一致，另拒绝 Windows 设备名。"""
    if not RELEASE_ID.fullmatch(value):
        raise argparse.ArgumentTypeError("release-id 须为 1–80 个字母、数字、点、下划线或短横线，首字符须为字母或数字")
    first_part = value.split(".", 1)[0].upper()
    if first_part in {"CON", "PRN", "AUX", "NUL", *(f"COM{i}" for i in range(1, 10)), *(f"LPT{i}" for i in range(1, 10))}:
        raise argparse.ArgumentTypeError("release-id 不能使用 Windows 保留设备名")
    return value


def safe_relative(value: str) -> PurePosixPath:
    path = PurePosixPath(value)
    if path.is_absolute() or not path.parts or any(part in {"", ".", ".."} for part in path.parts) or "\\" in value or ":" in value:
        raise ReleaseError(f"无效发布路径：{value}")
    return path


def reject_links(path: Path) -> None:
    """允许当前工作区的普通硬链接文件，但不沿符号链接或 Windows junction 读取外部内容。"""
    try:
        relative = path.relative_to(ROOT)
    except ValueError as error:
        raise ReleaseError("发布输入不能位于项目目录之外") from error
    current = ROOT
    for part in relative.parts:
        current = current / part
        if current.is_symlink() or (hasattr(current, "is_junction") and current.is_junction()):
            raise ReleaseError(f"发布输入含符号链接或目录联接：{relative.as_posix()}")


def require_file(relative: str) -> Path:
    path = ROOT.joinpath(*safe_relative(relative).parts)
    reject_links(path)
    if not path.is_file() or path.stat().st_size == 0:
        raise ReleaseError(f"缺少必要产物或文件为空：{relative}；请先完成相应构建")
    return path


def private_file(relative: PurePosixPath) -> bool:
    """仅基于路径拒绝秘密和原始资料，不打开秘密文件做内容扫描。"""
    lower = relative.name.lower()
    # 公开示例是部署契约的一部分；实际 .env/runtime.env/server.env 永不发布。
    if lower == "server.env.example" and relative.parent == PurePosixPath("deploy/linux"):
        return False
    return (
        any(part.lower() in PRIVATE_DIRECTORIES for part in relative.parts)
        or lower.startswith(".env")
        or lower in {"runtime.env", "server.env", "local-secrets.properties", "credentials", "credentials.json"}
        or lower.startswith(("id_rsa", "id_ed25519", "credentials.", "credentials-"))
        or relative.suffix.lower() in PRIVATE_SUFFIXES | USER_DOCUMENT_SUFFIXES
    )


def tree_files(relative: str, *, deployment: bool = False) -> Iterator[tuple[Path, str]]:
    directory = ROOT.joinpath(*safe_relative(relative).parts)
    reject_links(directory)
    if not directory.is_dir():
        raise ReleaseError(f"缺少必要产物目录：{relative}")
    found = False
    stack = [directory]
    while stack:
        current = stack.pop()
        for path in sorted(current.iterdir(), key=lambda candidate: candidate.name):
            if path.name.lower() in SKIP_DIRECTORIES:
                continue
            reject_links(path)
            archive = PurePosixPath(path.relative_to(ROOT).as_posix())
            if private_file(archive):
                raise ReleaseError(f"允许打包的产物目录中出现私密文件或原始资料，已拒绝：{archive}")
            if path.is_dir():
                stack.append(path)
            elif path.is_file():
                # 发布部署脚本，不把日后放在这个目录的临时笔记、配置或用户附件顺带带走。
                if deployment and path.name not in {"Caddyfile", "server.env.example"} and path.suffix not in {".sh", ".py", ".service", ".conf"}:
                    continue
                found = True
                yield path, archive.as_posix()
            else:
                raise ReleaseError(f"发布产物不是普通文件：{archive}")
    if not found:
        raise ReleaseError(f"产物目录为空：{relative}")


def load_manifest(relative: str) -> dict:
    try:
        value = json.loads(require_file(relative).read_text(encoding="utf-8-sig"))
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ReleaseError(f"包清单不是有效 JSON：{relative}") from error
    if not isinstance(value, dict):
        raise ReleaseError(f"包清单必须为 JSON 对象：{relative}")
    return value


def runtime_exports(value: object) -> Iterator[str]:
    if isinstance(value, str) and value.startswith("./"):
        yield value[2:]
    elif isinstance(value, dict):
        for name, target in value.items():
            if name != "types":
                yield from runtime_exports(target)
    elif isinstance(value, list):
        for target in value:
            yield from runtime_exports(target)


def package_artifacts(name: str) -> Iterator[tuple[Path, str]]:
    base = f"vendor/educationagent/{name}"
    manifest = load_manifest(f"{base}/package.json")
    if manifest.get("name") != f"@educationagent/{name}":
        raise ReleaseError(f"工作区包名与发布目录不一致：{base}")
    yield require_file(f"{base}/package.json"), f"{base}/package.json"
    yield from tree_files(f"{base}/dist")
    targets = list(runtime_exports(manifest.get("exports", {})))
    for field in ("main", "module"):
        if isinstance(manifest.get(field), str):
            targets.append(manifest[field].removeprefix("./"))
    for target in set(targets):
        safe_relative(target)
        if "*" in target:
            matches = list((ROOT / base).glob(target))
            if not matches or not any(path.is_file() for path in matches):
                raise ReleaseError(f"包的运行时导出缺失：{base}/{target}")
            for path in matches:
                reject_links(path)
        else:
            path = require_file(f"{base}/{target}")
            # renderer 的 styles.css 不在 dist 内，但它是公开导出，必须跟随发布。
            if not target.startswith("dist/"):
                if target != "styles.css":
                    raise ReleaseError(f"出现未列入发布白名单的包导出：{base}/{target}")
                yield path, f"{base}/{target}"
    declared = manifest.get("files", [])
    for asset in ASSET_DIRECTORIES:
        directory = ROOT / base / asset
        if directory.exists() or asset in declared:
            yield from tree_files(f"{base}/{asset}")


def application_jar() -> Path:
    """精确选择当前 Maven 项目的产物，不把 target 下的旧基线或测试 JAR 当作新版。"""
    try:
        project = ET.parse(require_file("pom.xml")).getroot()
        namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
        artifact = project.findtext("m:artifactId", namespaces=namespace)
        version = project.findtext("m:version", namespaces=namespace)
    except ET.ParseError as error:
        raise ReleaseError("pom.xml 无法解析") from error
    if not artifact or not version or not re.fullmatch(r"[A-Za-z0-9._-]+", artifact + version):
        raise ReleaseError("无法从 pom.xml 确定唯一 JAR 文件名")
    path = require_file(f"target/{artifact}-{version}.jar")
    try:
        with zipfile.ZipFile(path) as jar:
            names = set(jar.namelist())
            if "BOOT-INF/classes/com/demo/DemoApplication.class" not in names or not any(name.startswith("BOOT-INF/lib/") for name in names):
                raise ReleaseError("后端 JAR 不是已完成 Spring Boot repackage 的可运行产物")
            # 只检查 ZIP 条目名，不读取 JAR 中任何配置正文。
            for name in names:
                if name.startswith("BOOT-INF/classes/"):
                    relative = PurePosixPath(name.removeprefix("BOOT-INF/classes/"))
                    if relative.name and private_file(relative):
                        raise ReleaseError("后端 JAR 意外包含私密文件或原始资料，请检查构建资源白名单")
    except zipfile.BadZipFile as error:
        raise ReleaseError("后端 JAR 已损坏或打包尚未完成") from error
    return path


def collect_files(overlay: bool) -> dict[str, Path]:
    for relative in REQUIRED_FILES:
        require_file(relative)
    root_manifest = load_manifest("package.json")
    lock = load_manifest("package-lock.json")
    if not root_manifest.get("workspaces") or not isinstance(lock.get("packages"), dict):
        raise ReleaseError("缺少 npm 工作区声明或可供 npm ci 使用的 package-lock.json")
    files: dict[str, Path] = {
        "package.json": ROOT / "package.json", "package-lock.json": ROOT / "package-lock.json",
        "course-engine/package.json": ROOT / "course-engine/package.json",
    }
    if not overlay:
        files["app.jar"] = application_jar()
    for tree in ("desktop-app/dist", "course-engine/dist"):
        files.update((archive, source) for source, archive in tree_files(tree))
    files.update((archive, source) for source, archive in tree_files("deploy/linux", deployment=True))
    for name in PACKAGES:
        files.update((archive, source) for source, archive in package_artifacts(name))
    for archive in files:
        path = safe_relative(archive)
        if "node_modules" in path.parts or private_file(path):
            raise ReleaseError(f"发布清单越过打包白名单：{archive}")
    return dict(sorted(files.items()))


def write_archive(output: Path, files: dict[str, Path]) -> None:
    """以普通文件写入归档，不复制 Windows 硬链接元数据；存在的发布包绝不覆盖。"""
    output.parent.mkdir(parents=True, exist_ok=True)
    created = False
    try:
        with output.open("xb") as raw:
            created = True
            with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as archive:
                    for name, source in files.items():
                        reject_links(source)
                        with source.open("rb") as content:
                            before = os.fstat(content.fileno())
                            if not stat.S_ISREG(before.st_mode):
                                raise ReleaseError(f"发布输入不是普通文件：{name}")
                            entry = tarfile.TarInfo(name)
                            entry.size = before.st_size
                            entry.mode = 0o755 if name.startswith("deploy/linux/") and source.suffix in {".sh", ".py"} else 0o644
                            entry.uid = entry.gid = entry.mtime = 0
                            entry.uname = entry.gname = ""
                            archive.addfile(entry, content)
                            after = os.fstat(content.fileno())
                            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                                raise ReleaseError(f"打包期间产物发生变化，请等待构建结束后重试：{name}")
    except BaseException:
        # 只清理本次以 xb 新建的未完成包，不删除或覆盖已有发布包。
        if created:
            output.unlink(missing_ok=True)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--release-id", required=True, type=validate_release_id, help="发布标识，规则与 Linux 安装脚本一致")
    parser.add_argument("--output", type=Path, help="归档路径；默认项目 target/<release-id>.tar.gz；已有文件会拒绝覆盖")
    parser.add_argument("--overlay", action="store_true", help="生成覆盖包，不包含 app.jar；其他运行产物仍完整校验")
    args = parser.parse_args(argv)
    output = args.output if args.output is not None else ROOT / "target" / f"{args.release_id}.tar.gz"
    output = output.absolute()
    if not output.name.endswith(".tar.gz"):
        parser.error("--output 必须以 .tar.gz 结尾")
    try:
        if output.exists() or output.is_symlink():
            raise ReleaseError("输出文件已存在，请选择新的 release-id 或 output；不会覆盖已有发布包")
        files = collect_files(args.overlay)
        for tree in (ROOT / "desktop-app/dist", ROOT / "course-engine/dist", ROOT / "deploy/linux", ROOT / "vendor/educationagent"):
            if output.resolve().is_relative_to(tree.resolve()):
                raise ReleaseError("输出包不能放入待发布的输入目录")
        write_archive(output, files)
        print(json.dumps({"release_id": args.release_id, "mode": "overlay" if args.overlay else "full",
                          "archive": str(output), "files": len(files), "bytes": output.stat().st_size,
                          "includes_jar": not args.overlay}, ensure_ascii=True))
        return 0
    except (OSError, ReleaseError, ValueError) as error:
        print(f"发布包未生成：{error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
