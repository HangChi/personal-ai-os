#!/usr/bin/env python3
"""数据库、聊天附件备份与隔离恢复验证；不执行 .env，不修改运行中的业务库。"""
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
from datetime import datetime, timezone
import uuid
import urllib.request
import urllib.error
import urllib.parse
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]


def settings():
    values = {}
    for env in [ROOT / '.env', ROOT / '.env.integrations.local']:
        if env.is_file():
            for raw in env.read_text().splitlines():
                line = raw.strip().removeprefix('export ')
                if not line or line.startswith('#') or '=' not in line:
                    continue
                key, value = line.split('=', 1)
                value = value.strip()
                if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                    value = value[1:-1]
                values[key.strip()] = value
    values.update(os.environ)
    return values


def run(args, **kwargs):
    # 不打印命令/服务响应，避免日志泄漏数据库名、端点和配置。
    return subprocess.run(args, check=True, stderr=subprocess.PIPE, **kwargs)


def sha(path):
    with path.open('rb') as stream:
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
        return digest.hexdigest()


def pg(container, user, command, *args, **kwargs):
    return run(['docker', 'exec', '-i', container, command, '-U', user, *args], **kwargs)


def validate_dump(dump, container, user):
    with dump.open('rb') as stream:
        pg(container, user, 'pg_restore', '--list', stdin=stream, stdout=subprocess.DEVNULL)


def restore_drill(dump, container, user):
    # 仅创建/删除本次随机生成的隔离库，绝不接收用户指定恢复目标。
    database = 'paios_drill_' + uuid.uuid4().hex
    pg(container, user, 'createdb', database, stdout=subprocess.DEVNULL)
    try:
        with dump.open('rb') as stream:
            pg(container, user, 'pg_restore', '--exit-on-error', '--no-owner', '--no-privileges',
               '-d', database, stdin=stream, stdout=subprocess.DEVNULL)
        pg(container, user, 'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-d', database,
           '-c', 'SELECT count(*) FROM flyway_schema_history; SELECT count(*) FROM token_usage;',
           stdout=subprocess.DEVNULL)
    finally:
        pg(container, user, 'dropdb', '--if-exists', database, stdout=subprocess.DEVNULL)
    print('隔离数据库恢复演练通过，临时库已删除')


def unpack(archive, target):
    target.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive, 'r:') as tar:
        names = set()
        for member in tar.getmembers():
            path = Path(member.name)
            if path.is_absolute() or '..' in path.parts or not (member.isfile() or member.isdir()):
                raise ValueError('备份包含不安全的文件路径或链接')
            if member.name in names:
                raise ValueError('备份包含重复文件')
            names.add(member.name)
        tar.extractall(target)
    manifest = json.loads((target / 'manifest.json').read_text())
    if manifest['formatVersion'] != 1:
        raise ValueError('不支持的备份格式')
    expected = manifest['sha256']
    actual = {str(p.relative_to(target)): sha(p) for p in target.rglob('*')
              if p.is_file() and p.relative_to(target).as_posix() != 'manifest.json'}
    if expected != actual:
        raise ValueError('备份内部文件校验失败')
    if 'database.dump' not in actual:
        raise ValueError('备份缺少数据库')
    return target / 'database.dump'


def verify(artifact, config, drill=False, identity=None):
    artifact = artifact.resolve()
    sidecar = Path(str(artifact) + '.sha256')
    if not sidecar.is_file() or sidecar.read_text().strip() != sha(artifact):
        raise ValueError('备份 SHA-256 校验失败或校验文件缺失')
    container = config.get('DB_CONTAINER', 'personal-ai-os-postgres')
    user = config.get('POSTGRES_USER', 'personalos')
    with tempfile.TemporaryDirectory(prefix='paios-verify-') as tmp:
        temp = Path(tmp)
        archive = artifact
        if artifact.suffix == '.age':
            if not identity:
                raise ValueError('加密备份校验需要 --identity 指定 age 身份文件')
            archive = temp / 'decrypted.tar'
            run(['age', '--decrypt', '-i', str(identity), '-o', str(archive), str(artifact)])
        dump = unpack(archive, temp / 'contents')
        validate_dump(dump, container, user)
        if drill:
            restore_drill(dump, container, user)
    print('备份文件、附件与数据库归档校验通过')


class S3Snapshot:
    """只读、path-style 的逻辑对象快照；不修改远端对象，不输出凭据。"""
    def __init__(self, config):
        self.endpoint = config.get('S3_ENDPOINT', '').rstrip('/')
        uri = urllib.parse.urlparse(self.endpoint)
        self.access = config.get('S3_ACCESS_KEY') or config.get('AWS_ACCESS_KEY_ID')
        self.secret = config.get('S3_SECRET_KEY') or config.get('AWS_SECRET_ACCESS_KEY')
        namespace = config.get('S3_NAMESPACE', '')
        self.bucket = config.get('S3_BUCKET', '')
        if uri.scheme not in ('http', 'https') or uri.username or not self.access or not self.secret or not namespace or not self.bucket:
            raise ValueError('S3 备份需要合法端点、bucket、namespace 和可读凭据，未生成缺少对象的备份')
        self.region = config.get('S3_REGION', 'us-east-1')
        self.owner = hashlib.sha256(namespace.encode()).hexdigest()
        self.prefix = 'personalos/' + self.owner + '/attachments/'
        self.host = uri.netloc
        self.maximum = int(config.get('AI_CHAT_MAX_ATTACHMENT_BYTES', str(64 * 1024 * 1024)))
        self.session = config.get('AWS_SESSION_TOKEN')
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None
        self.opener = urllib.request.build_opener(NoRedirect())

    def get(self, key=None, parameters=None):
        path = urllib.parse.quote('/' + self.bucket + ('/' + key if key else ''), safe='/-_.~')
        query = urllib.parse.urlencode(sorted((parameters or {}).items()), quote_via=urllib.parse.quote, safe='-_.~')
        now = datetime.now(timezone.utc)
        day, timestamp = now.strftime('%Y%m%d'), now.strftime('%Y%m%dT%H%M%SZ')
        empty = hashlib.sha256(b'').hexdigest()
        headers = {'host': self.host, 'x-amz-content-sha256': empty, 'x-amz-date': timestamp}
        if self.session:
            headers['x-amz-security-token'] = self.session
        signed = ';'.join(sorted(headers))
        canonical_headers = ''.join(name + ':' + headers[name] + '\n' for name in sorted(headers))
        canonical = '\n'.join(['GET', path, query, canonical_headers, signed, empty])
        scope = day + '/' + self.region + '/s3/aws4_request'
        sign = 'AWS4-HMAC-SHA256\n' + timestamp + '\n' + scope + '\n' + hashlib.sha256(canonical.encode()).hexdigest()
        key_bytes = ('AWS4' + self.secret).encode()
        for value in [day, self.region, 's3', 'aws4_request']:
            key_bytes = hmac.new(key_bytes, value.encode(), hashlib.sha256).digest()
        signature = hmac.new(key_bytes, sign.encode(), hashlib.sha256).hexdigest()
        headers['Authorization'] = 'AWS4-HMAC-SHA256 Credential=' + self.access + '/' + scope + ', SignedHeaders=' + signed + ', Signature=' + signature
        try:
            return self.opener.open(urllib.request.Request(self.endpoint + path + ('?' + query if query else ''), headers=headers), timeout=30)
        except urllib.error.URLError:
            raise ValueError('S3 对象读取失败，备份未发布；请检查端点与读取权限') from None

    def copy_to(self, target):
        token = None
        count = 0
        seen_tokens = set()
        while True:
            parameters = {'list-type': '2', 'prefix': self.prefix, 'max-keys': '100'}
            if token:
                parameters['continuation-token'] = token
            with self.get(parameters=parameters) as response:
                body = response.read(1_000_001)
            if len(body) > 1_000_000:
                raise ValueError('S3 列表响应超过上限')
            tree = ET.fromstring(body)
            for item in tree.findall('{*}Contents'):
                key = item.findtext('{*}Key', '')
                if not key.startswith(self.prefix):
                    raise ValueError('S3 返回了命名空间外对象，拒绝备份')
                identity = key[len(self.prefix):]
                try:
                    if str(uuid.UUID(identity)) != identity:
                        raise ValueError()
                except ValueError:
                    raise ValueError('S3 对象键不是本应用附件 UUID') from None
                destination = target / identity
                # 应用优先读旧本地附件，因此备份保留同样的读取优先级。
                if destination.exists():
                    continue
                with self.get(key) as response:
                    if response.headers.get('x-amz-meta-personalos-owner') != self.owner:
                        raise ValueError('S3 对象归属标记不匹配，拒绝备份')
                    size = 0
                    with destination.open('wb') as output:
                        while True:
                            chunk = response.read(1024 * 1024)
                            if not chunk:
                                break
                            size += len(chunk)
                            if size > self.maximum:
                                raise ValueError('S3 附件超过备份大小上限')
                            output.write(chunk)
                    if size != int(item.findtext('{*}Size', '-1')):
                        raise ValueError('S3 对象大小发生变化，备份未发布')
                count += 1
            if tree.findtext('{*}IsTruncated', 'false').lower() != 'true':
                return count
            token = tree.findtext('{*}NextContinuationToken')
            if not token or token in seen_tokens:
                raise ValueError('S3 分页游标无效，备份未发布')
            seen_tokens.add(token)


def backup(config):
    container = config.get('DB_CONTAINER', 'personal-ai-os-postgres')
    user = config.get('POSTGRES_USER', 'personalos')
    database = config.get('POSTGRES_DB', 'personal_ai_os')
    directory = Path(config.get('BACKUP_DIR', str(ROOT / 'backups'))).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    retain = int(config.get('BACKUP_RETAIN', '0'))
    if retain < 0:
        raise ValueError('BACKUP_RETAIN 不能为负数，0 表示永久保留')
    recipient = config.get('BACKUP_AGE_RECIPIENT', '')
    name = 'personal-ai-os-' + datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8]
    artifact = directory / (name + '.tar' + ('.age' if recipient else ''))
    # 临时目录与目标同文件系统；失败不会留下貌似成功的备份。
    with tempfile.TemporaryDirectory(prefix='.paios-', dir=directory) as tmp:
        temp = Path(tmp)
        data = temp / 'contents'
        data.mkdir()
        dump = data / 'database.dump'
        with dump.open('wb') as stream:
            pg(container, user, 'pg_dump', '-d', database, '-Fc', stdout=stream)
        validate_dump(dump, container, user)
        uploads = data / 'uploads'
        uploads.mkdir()
        backend = config.get('BACKUP_BACKEND_CONTAINER', '')
        if backend:
            run(['docker', 'cp', backend + ':/app/data/uploads/.', str(uploads)])
        else:
            source = Path(config.get('BACKUP_UPLOAD_DIR', config.get('AI_CHAT_UPLOAD_DIR', 'data/uploads')))
            if not source.is_absolute():
                source = ROOT / 'backend' / source
            if not source.is_dir():
                raise ValueError('附件目录不存在，请配置 BACKUP_UPLOAD_DIR 或 BACKUP_BACKEND_CONTAINER')
            if any(p.is_symlink() for p in source.rglob('*')):
                raise ValueError('附件目录不允许符号链接')
            shutil.copytree(source, uploads, dirs_exist_ok=True)
        if config.get('S3_ENABLED', 'false').lower() == 'true':
            copied_objects = S3Snapshot(config).copy_to(uploads)
            print('已将 S3 附件纳入逻辑备份：' + str(copied_objects) + ' 项')
        files = {str(p.relative_to(data)): sha(p) for p in data.rglob('*') if p.is_file()}
        (data / 'manifest.json').write_text(json.dumps({
            'formatVersion': 1, 'createdAt': datetime.now(timezone.utc).isoformat(),
            'consistency': '请在暂停应用写入的窗口备份数据库与附件', 'sha256': files}, ensure_ascii=False, indent=2))
        archive = temp / 'archive.tar'
        with tarfile.open(archive, 'w') as tar:
            for child in sorted(data.iterdir()):
                tar.add(child, arcname=child.name)
        unpack(archive, temp / 'verify')
        pending = archive
        if recipient:
            pending = temp / 'archive.tar.age'
            run(['age', '--encrypt', '-r', recipient, '-o', str(pending), str(archive)])
        pending.chmod(0o600)
        checksum = temp / 'checksum'
        checksum.write_text(sha(pending) + '\n')
        checksum.chmod(0o600)
        # checksum 最后发布；缺它的包会被 verify 明确拒绝。
        pending.replace(artifact)
        checksum.replace(Path(str(artifact) + '.sha256'))
    copy_dir = config.get('BACKUP_COPY_DIR')
    if copy_dir:
        destination = Path(copy_dir).expanduser().resolve()
        destination.mkdir(parents=True, exist_ok=True)
        copied = destination / artifact.name
        if copied != artifact:
            staging = destination / ('.' + artifact.name + '.partial')
            shutil.copyfile(artifact, staging)
            staging.chmod(0o600)
            if sha(staging) != sha(artifact):
                raise ValueError('异地副本校验失败')
            staging.replace(copied)
            shutil.copyfile(str(artifact) + '.sha256', str(copied) + '.sha256')
    if retain:
        # 仅清理本工具格式且完整的包，保留旧版 .dump 和 pre-clean 备份。
        backups = sorted([p for p in directory.glob('personal-ai-os-*.tar*')
                          if p.name.endswith(('.tar', '.tar.age')) and Path(str(p) + '.sha256').is_file()], reverse=True)
        for old in backups[retain:]:
            old.unlink()
            Path(str(old) + '.sha256').unlink()
    print('备份完成：' + str(artifact))
    return artifact


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['backup', 'verify', 'drill'])
    parser.add_argument('artifact', nargs='?', type=Path)
    parser.add_argument('--identity', type=Path)
    args = parser.parse_args()
    config = settings()
    if args.command == 'backup':
        backup(config)
    elif args.artifact:
        verify(args.artifact, config, args.command == 'drill', args.identity)
    else:
        parser.error('verify/drill 需要备份文件路径')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, tarfile.TarError, KeyError) as error:
        # 子进程 stderr 可能含服务细节，保留失败类型供排查，不输出凭据。
        raise SystemExit('备份操作失败：' + (str(error) if not isinstance(error, subprocess.CalledProcessError)
                                       else '外部命令执行失败，请检查 Docker/age 与数据库状态'))
