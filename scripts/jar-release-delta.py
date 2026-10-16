#!/usr/bin/env python3
"""Build/apply a byte-exact JAR delta with exclusive, directory-scoped outputs.

Only raw ZIP local records are reused. Recompressing an entry would change ZIP
bytes and therefore cannot provide the target JAR's complete SHA-256 identity.
This tool never extracts ZIP entries or overwrites an existing output file.
Build is release-specific. Portable apply requires an explicit --work-dir.
Python 3.9+ is required; no third-party dependencies are used.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import sys
from typing import BinaryIO
import zipfile
import zlib


WORKSPACE = Path(__file__).resolve().parent.parent
WORK_DIR = WORKSPACE / "target" / "xiaotao-upgrade-20261016" / "jar-delta"
TARGET_JAR = WORKSPACE / "target" / "NewBot_Agent-0.0.1-SNAPSHOT.jar"
REQUIRED_ENTRY = "BOOT-INF/classes/com/demo/edu/classroom/code/CodeJobService.class"
FORMAT = "jar-record-delta-v1"
CHUNK_SIZE = 1024 * 1024
MAX_JAR_BYTES = 1024 * 1024 * 1024
MAX_UNCOMPRESSED_BYTES = 4 * MAX_JAR_BYTES
MAX_REQUIRED_CLASS_BYTES = 16 * 1024 * 1024
MAX_MANIFEST_BYTES = 4 * 1024 * 1024
MAX_ENTRIES = 100_000
MAX_OPERATIONS = 200_000
HEX_SHA256 = re.compile(r"[0-9a-f]{64}\Z")
ADD_NAME = re.compile(r"data/[0-9]{8}\.bin\Z")


class DeltaError(ValueError):
    """A source, manifest, ZIP boundary, or requested path is unsafe/invalid."""


@dataclass(frozen=True)
class Record:
    name: str
    offset: int
    length: int


@dataclass(frozen=True)
class Layout:
    records: tuple[Record, ...]
    prefix_length: int
    central_offset: int
    size: int


def require(condition: bool, message: str) -> None:
    if not condition:
        raise DeltaError(message)


def reject_linked_components(path: Path, anchor: Path = WORKSPACE) -> None:
    """禁止 junction/symlink 让表面位于工作目录的写入越过实际目录边界。"""
    require(path.is_relative_to(anchor), "Path is outside its permitted anchor")
    current = anchor
    for part in path.relative_to(anchor).parts:
        # ADS/设备名绕过普通文件的独占语义；portable 模式同样拒绝这些歧义名字。
        require(not any(ord(char) < 32 or char in ':*?"<>|' for char in part), "Special path component is not allowed")
        basename = part.split(".", 1)[0].rstrip(" ").upper()
        reserved = basename in {"CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$", "CLOCK$"}
        reserved |= re.fullmatch(r"(?:COM|LPT)[1-9¹²³]", basename) is not None
        require(not part.endswith((".", " ")) and not reserved,
                f"Reserved/ambiguous path component: {part!r}")
        current /= part
        if current.exists() or current.is_symlink():
            require(not current.is_symlink(), f"Symbolic link is not allowed: {current}")
            require(not getattr(current, "is_junction", lambda: False)(), f"Junction is not allowed: {current}")


def work_path(value: str, suffix: str, *, existing: bool, root: Path = WORK_DIR,
              anchor: Path = WORKSPACE) -> Path:
    """所有可写产物和基线只允许落在本轮独占目录，避免成为通用文件写入器。"""
    require(".." not in Path(value).parts, "Parent-directory traversal is not allowed")
    lexical = Path(os.path.abspath(value))
    require(lexical.is_relative_to(root), f"Path must be inside {root}")
    reject_linked_components(lexical, anchor)
    resolved = lexical.resolve(strict=existing)
    require(resolved.is_relative_to(root), "Resolved path leaves the release work directory")
    require(resolved.suffix.lower() == suffix, f"Path must end in {suffix}")
    if existing:
        require(resolved.is_file(), f"Input must be a regular file: {resolved}")
    else:
        require(not resolved.exists(), f"Refusing to overwrite output: {resolved}")
    return resolved


def target_path(value: str) -> Path:
    lexical = Path(os.path.abspath(value))
    require(lexical == TARGET_JAR, f"New JAR must be the fixed release target: {TARGET_JAR}")
    reject_linked_components(lexical)
    resolved = lexical.resolve(strict=True)
    require(resolved == TARGET_JAR and resolved.is_file(), "Invalid target JAR path")
    return resolved


def portable_work_dir(value: str) -> Path:
    """便携 apply 的写入授权由 operator 显式给定绝对目录，绝不隐式扩大。"""
    path = Path(value)
    require(path.is_absolute() and ".." not in path.parts, "--work-dir must be an absolute path without '..'")
    require(path != Path(path.anchor), "--work-dir cannot be a filesystem root")
    reject_linked_components(path, Path(path.anchor))
    resolved = path.resolve(strict=True)
    require(resolved.is_dir(), "--work-dir must already exist and be a directory")
    return resolved


def portable_base_path(value: str) -> Path:
    path = Path(value)
    require(path.is_absolute() and ".." not in path.parts, "Portable --old must be an explicit absolute path")
    resolved = path.resolve(strict=True)
    require(resolved.is_file() and resolved.suffix.lower() == ".jar", "Portable base must be a regular .jar file")
    return resolved


def prepare_parent(path: Path, root: Path = WORK_DIR) -> None:
    require(path.is_relative_to(root), "Output leaves the selected work directory")
    anchor = Path(root.anchor)
    reject_linked_components(path.parent, anchor)
    path.parent.mkdir(parents=True, exist_ok=True)
    reject_linked_components(path.parent, anchor)


def remove_owned_output(path: Path, identity: tuple[int, int] | None) -> None:
    """失败只清理本次创建的文件；路径被其他任务替换后不再删除。"""
    if identity is None:
        return
    try:
        stat = path.lstat()
    except FileNotFoundError:
        return
    if (stat.st_dev, stat.st_ino) == identity:
        path.unlink()


def safe_zip_name(name: str) -> None:
    require(isinstance(name, str) and bool(name), "Empty or invalid ZIP entry name")
    require("\x00" not in name and "\\" not in name and ":" not in name,
            f"Unsafe ZIP entry name: {name!r}")
    require(not name.startswith("/"), f"Absolute ZIP entry name: {name!r}")
    parts = name.removesuffix("/").split("/")
    require(all(part not in ("", ".", "..") for part in parts),
            f"Traversal/ambiguous ZIP entry name: {name!r}")


def snapshot(fp: BinaryIO) -> tuple[int, int, int, int]:
    st = os.fstat(fp.fileno())
    return st.st_dev, st.st_ino, st.st_size, st.st_mtime_ns


def copy_region(source: BinaryIO, offset: int, length: int, destination: BinaryIO | None = None) -> str:
    """按有界区间流式读写，截断输入立即失败，不把大 JAR 整体装入内存。"""
    source.seek(offset)
    digest = hashlib.sha256()
    remaining = length
    while remaining:
        block = source.read(min(CHUNK_SIZE, remaining))
        require(bool(block), "Unexpected end of source while reading a byte region")
        digest.update(block)
        if destination is not None:
            destination.write(block)
        remaining -= len(block)
    return digest.hexdigest()


def same_region(a: BinaryIO, a_offset: int, b: BinaryIO, b_offset: int, length: int) -> bool:
    # 哈希只负责筛选候选；真实字节比较保证 COPY 不依赖碰撞假设。
    a.seek(a_offset)
    b.seek(b_offset)
    remaining = length
    while remaining:
        amount = min(CHUNK_SIZE, remaining)
        block_a, block_b = a.read(amount), b.read(amount)
        if len(block_a) != amount or len(block_b) != amount or block_a != block_b:
            return False
        remaining -= amount
    return True


def jar_layout(fp: BinaryIO, *, require_code_job: bool) -> Layout:
    """根据 central directory 给出的 offset 切出完整原始 local record。"""
    size = os.fstat(fp.fileno()).st_size
    require(0 < size <= MAX_JAR_BYTES, "JAR size is outside the supported 1 GiB bound")
    with zipfile.ZipFile(fp, "r") as jar:
        entries = jar.infolist()
        require(0 < len(entries) <= MAX_ENTRIES, "JAR entry count is invalid")
        names: set[str] = set()
        uncompressed_bytes = 0
        for entry in entries:
            # orig_filename 保留 NUL；filename 会被 zipfile 静默截断，不能据此做安全检查。
            safe_zip_name(entry.orig_filename)
            require(entry.orig_filename == entry.filename, "ZIP filename was normalized by zipfile")
            require(entry.filename not in names, f"Duplicate JAR entry: {entry.filename}")
            names.add(entry.filename)
            require(not (entry.flag_bits & 1), "Encrypted ZIP entries are not supported")
            require(0 <= entry.file_size <= MAX_JAR_BYTES, "ZIP member expands beyond the supported bound")
            uncompressed_bytes += entry.file_size
            require(uncompressed_bytes <= MAX_UNCOMPRESSED_BYTES, "ZIP expansion exceeds the supported 4 GiB bound")
        if require_code_job:
            require(REQUIRED_ENTRY in names, f"Required class is absent: {REQUIRED_ENTRY}")
            require(jar.getinfo(REQUIRED_ENTRY).file_size <= MAX_REQUIRED_CLASS_BYTES, "Required class is too large")
        central = jar.start_dir
        require(type(central) is int and 0 < central < size, "Invalid ZIP central directory offset")
        ordered = sorted(entries, key=lambda entry: entry.header_offset)
        offsets = [entry.header_offset for entry in ordered]
        require(len(set(offsets)) == len(offsets), "Overlapping local-record starts")
        records: list[Record] = []
        for index, entry in enumerate(ordered):
            start = entry.header_offset
            end = offsets[index + 1] if index + 1 < len(offsets) else central
            require(type(start) is int and 0 <= start < end <= central, "Invalid local-record bounds")
            require(end - start >= 30, "Truncated ZIP local header")
            fp.seek(start)
            header = fp.read(30)
            require(len(header) == 30, "Truncated ZIP local header")
            fields = struct.unpack("<4s5H3I2H", header)
            require(fields[0] == b"PK\x03\x04", "Missing ZIP local-file signature")
            flags, compression, name_length, extra_length = fields[2], fields[3], fields[9], fields[10]
            require(flags == entry.flag_bits and compression == entry.compress_type,
                    "Local and central ZIP header metadata disagree")
            payload_start = start + 30 + name_length + extra_length
            require(payload_start + entry.compress_size <= end, "Compressed data overlaps the next record")
            raw_name = fp.read(name_length)
            local_name = raw_name.decode("utf-8" if flags & 0x800 else "cp437")
            require(local_name == entry.orig_filename, "Local and central ZIP filenames disagree")
            extra = fp.read(extra_length)
            local_compressed, local_expanded = fields[7], fields[8]
            zip64 = local_compressed == 0xFFFFFFFF or local_expanded == 0xFFFFFFFF
            if zip64:
                # ZIP64 local extra 依次仅存放标记为 0xffffffff 的尺寸字段。
                cursor = 0
                zip64_sizes = None
                while cursor + 4 <= len(extra):
                    field_id, field_size = struct.unpack_from("<HH", extra, cursor)
                    cursor += 4
                    require(cursor + field_size <= len(extra), "Truncated local ZIP extra field")
                    if field_id == 1:
                        zip64_sizes = extra[cursor:cursor + field_size]
                        break
                    cursor += field_size
                require(zip64_sizes is not None, "ZIP64 local size extra field is absent")
                cursor = 0
                if local_expanded == 0xFFFFFFFF:
                    require(cursor + 8 <= len(zip64_sizes), "Truncated ZIP64 expanded size")
                    local_expanded = struct.unpack_from("<Q", zip64_sizes, cursor)[0]
                    cursor += 8
                if local_compressed == 0xFFFFFFFF:
                    require(cursor + 8 <= len(zip64_sizes), "Truncated ZIP64 compressed size")
                    local_compressed = struct.unpack_from("<Q", zip64_sizes, cursor)[0]
            if flags & 8:
                descriptor_start = payload_start + entry.compress_size
                descriptor_size = 20 if zip64 else 12
                require(descriptor_start + descriptor_size <= end, "Truncated ZIP data descriptor")
                fp.seek(descriptor_start)
                first_word = fp.read(4)
                starts = [descriptor_start]
                if first_word == b"PK\x07\x08":
                    starts.append(descriptor_start + 4)
                valid_descriptor = False
                # CRC 本身可能等于可选 signature，两个解释都试一次避免拒绝合法记录。
                for candidate in starts:
                    if candidate + descriptor_size <= end:
                        fp.seek(candidate)
                        descriptor = fp.read(descriptor_size)
                        values = struct.unpack("<LQQ" if zip64 else "<LLL", descriptor)
                        valid_descriptor |= values == (entry.CRC, entry.compress_size, entry.file_size)
                require(valid_descriptor, "ZIP data descriptor disagrees with central directory")
            else:
                require((fields[6], local_compressed, local_expanded) == (entry.CRC, entry.compress_size, entry.file_size),
                        "Local ZIP sizes/CRC disagree with central directory")
            records.append(Record(entry.filename, start, end - start))
    return Layout(tuple(records), offsets[0], central, size)


def append_segment(segments: list[dict], kind: str, offset: int, length: int, role: str) -> None:
    if not length:
        return
    if segments and segments[-1]["kind"] == kind and segments[-1]["role"] == role:
        previous = segments[-1]
        if previous["offset"] + previous["length"] == offset:
            previous["length"] += length
            return
    segments.append({"kind": kind, "offset": offset, "length": length, "role": role})


def required_class_digest(jar: zipfile.ZipFile) -> str:
    # 即使 central directory 谎报 file_size，read(n) 仍限制每次 inflate 的内存。
    digest = hashlib.sha256()
    total = 0
    with jar.open(REQUIRED_ENTRY) as source:
        while block := source.read(CHUNK_SIZE):
            total += len(block)
            require(total <= MAX_REQUIRED_CLASS_BYTES, "Required class expands beyond its bound")
            digest.update(block)
    return digest.hexdigest()


def build_delta(old_path: Path, new_path: Path, delta_path: Path) -> dict:
    prepare_parent(delta_path)
    created_identity = None
    try:
        with old_path.open("rb") as old, new_path.open("rb") as new:
            old_snapshot, new_snapshot = snapshot(old), snapshot(new)
            old_layout = jar_layout(old, require_code_job=False)
            new_layout = jar_layout(new, require_code_job=True)
            old_hash = copy_region(old, 0, old_layout.size)
            new_hash = copy_region(new, 0, new_layout.size)
            candidates: dict[tuple[int, str], list[Record]] = defaultdict(list)
            for record in old_layout.records:
                candidates[(record.length, copy_region(old, record.offset, record.length))].append(record)
            segments: list[dict] = []
            append_segment(segments, "ADD", 0, new_layout.prefix_length, "prefix")
            reused_records = reused_bytes = 0
            for record in new_layout.records:
                digest = copy_region(new, record.offset, record.length)
                matched = next((candidate for candidate in candidates.get((record.length, digest), [])
                                if same_region(old, candidate.offset, new, record.offset, record.length)), None)
                if matched is not None:
                    append_segment(segments, "COPY", matched.offset, record.length, "records")
                    reused_records += 1
                    reused_bytes += record.length
                else:
                    append_segment(segments, "ADD", record.offset, record.length, "records")
            append_segment(segments, "ADD", new_layout.central_offset,
                           new_layout.size - new_layout.central_offset, "central_directory")
            require(len(segments) <= MAX_OPERATIONS, "Too many delta operations")
            with zipfile.ZipFile(new_path) as jar:
                required_digest = required_class_digest(jar)
            manifest = {
                "format": FORMAT,
                "base": {"sha256": old_hash, "size": old_layout.size},
                "target": {"sha256": new_hash, "size": new_layout.size},
                "required_entries": [{"name": REQUIRED_ENTRY, "sha256": required_digest}],
                "operations": [],
            }
            # x 模式保证已存在的 delta 不会被覆盖；仅清理本次成功创建后失败的文件。
            with zipfile.ZipFile(delta_path, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as delta:
                created_identity = snapshot(delta.fp)[:2]
                add_index = 0
                for segment in segments:
                    if segment["kind"] == "COPY":
                        manifest["operations"].append({"kind": "COPY", "offset": segment["offset"],
                                                       "length": segment["length"]})
                    else:
                        entry_name = f"data/{add_index:08d}.bin"
                        with delta.open(entry_name, "w") as destination:
                            digest = copy_region(new, segment["offset"], segment["length"], destination)
                        manifest["operations"].append({"kind": "ADD", "entry": entry_name,
                                                       "length": segment["length"], "sha256": digest})
                        add_index += 1
                encoded = json.dumps(manifest, ensure_ascii=True, separators=(",", ":")).encode("utf-8")
                require(len(encoded) <= MAX_MANIFEST_BYTES, "Manifest is too large")
                delta.writestr("manifest.json", encoded)
            require(snapshot(old) == old_snapshot and copy_region(old, 0, old_layout.size) == old_hash,
                    "Base JAR changed while building; discard this delta")
            require(snapshot(new) == new_snapshot and copy_region(new, 0, new_layout.size) == new_hash,
                    "Target JAR changed while building; rebuild after the JAR is stable")
        return {"action": "build", "delta": str(delta_path), "delta_bytes": delta_path.stat().st_size,
                "old_sha256": old_hash, "new_sha256": new_hash, "new_bytes": new_layout.size,
                "records": len(new_layout.records), "reused_records": reused_records,
                "changed_records": len(new_layout.records) - reused_records,
                "reused_bytes": reused_bytes, "added_bytes": new_layout.size - reused_bytes,
                "operations": len(segments)}
    except BaseException:
        remove_owned_output(delta_path, created_identity)
        raise


def exact_keys(value: object, keys: set[str], label: str) -> dict:
    require(type(value) is dict and set(value) == keys, f"Invalid {label} fields")
    return value


def integer(value: object, label: str, *, positive: bool = False) -> int:
    require(type(value) is int and (1 if positive else 0) <= value <= MAX_JAR_BYTES,
            f"Invalid {label}")
    return value


def sha256_text(value: object) -> str:
    require(type(value) is str and HEX_SHA256.fullmatch(value) is not None, "Invalid SHA-256 value")
    return value


def unique_object(pairs: list[tuple[str, object]]) -> dict:
    result: dict = {}
    for key, value in pairs:
        require(key not in result, f"Duplicate JSON field: {key}")
        result[key] = value
    return result


def read_manifest(delta: zipfile.ZipFile) -> dict:
    infos = delta.infolist()
    require(0 < len(infos) <= MAX_OPERATIONS + 1, "Invalid delta entry count")
    entries: dict[str, zipfile.ZipInfo] = {}
    for info in infos:
        safe_zip_name(info.orig_filename)
        require(info.orig_filename == info.filename and info.filename not in entries,
                "Duplicate/normalized delta ZIP entry")
        require(info.filename == "manifest.json" or ADD_NAME.fullmatch(info.filename) is not None,
                f"Unexpected delta ZIP entry: {info.filename!r}")
        require(not (info.flag_bits & 1) and info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
                "Unsupported delta ZIP compression/encryption")
        entries[info.filename] = info
    require("manifest.json" in entries, "Delta manifest is missing")
    require(0 < entries["manifest.json"].file_size <= MAX_MANIFEST_BYTES, "Invalid manifest size")
    # 不使用 read(-1)：恶意 central size 可掩盖巨大的 deflate 数据，导致一次性膨胀。
    with delta.open("manifest.json") as source:
        raw_manifest = source.read(MAX_MANIFEST_BYTES + 1)
        require(len(raw_manifest) <= MAX_MANIFEST_BYTES and source.read(1) == b"", "Manifest expands beyond its bound")
    manifest = json.loads(raw_manifest, object_pairs_hook=unique_object)
    exact_keys(manifest, {"format", "base", "target", "required_entries", "operations"}, "manifest")
    require(manifest["format"] == FORMAT, "Unsupported delta format")
    for field in ("base", "target"):
        item = exact_keys(manifest[field], {"size", "sha256"}, field)
        integer(item["size"], f"{field} size", positive=True)
        sha256_text(item["sha256"])
    required = manifest["required_entries"]
    require(type(required) is list and len(required) == 1, "Invalid required_entries")
    exact_keys(required[0], {"name", "sha256"}, "required entry")
    require(required[0]["name"] == REQUIRED_ENTRY, "Unexpected required class")
    sha256_text(required[0]["sha256"])
    operations = manifest["operations"]
    require(type(operations) is list and 0 < len(operations) <= MAX_OPERATIONS, "Invalid operation count")
    expected_entries = {"manifest.json"}
    total_length = 0
    for operation in operations:
        require(type(operation) is dict, "Invalid delta operation")
        kind = operation.get("kind")
        if kind == "COPY":
            exact_keys(operation, {"kind", "offset", "length"}, "COPY operation")
            offset = integer(operation["offset"], "COPY offset")
            length = integer(operation["length"], "COPY length", positive=True)
            require(offset + length <= manifest["base"]["size"], "COPY goes beyond base JAR")
        elif kind == "ADD":
            exact_keys(operation, {"kind", "entry", "length", "sha256"}, "ADD operation")
            name = operation["entry"]
            require(type(name) is str and ADD_NAME.fullmatch(name) is not None, "Invalid ADD entry name")
            require(name not in expected_entries, "ADD entry may only be consumed once")
            expected_entries.add(name)
            require(name in entries, "ADD entry is missing")
            length = integer(operation["length"], "ADD length", positive=True)
            require(entries[name].file_size == length, "ADD entry length disagrees with manifest")
            sha256_text(operation["sha256"])
        else:
            raise DeltaError("Unknown delta operation")
        total_length += length
        require(total_length <= manifest["target"]["size"], "Operations exceed target JAR size")
    require(total_length == manifest["target"]["size"], "Operations do not cover target JAR size")
    require(set(entries) == expected_entries, "Delta contains unused ZIP entries")
    return manifest


def verify_rebuilt(path: Path, manifest: dict) -> None:
    with path.open("rb") as rebuilt:
        require(os.fstat(rebuilt.fileno()).st_size == manifest["target"]["size"], "Rebuilt size mismatch")
        require(copy_region(rebuilt, 0, manifest["target"]["size"]) == manifest["target"]["sha256"],
                "Rebuilt JAR SHA-256 does not match target")
        jar_layout(rebuilt, require_code_job=True)
    with zipfile.ZipFile(path) as jar:
        bad_entry = jar.testzip()
        require(bad_entry is None, f"Rebuilt ZIP CRC check failed: {bad_entry}")
        actual = required_class_digest(jar)
        require(actual == manifest["required_entries"][0]["sha256"], "Required class SHA-256 mismatch")


def apply_delta(old_path: Path, delta_path: Path, output_path: Path, *, work_root: Path = WORK_DIR) -> dict:
    require(output_path != old_path, "Output cannot replace the base JAR")
    prepare_parent(output_path, work_root)
    created_identity = None
    try:
        with old_path.open("rb") as old, zipfile.ZipFile(delta_path, "r") as delta:
            manifest = read_manifest(delta)
            original = snapshot(old)
            require(original[2] == manifest["base"]["size"], "Base JAR size mismatch")
            require(copy_region(old, 0, original[2]) == manifest["base"]["sha256"], "Base JAR SHA-256 mismatch")
            # 所有 manifest/输入校验先于创建输出；xb 防止并发任务的文件被覆盖。
            with output_path.open("xb") as output:
                created_identity = snapshot(output)[:2]
                for operation in manifest["operations"]:
                    if operation["kind"] == "COPY":
                        copy_region(old, operation["offset"], operation["length"], output)
                    else:
                        digest = hashlib.sha256()
                        remaining = operation["length"]
                        with delta.open(operation["entry"]) as source:
                            while remaining:
                                block = source.read(min(CHUNK_SIZE, remaining))
                                require(bool(block), "Truncated ADD data")
                                output.write(block)
                                digest.update(block)
                                remaining -= len(block)
                            require(source.read(1) == b"", "ADD data exceeds declared length")
                        require(digest.hexdigest() == operation["sha256"], "ADD data SHA-256 mismatch")
                output.flush()
                os.fsync(output.fileno())
            require(snapshot(old) == original and copy_region(old, 0, original[2]) == manifest["base"]["sha256"],
                    "Base JAR changed while applying delta")
            verify_rebuilt(output_path, manifest)
        return {"action": "apply", "output": str(output_path), "bytes": output_path.stat().st_size,
                "sha256": manifest["target"]["sha256"], "testzip": "PASS", "required_class": REQUIRED_ENTRY}
    except BaseException:
        remove_owned_output(output_path, created_identity)
        raise


def main(argv: list[str] | None = None) -> int:
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="backslashreplace")
    parser = argparse.ArgumentParser(description=__doc__, epilog=f"Local output/input boundary: {WORK_DIR}")
    subparsers = parser.add_subparsers(dest="command", required=True)
    build = subparsers.add_parser("build", help="Build a raw ZIP-record delta; refuses existing outputs")
    build.add_argument("--old", required=True, help="Extracted baseline .jar inside the release work directory")
    build.add_argument("--new", required=True, help=f"Must be {TARGET_JAR}")
    build.add_argument("--delta", required=True, help="New .zip inside the release work directory")
    apply = subparsers.add_parser("apply", help="Rebuild, hash, and test a JAR into a new exclusive file")
    apply.add_argument("--work-dir", help="Portable apply only: explicit absolute existing output/delta directory")
    apply.add_argument("--old", required=True, help="Baseline .jar; explicit absolute read-only path with --work-dir")
    apply.add_argument("--delta", required=True, help="Delta .zip inside the release work directory")
    apply.add_argument("--output", required=True, help="New .jar inside the release work directory")
    args = parser.parse_args(argv)
    try:
        if args.command == "build":
            old = work_path(args.old, ".jar", existing=True)
            result = build_delta(old, target_path(args.new), work_path(args.delta, ".zip", existing=False))
        else:
            if args.work_dir is None:
                old = work_path(args.old, ".jar", existing=True)
                scope, anchor = WORK_DIR, WORKSPACE
            else:
                scope = portable_work_dir(args.work_dir)
                anchor = Path(scope.anchor)
                old = portable_base_path(args.old)
            result = apply_delta(old, work_path(args.delta, ".zip", existing=True, root=scope, anchor=anchor),
                                 work_path(args.output, ".jar", existing=False, root=scope, anchor=anchor),
                                 work_root=scope)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0
    except (DeltaError, OSError, zipfile.BadZipFile, UnicodeError, json.JSONDecodeError,
            NotImplementedError, RuntimeError, zlib.error) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
