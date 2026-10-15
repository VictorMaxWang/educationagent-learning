"""本项目服务器维护入口：凭据只从忽略的 env 文件读取，脚本经 SSH stdin 执行。"""
from __future__ import annotations

import argparse
import base64
import hashlib
import os
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "target" / "deploy-tools"))
import paramiko


def settings(path: Path) -> dict[str, str]:
    result = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if line and not line.lstrip().startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


class RecordNewHost(paramiko.MissingHostKeyPolicy):
    def missing_host_key(self, client, hostname, key):
        fingerprint = base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip("=")
        print(f"First SSH host key: {hostname} {key.get_name()} SHA256:{fingerprint}", flush=True)
        client.get_host_keys().add(hostname, key.get_name(), key)
        client.save_host_keys(str(ROOT / "target" / "deploy-known_hosts"))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", type=Path, default=ROOT / "deploy" / ".env")
    parser.add_argument("--script", type=Path)
    parser.add_argument("--upload", nargs=2, metavar=("LOCAL", "REMOTE"))
    parser.add_argument("--download", nargs=2, metavar=("REMOTE", "LOCAL"))
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    config = settings(args.env)
    client = paramiko.SSHClient()
    known = ROOT / "target" / "deploy-known_hosts"
    if known.exists():
        client.load_host_keys(str(known))
    client.set_missing_host_key_policy(RecordNewHost())
    try:
        client.connect(config["DEPLOY_HOST"], username=config.get("DEPLOY_USER", "root"),
                       password=config["DEPLOY_PASSWORD"], look_for_keys=False, allow_agent=False,
                       timeout=15, auth_timeout=20, banner_timeout=20)
        client.get_transport().set_keepalive(30)
        if args.upload or args.download:
            with client.open_sftp() as sftp:
                if args.upload:
                    local, remote = args.upload
                    sftp.put(local, remote)
                    print(f"Uploaded {Path(local).name}: {sftp.stat(remote).st_size} bytes")
                else:
                    remote, local = args.download
                    sftp.get(remote, local)
                    print(f"Downloaded {Path(local).name}: {Path(local).stat().st_size} bytes")
            return 0
        script = args.script.read_text(encoding="utf-8-sig") if args.script else "set -eu\nid\nuname -sr\n"
        stdin, stdout, stderr = client.exec_command("bash -s", timeout=args.timeout)
        stdin.write(script)
        if not script.endswith("\n"):
            stdin.write("\n")
        stdin.flush()
        stdin.channel.shutdown_write()
        channel = stdout.channel
        deadline = time.monotonic() + args.timeout
        while True:
            if channel.recv_ready():
                sys.stdout.write(channel.recv(65536).decode("utf-8", "replace")); sys.stdout.flush()
            if channel.recv_stderr_ready():
                sys.stderr.write(channel.recv_stderr(65536).decode("utf-8", "replace")); sys.stderr.flush()
            if channel.exit_status_ready() and not channel.recv_ready() and not channel.recv_stderr_ready():
                return channel.recv_exit_status()
            if time.monotonic() > deadline:
                channel.close()
                print("Remote command timed out; inspect actual server state before retrying.", file=sys.stderr)
                return 124
            time.sleep(0.05)
    except paramiko.AuthenticationException:
        print("SSH authentication failed for the supplied deployment credential.", file=sys.stderr)
        return 77
    except paramiko.BadHostKeyException:
        print("SSH host key changed; connection rejected.", file=sys.stderr)
        return 78
    except (OSError, paramiko.SSHException) as error:
        print(f"SSH connection failed: {type(error).__name__}", file=sys.stderr)
        return 79
    finally:
        client.close()


if __name__ == "__main__":
    raise SystemExit(main())
