#!/usr/bin/env python3
"""Example release metadata checks. No downloads, builds or uploads are performed here."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import urlsplit
import zipfile

MARKER = re.compile(r'<!-- hytale-candidate run=([1-9][0-9]*) attempt=([1-9][0-9]*) sha256=([0-9a-f]{64}) -->')
HEX = re.compile(r'[0-9a-f]{64}')
VERSION = re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads(Path(path).read_text())


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def config(path='release.json'):
    c = read(path)
    base = Path(path).resolve().parent
    if 'manifest' in c:
        source = read(base / c['manifest'])
        c.update(version=source['Version'], serverVersion=source['ServerVersion'])
    if 'sdkTarget' in c:
        descriptor = c['sdkTarget']
        pin = read(base / descriptor['path'])
        c.update(sdkVersion=pin[descriptor.get('versionKey', 'hytale_version')], sdkSha256=pin['server_sha256'], javaVersion=str(pin['java_release']))
    version = VERSION.fullmatch(c['version'])
    require(version is not None, 'Use a three-part mod SemVer')
    if version.group(4):
        require(all(not p.isdigit() or p == '0' or not p.startswith('0') for p in version.group(4).split('.')), 'Numeric prerelease identifiers cannot have leading zeroes')
    require(re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]*', c['modId']), 'Invalid mod ID')
    require(re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', c['hytaleFamily']), 'Use a major.minor Hytale family')
    require(c['sdkVersion'].startswith(c['hytaleFamily'] + '.'), 'SDK and Hytale family differ')
    require(HEX.fullmatch(c['sdkSha256'] or ''), 'Set the exact SDK SHA-256')
    require(re.fullmatch(r'[1-9][0-9]*', c['javaVersion']), 'Set a Java release')
    require(c['serverVersion'] and not any(ch in c['serverVersion'] for ch in '\r\n'), 'Invalid manifest support constraint')
    require(c['curseforge']['gameVersion'] == c['hytaleFamily'], 'CurseForge must select the configured minor family')
    require(c['curseforge']['releaseType'] in ('alpha', 'beta', 'release'), 'Invalid release channel')
    require(type(c['curseforge']['manualRelease']) is bool, 'manualRelease must be boolean')
    require(Path(c['artifact']).suffix in ('.jar', '.zip'), 'Expected an installable JAR or ZIP')
    c['artifact'] = c['artifact'].format(modId=c['modId'], hytaleFamily=c['hytaleFamily'], version=c['version'])
    return c


def tag(c):
    return 'hytale' + c['hytaleFamily'] + '-v' + c['version']


def filename(c):
    return c['modId'] + '-hytale' + c['hytaleFamily'] + '-' + c['version'] + Path(c['artifact']).suffix


def manifest(path, c):
    with zipfile.ZipFile(path) as archive:
        require(archive.namelist().count('manifest.json') == 1, 'Expected one root manifest')
        value = json.loads(archive.read('manifest.json'))
    require(value.get('Version') == c['version'], 'Packaged mod version differs from release configuration')
    require(value.get('ServerVersion') == c['serverVersion'], 'Packaged compatibility differs from release configuration')


def commit():
    return subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()


def outputs(values):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as target:
        for key, value in values.items():
            target.write(f'{key}={value}\n')


def selection(c, event, source_commit):
    release = event['release']
    require(release['tag_name'] == tag(c), 'Release tag and configured mod/family differ')
    require(not release.get('draft'), 'The publish event must refer to a published release')
    require(bool(release['prerelease']) == (c['curseforge']['releaseType'] != 'release'), 'GitHub and CurseForge release channels disagree')
    matches = MARKER.findall(release.get('body') or '')
    require(len(matches) == 1, 'Include exactly one tested candidate marker in the release notes')
    run, attempt, digest = matches[0]
    return {'run': int(run), 'attempt': int(attempt), 'sha256': digest, 'commit': source_commit, 'filename': filename(c), 'tag': tag(c)}


def check_run(run, selected, c, repository):
    expected = {'id': selected['run'], 'run_attempt': selected['attempt'], 'head_sha': selected['commit'], 'head_branch': 'hytale' + c['hytaleFamily'], 'event': 'push', 'status': 'completed', 'conclusion': 'success'}
    require(all(run.get(k) == v for k, v in expected.items()), 'Candidate must be a successful push build for this commit, family and attempt')
    require(run['repository']['full_name'] == repository, 'Candidate run belongs to another repository')
    require(run['path'].split('@', 1)[0] == '.github/workflows/build.yml', 'Candidate must come from the configured build workflow')


def verify_candidate(folder, selected, c):
    folder = Path(folder)
    receipt = read(folder / 'candidate.json')
    require(all(receipt.get(k) == selected[k] for k in ('run', 'attempt', 'commit', 'sha256', 'filename', 'tag')), 'Candidate receipt does not match the selected tested artifact')
    require(receipt.get('sdkSha256') == c['sdkSha256'], 'Candidate used a different SDK pin')
    require({p.name for p in folder.iterdir()} == {filename(c), 'candidate.json'}, 'Unexpected candidate contents')
    binary = folder / filename(c)
    require(sha(binary) == selected['sha256'], 'Candidate SHA-256 differs from the tested checksum')
    manifest(binary, c)
    return binary


def cf_metadata(c, catalog, body):
    project = c['curseforge']['projectId']
    require(type(project) is int and project > 0, 'Set the existing CurseForge project ID')
    require(isinstance(catalog, list), 'Unexpected CurseForge version catalog response')
    matches = [row for row in catalog if row.get('name') == c['curseforge']['gameVersion']]
    require(len(matches) == 1, 'Expected one exact CurseForge minor tag; inspect missing/ambiguous catalog entries')
    version_id = matches[0].get('id')
    require(type(version_id) is int and version_id > 0, 'Invalid CurseForge game-version ID')
    return {'displayName': c['version'], 'gameVersions': [version_id], 'releaseType': c['curseforge']['releaseType'], 'isMarkedForManualRelease': c['curseforge']['manualRelease'], 'changelogType': 'markdown', 'changelog': MARKER.sub('', body).strip() + '\n\nHytale support: `' + c['serverVersion'] + '`. CurseForge minor tag: `' + c['hytaleFamily'] + '`.'}


def main():
    command = sys.argv[1]
    c = config()
    if command == 'config':
        outputs({'java': c['javaVersion']})
    elif command == 'verify-sdk':
        require(sha(sys.argv[2]) == c['sdkSha256'], 'SDK fingerprint differs from the pinned build')
    elif command == 'pack':
        manifest(c['artifact'], c)
        folder = Path('candidate')
        folder.mkdir(exist_ok=False)
        binary = folder / filename(c)
        shutil.copyfile(c['artifact'], binary)
        receipt = {'commit': commit(), 'run': int(os.environ['GITHUB_RUN_ID']), 'attempt': int(os.environ['GITHUB_RUN_ATTEMPT']), 'sha256': sha(binary), 'filename': filename(c), 'tag': tag(c), 'sdkSha256': c['sdkSha256']}
        (folder / 'candidate.json').write_text(json.dumps(receipt, indent=2) + '\n')
        marker = f"<!-- hytale-candidate run={receipt['run']} attempt={receipt['attempt']} sha256={receipt['sha256']} -->"
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
            summary.write('Candidate: `' + filename(c) + '`\n\nAfter its required checks, copy this marker into the GitHub release notes:\n\n```text\n' + marker + '\n```\n')
    else:
        event = read(os.environ['GITHUB_EVENT_PATH'])
        selected = selection(c, event, commit())
        if command == 'release-inputs':
            outputs(selected)
        elif command == 'check-run':
            check_run(read(sys.argv[2]), selected, c, os.environ['GITHUB_REPOSITORY'])
        elif command == 'verify-candidate':
            verify_candidate('candidate', selected, c)
        elif command == 'cf-request':
            base = os.environ.get('CURSEFORGE_API_BASE', '').rstrip('/')
            url = urlsplit(base)
            require(re.fullmatch(r'https://[a-z0-9-]+(?:\.[a-z0-9-]+)*\.curseforge\.com', base) and url.scheme == 'https' and not url.username and not url.password and not url.query and not url.fragment and not url.path and url.port is None, 'Configure a verified HTTPS CurseForge author API base host')
            project = c['curseforge']['projectId']
            require(type(project) is int and project > 0, 'Set the existing CurseForge project ID')
            Path('cf-request.env').write_text(f'CF_BASE={base}\nCF_PROJECT={project}\nCF_FILE={filename(c)}\n')
        elif command == 'cf-metadata':
            Path('cf-metadata.json').write_text(json.dumps(cf_metadata(c, read(sys.argv[2]), event['release'].get('body') or ''), indent=2) + '\n')
        elif command == 'cf-receipt':
            file_id = read(sys.argv[2]).get('id')
            require(type(file_id) is int and file_id > 0, 'Upload result is unknown; inspect CurseForge before retrying')
            receipt = {'projectId': c['curseforge']['projectId'], 'fileId': file_id, 'sha256': selected['sha256'], 'tag': selected['tag'], 'status': 'submitted; inspect moderation/publication status separately'}
            Path('curseforge-upload.json').write_text(json.dumps(receipt, indent=2) + '\n')
        else:
            raise ValueError('Unknown command')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        sys.exit(str(error))
