#!/usr/bin/env python3
"""读取本项目限定的 EnvironmentFile，不执行 shell 表达式、不打印凭据。"""
from __future__ import annotations

import grp
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import zipfile

ENV_FILE = Path('/etc/educationagent/runtime.env')


def read_values() -> dict[str, str]:
    info = ENV_FILE.stat()
    if info.st_uid != 0 or info.st_mode & 0o077:
        raise ValueError('runtime.env must be owned by root and have mode 0600')
    values: dict[str, str] = {}
    for line in ENV_FILE.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        key, sep, value = line.partition('=')
        if not sep or not re.fullmatch(r'[A-Z][A-Z0-9_]*', key):
            raise ValueError('Invalid runtime.env entry')
        # EnvironmentFile does not perform shell interpolation. Only simple quoted/unquoted values are accepted.
        if value.startswith(('"', "'")):
            parsed = shlex.split(value, comments=False)
            if len(parsed) != 1:
                raise ValueError('Invalid quoted runtime.env value')
            value = parsed[0]
        elif any(char.isspace() for char in value):
            raise ValueError('Unquoted runtime.env values cannot contain whitespace')
        if key in values:
            raise ValueError('Duplicate runtime.env key')
        values[key] = value
    return values


def secret(values: dict[str, str], key: str) -> str:
    value = values.get(key, '')
    if not value or value.startswith(('CHANGE_ME', 'YOUR_')) or any(ord(c) < 33 or ord(c) > 126 for c in value):
        raise ValueError(f'{key} must contain a configured printable ASCII value')
    return value


def write_config(path: str, text: str, mode: int = 0o644, group: str | None = None) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + '.next')
    # Open with restrictive permissions before writing; never expose a secret during replacement.
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    os.fchmod(fd, mode)
    if group:
        os.fchown(fd, 0, grp.getgrnam(group).gr_gid)
    with os.fdopen(fd, 'w', encoding='utf-8') as output:
        output.write(text)
    os.replace(temporary, target)


def provision(values: dict[str, str]) -> None:
    if values.get('SPRING_DATASOURCE_USERNAME') != 'educationagent':
        raise ValueError('Dedicated MySQL account must be educationagent')
    secret(values, 'AI_CHAT_API_KEY')
    secret(values, 'SPRING_DATASOURCE_PASSWORD')
    redis_password = secret(values, 'SPRING_DATA_REDIS_PASSWORD')
    domain = values.get('EDUCATIONAGENT_DOMAIN', 'smartchildcareagent.cn')
    if domain != 'smartchildcareagent.cn':
        raise ValueError('This deployment is bound to smartchildcareagent.cn')
    write_config('/etc/mysql/mysql.conf.d/educationagent.cnf', '''[mysqld]
bind-address=127.0.0.1
mysqlx-bind-address=127.0.0.1
skip-name-resolve
max_connections=40
innodb_buffer_pool_size=384M
max_allowed_packet=64M
character-set-server=utf8mb4
collation-server=utf8mb4_unicode_ci
''')
    # Redis's quoted configuration supports escaped double quotes and backslashes.
    redis_escaped = redis_password.replace('\\', '\\\\').replace('"', '\\"')
    write_config('/etc/redis/educationagent-auth.conf', f'''# Managed by educationagent deployment.
bind 127.0.0.1 ::1
protected-mode yes
requirepass "{redis_escaped}"
maxmemory 128mb
maxmemory-policy allkeys-lru
appendonly no
save ""
''', 0o640, 'redis')
    redis_main = Path('/etc/redis/redis.conf')
    include = 'include /etc/redis/educationagent-auth.conf'
    current = redis_main.read_text(encoding='utf-8')
    if include not in current.splitlines():
        with redis_main.open('a', encoding='utf-8') as output:
            output.write('\n# Dedicated local educationagent cache settings.\n' + include + '\n')
    write_config('/etc/caddy/educationagent.env', f'EDUCATIONAGENT_DOMAIN={domain}\n', 0o640, 'caddy')
    write_config('/etc/systemd/system/caddy.service.d/educationagent.conf', '[Service]\nEnvironmentFile=/etc/caddy/educationagent.env\n')


def production_schema(text: str) -> str:
    """只排除已识别的两类演示 INSERT；遇到新的数据写入语句必须先人工审查。"""
    output: list[str] = []
    skipping = False
    for line in text.splitlines():
        statement = line.strip()
        if not skipping and re.match(r'^INSERT\s+IGNORE\s+INTO\s+(edu_user_class|edu_class)\b', statement, re.I):
            skipping = True
        if skipping:
            if statement.endswith(';'):
                skipping = False
            continue
        if re.match(r'^(INSERT|REPLACE|UPDATE|DELETE|DROP|TRUNCATE|ALTER)\b', statement, re.I):
            raise ValueError('Unexpected data-changing statement in initial schema; review it first')
        output.append(line)
    if skipping or not any(re.match(r'^CREATE TABLE', line.strip(), re.I) for line in output):
        raise ValueError('Incomplete production schema')
    return '\n'.join(output) + '\n'


def database(values: dict[str, str]) -> None:
    # 结构来自即将发布的同版 JAR，不依赖服务器保留 Java 源码。
    jar = Path(__file__).resolve().parents[2] / 'app.jar'
    with zipfile.ZipFile(jar) as archive:
        schema = production_schema(archive.read('BOOT-INF/classes/schema.sql').decode('utf-8-sig'))
    password = secret(values, 'SPRING_DATASOURCE_PASSWORD').replace('\\', '\\\\').replace("'", "\\'")
    sql = 'CREATE DATABASE IF NOT EXISTS educationagent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;\n'
    for host in ('localhost', '127.0.0.1'):
        account = f"'educationagent'@'{host}'"
        sql += f"CREATE USER IF NOT EXISTS {account} IDENTIFIED BY '{password}';\n"
        sql += f"ALTER USER {account} IDENTIFIED BY '{password}';\n"
        sql += f'GRANT ALL PRIVILEGES ON educationagent.* TO {account};\n'
    sql += 'USE educationagent;\n' + schema
    # Passwords travel over stdin to the local socket, never through process arguments.
    subprocess.run(['mysql', '--protocol=socket', '--user=root'], input=sql, text=True, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def validate_caddy(values: dict[str, str]) -> None:
    environment = dict(os.environ)
    environment['EDUCATIONAGENT_DOMAIN'] = values.get('EDUCATIONAGENT_DOMAIN', 'smartchildcareagent.cn')
    environment.pop('EDUCATIONAGENT_BASIC_HASH', None)
    subprocess.run(['caddy', 'validate', '--config', '/etc/caddy/Caddyfile', '--adapter', 'caddyfile'], env=environment, check=True)


def main() -> None:
    if os.geteuid() != 0:
        raise ValueError('Run as root')
    actions = {'provision': provision, 'database': database, 'validate-caddy': validate_caddy}
    if len(sys.argv) != 2 or sys.argv[1] not in actions:
        raise ValueError('Expected provision, database, or validate-caddy')
    actions[sys.argv[1]](read_values())


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        # Exception text deliberately avoids file contents or credential-bearing SQL.
        print(f'Deployment configuration failed: {type(error).__name__}', file=sys.stderr)
        sys.exit(1)
