#!/usr/bin/env python3
"""Manual Hytale releases. Network writes occur only in the publish command."""
import argparse
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

import release_pipeline as pipeline

STATE = Path('.release')
PLAN = STATE / 'plan.json'
BASE = 'https://www.curseforge.com'
MARKER = '<!-- hytale-dispatch run={run} source={source} -->'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def write(path, value):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(value, indent=2) + '\n')


def run(args, *, data=None, binary=False, env=None):
    result = subprocess.run(args, input=data, capture_output=True, text=not binary, env=env)
    if result.returncode:
        # Never include command arguments or subprocess output containing credentials.
        raise ValueError(args[0] + ' failed; inspect its operation and repository permissions')
    return result.stdout


def gh(path, method='GET', body=None, raw=False):
    args = ['gh', 'api', '--method', method, path]
    if raw:
        args += ['-H', 'Accept: application/octet-stream']
    if body is not None:
        args += ['--input', '-']
    value = run(args, data=json.dumps(body) if body is not None else None, binary=raw)
    return value if raw else json.loads(value) if value.strip() else None


def rows(path):
    result = []
    for page in range(1, 1001):
        values = gh(path + ('&' if '?' in path else '?') + f'per_page=100&page={page}')
        require(isinstance(values, list), 'Unexpected GitHub list response')
        result += values
        if len(values) < 100:
            return result
    raise ValueError('GitHub pagination exceeded the inspection bound')


def api_root():
    repository = os.environ['GITHUB_REPOSITORY']
    require(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository), 'Invalid repository')
    return 'repos/' + repository


def core(version):
    match = pipeline.VERSION.fullmatch(version)
    require(match is not None, 'Invalid mod SemVer')
    if match.group(4):
        require(all(not p.isdigit() or p == '0' or not p.startswith('0') for p in match.group(4).split('.')), 'Invalid numeric prerelease identifier')
    return tuple(int(match.group(i)) for i in (1, 2, 3))


def next_version(family, current, releases, bump):
    require(bump in ('Major', 'Minor', 'Patch'), 'Select Major, Minor or Patch')
    prefix = 'hytale' + family + '-v'
    versions = []
    for release in releases:
        if release.get('draft') or not release.get('published_at'):
            continue
        tag = release['tag_name']
        if tag.startswith(prefix):
            versions.append((core(tag[len(prefix):]), tag))
    latest = max(versions, default=None)
    major, minor, patch = latest[0] if latest else core(current)
    if bump == 'Major':
        version = (major + 1, 0, 0)
    elif bump == 'Minor':
        version = (major, minor + 1, 0)
    else:
        version = (major, minor, patch + 1)
    return '.'.join(map(str, version)), latest[1] if latest else None


def title(config):
    return f"{config.get('displayName', config['modId'])} {config['version']} — Hytale {config['hytaleFamily']}"


def fingerprint(config):
    keys = ('modId', 'hytaleFamily', 'serverVersion', 'sdkVersion', 'sdkSha256', 'javaVersion')
    value = {key: config[key] for key in keys}
    value['curseforgeProject'] = config['curseforge']['projectId']
    value['manualRelease'] = config['curseforge']['manualRelease']
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def identity(config):
    require(os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'Publishing requires manual workflow dispatch')
    branch = os.environ['GITHUB_REF_NAME']
    require(os.environ.get('GITHUB_REF_TYPE') == 'branch' and branch == 'hytale' + config['hytaleFamily'], 'Select the matching hytaleX.Y support branch, not a tag or working branch')
    return {'run': int(os.environ['GITHUB_RUN_ID']), 'repository': os.environ['GITHUB_REPOSITORY'], 'source': os.environ['GITHUB_SHA'], 'branch': branch, 'bump': os.environ.get('VERSION_BUMP', 'Patch'), 'channel': os.environ.get('RELEASE_TYPE', 'Release')}


def check_plan(plan, config):
    require(plan.get('schema') == 1 and all(plan.get(k) == v for k, v in identity(config).items()), 'Saved release plan does not match this dispatch')
    require(plan['fingerprint'] == fingerprint(config), 'SDK, compatibility or upload destination differs from the saved plan')
    core(plan['version'])
    require(plan['tag'] == 'hytale' + config['hytaleFamily'] + '-v' + plan['version'], 'Saved release tag does not match its Hytale family')
    return plan


def artifact(name):
    values = gh(api_root() + f"/actions/runs/{os.environ['GITHUB_RUN_ID']}/artifacts?per_page=100")['artifacts']
    matches = [v for v in values if v['name'] == name]
    require(len(matches) <= 1, 'Ambiguous saved release artifact')
    if not matches:
        return None
    require(not matches[0]['expired'], 'Saved release artifact expired; do not rebuild or bump on this rerun')
    raw = gh(api_root() + '/actions/artifacts/' + str(matches[0]['id']) + '/zip', raw=True)
    return zipfile.ZipFile(io.BytesIO(raw))


def restore(name, folder, allowed):
    archive = artifact(name)
    if archive is None:
        return False
    with archive:
        require(set(archive.namelist()) == set(allowed), 'Unexpected saved release artifact contents')
        folder = Path(folder)
        folder.mkdir(parents=True, exist_ok=True)
        for name in allowed:
            (folder / name).write_bytes(archive.read(name))
    return True


def curseforge(path, *, upload=None):
    base = os.environ.get('CURSEFORGE_API_BASE') or BASE
    require(base == BASE, 'Use the verified https://www.curseforge.com author API origin')
    token = os.environ.get('CURSEFORGE_API_TOKEN', '')
    require(token and '\n' not in token and '\r' not in token, 'Set the repository Actions secret CURSEFORGE_API_TOKEN')
    with tempfile.TemporaryDirectory() as folder:
        response = Path(folder) / 'response.json'
        args = ['curl', '--silent', '--show-error', '--max-time', '180' if upload else '60', '--retry', '0', '--max-redirs', '0', '--proto', '=https', '--config', '-', '--output', str(response), '--write-out', '%{http_code}']
        if upload:
            metadata, binary = upload
            args += ['--form', 'metadata=<' + str(metadata), '--form', 'file=@' + str(binary) + ';type=application/octet-stream']
        args += [base + path]
        status = run(args, data='header = ' + json.dumps('X-Api-Token: ' + token) + '\n').strip()
        require(status.isdigit() and 200 <= int(status) < 300, 'CurseForge request failed (HTTP ' + status + '); no upload is retried automatically')
        return json.loads(response.read_text())


def connection(config):
    project = config['curseforge']['projectId']
    require(type(project) is int and project > 0, 'Create the CurseForge listing manually and set its projectId in release.json before publishing')
    catalog = curseforge('/api/game/hytale/versions')
    pipeline.cf_metadata(config, catalog, '')
    return catalog


def stamp(plan):
    descriptor = pipeline.read('release.json')
    paths = ['release.json']
    if 'manifest' in descriptor:
        path = descriptor['manifest']
        require(not Path(path).is_absolute() and '..' not in Path(path).parts, 'Manifest must be repository-relative')
        manifest = pipeline.read(path)
        manifest['Version'] = plan['version']
        write(path, manifest)
        paths.append(path)
    else:
        descriptor['version'] = plan['version']
    descriptor['curseforge']['releaseType'] = plan['channel'].lower()
    write('release.json', descriptor)
    run(['git', 'add', '--', *paths])
    env = os.environ.copy()
    env.update(GIT_AUTHOR_NAME='github-actions[bot]', GIT_COMMITTER_NAME='github-actions[bot]', GIT_AUTHOR_EMAIL='41898282+github-actions[bot]@users.noreply.github.com', GIT_COMMITTER_EMAIL='41898282+github-actions[bot]@users.noreply.github.com', GIT_AUTHOR_DATE=plan['created'], GIT_COMMITTER_DATE=plan['created'])
    run(['git', 'commit', '--allow-empty', '-m', 'Release ' + plan['tag'] + ' (' + plan['channel'] + ')'], env=env)
    return pipeline.config()


def prepare():
    config = pipeline.config()
    wanted = identity(config)
    require(wanted['channel'] in ('Release', 'Beta', 'Alpha'), 'Select Release, Beta or Alpha')
    STATE.mkdir(exist_ok=True)
    restored = restore('release-plan', STATE, ['plan.json'])
    if restored:
        plan = check_plan(pipeline.read(PLAN), config)
    else:
        require(int(os.environ['GITHUB_RUN_ATTEMPT']) == 1, 'This rerun has no saved release plan; stop rather than choosing a different version')
        releases = rows(api_root() + '/releases')
        # A new dispatch must not skip over a previous unfinished store upload.
        prefix = wanted['branch'] + '-v'
        require(not any(r['draft'] and r['tag_name'].startswith(prefix) for r in releases), 'An unfinished draft release exists on this Hytale line; reconcile or rerun its original workflow first')
        version, previous = next_version(config['hytaleFamily'], config['version'], releases, wanted['bump'])
        plan = dict(schema=1, **wanted, version=version, previous=previous, tag=prefix + version, fingerprint=fingerprint(config), created=datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))
        check_plan(plan, config)
        write(PLAN, plan)
    # Check credentials/catalog before changing versions, tags or repository state.
    connection(config)
    config = stamp(plan)
    if restored:
        require(plan['commit'] == pipeline.commit(), 'Saved release version commit is not reproducible; stop before publishing')
    plan['commit'] = pipeline.commit()
    write(PLAN, plan)
    pipeline.outputs({'new_plan': str(not restored).lower(), 'java': config['javaVersion'], 'tag': plan['tag'], 'version': plan['version']})
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
        stream.write(f"Selected {title(config)} ({plan['channel']}); tag `{plan['tag']}`.\n")


def candidate():
    config = pipeline.config()
    plan = check_plan(pipeline.read(PLAN), config)
    found = restore('release-candidate', 'candidate', [pipeline.filename(config), 'candidate.json'])
    if found:
        verify(config, plan)
    pipeline.outputs({'restored': str(found).lower()})


def verify(config, plan):
    receipt = pipeline.read('candidate/candidate.json')
    require(receipt['run'] == plan['run'] and receipt['commit'] == plan['commit'], 'Candidate belongs to another release dispatch or commit')
    selected = {k: receipt[k] for k in ('run', 'attempt', 'commit', 'sha256', 'filename', 'tag')}
    require(selected['tag'] == plan['tag'], 'Candidate tag differs from the saved release plan')
    return pipeline.verify_candidate('candidate', selected, config), selected


def marker(plan):
    return MARKER.format(**plan)


def release_check(release, config, plan):
    require(release['tag_name'] == plan['tag'] and marker(plan) in (release.get('body') or ''), 'Existing release belongs to a different dispatch; never overwrite it')
    require(release['name'] == title(config) and release['prerelease'] == (plan['channel'] != 'Release'), 'Existing release title/channel differs from the saved plan')


def assets(release):
    return {a['name']: a for a in rows(api_root() + f"/releases/{release['id']}/assets")}


def asset_bytes(asset):
    return gh(api_root() + '/releases/assets/' + str(asset['id']), raw=True)


def attach(release, path):
    existing = assets(release).get(Path(path).name)
    if existing:
        require(hashlib.sha256(asset_bytes(existing)).hexdigest() == pipeline.sha(path), 'Existing release asset differs; never overwrite published bytes')
    else:
        run(['gh', 'release', 'upload', release['tag_name'], str(path), '--repo', os.environ['GITHUB_REPOSITORY']])


def receipt_check(receipt, config, selected):
    require(receipt.get('tag') == selected['tag'] and receipt.get('sha256') == selected['sha256'] and receipt.get('projectId') == config['curseforge']['projectId'] and type(receipt.get('fileId')) is int and receipt['fileId'] > 0, 'CurseForge receipt does not match this exact release')


def publish():
    config = pipeline.config()
    plan = check_plan(pipeline.read(PLAN), config)
    binary, selected = verify(config, plan)
    require(pipeline.commit() == plan['commit'], 'Release source commit changed')
    releases = rows(api_root() + '/releases')
    matches = [r for r in releases if r['tag_name'] == plan['tag']]
    require(len(matches) <= 1, 'Ambiguous existing release tag')
    release = matches[0] if matches else None
    if release:
        release_check(release, config, plan)
    else:
        remote = gh(api_root() + '/git/ref/heads/' + plan['branch'])['object']['sha']
        require(remote in (plan['source'], plan['commit']), 'The support branch advanced after dispatch; start from its new source instead')
        refs = run(['git', '-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential', 'ls-remote', 'origin', 'refs/tags/' + plan['tag']]).strip()
        if refs:
            require(refs.split()[0] == plan['commit'], 'Release tag already exists at another commit')
        else:
            run(['git', 'tag', plan['tag'], plan['commit']])
        # One non-forced atomic push; another branch update causes a clean failure.
        run(['git', '-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential', 'push', '--atomic', 'origin', plan['commit'] + ':refs/heads/' + plan['branch'], 'refs/tags/' + plan['tag']])
        notes = f"{title(config)}\n\nHytale support: `{config['serverVersion']}`. Channel: {plan['channel']}.\n\n"
        if plan['previous']:
            generated = gh(api_root() + '/releases/generate-notes', 'POST', {'tag_name': plan['tag'], 'target_commitish': plan['commit'], 'previous_tag_name': plan['previous']})
            notes += generated['body'] + '\n\n'
        else:
            notes += 'First release on this Hytale support line.\n\n'
        notes += marker(plan)
        release = gh(api_root() + '/releases', 'POST', {'tag_name': plan['tag'], 'target_commitish': plan['commit'], 'name': title(config), 'body': notes, 'draft': True, 'prerelease': plan['channel'] != 'Release'})
    attach(release, binary)
    attach(release, 'candidate/candidate.json')
    present = assets(release)
    if 'curseforge-upload.json' in present:
        receipt = json.loads(asset_bytes(present['curseforge-upload.json']))
        receipt_check(receipt, config, selected)
    elif restore('curseforge-confirmed-receipt', '.', ['curseforge-upload.json']):
        receipt = pipeline.read('curseforge-upload.json')
        receipt_check(receipt, config, selected)
        attach(release, 'curseforge-upload.json')
    else:
        require(release['draft'], 'Published release has no confirmed CurseForge receipt; inspect before retrying')
        require('curseforge-upload-intent.json' not in present, 'A CurseForge upload was already attempted but has no confirmed receipt. Inspect the store before reconciling; this run will not submit a duplicate')
        catalog = connection(config)
        metadata = pipeline.cf_metadata(config, catalog, release['body'])
        # Internal dispatch markers belong only in GitHub release metadata.
        metadata['changelog'] = metadata['changelog'].replace(marker(plan), '').strip()
        write(STATE / 'cf-metadata.json', metadata)
        intent = {'tag': plan['tag'], 'sha256': selected['sha256'], 'projectId': config['curseforge']['projectId'], 'run': plan['run']}
        write(STATE / 'curseforge-upload-intent.json', intent)
        attach(release, STATE / 'curseforge-upload-intent.json')
        response = curseforge('/api/projects/' + str(config['curseforge']['projectId']) + '/upload-file', upload=(STATE / 'cf-metadata.json', binary))
        file_id = response.get('id')
        require(type(file_id) is int and file_id > 0, 'Unknown CurseForge upload result; inspect the store before reconciling')
        receipt = {'projectId': config['curseforge']['projectId'], 'fileId': file_id, 'sha256': selected['sha256'], 'tag': plan['tag'], 'status': 'submitted; moderation/publication may still be pending'}
        write('curseforge-upload.json', receipt)
        attach(release, 'curseforge-upload.json')
    if release['draft']:
        default = gh(api_root())['default_branch']
        release = gh(api_root() + '/releases/' + str(release['id']), 'PATCH', {'draft': False, 'make_latest': 'true' if plan['channel'] == 'Release' and plan['branch'] == default else 'false'})
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
        stream.write(f"GitHub release: {release['html_url']}\n\nCurseForge project {receipt['projectId']}, file {receipt['fileId']} submitted. Moderation/publication may still be pending.\n\nSHA-256: `{receipt['sha256']}`\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'candidate', 'publish', 'check-connection', 'check-connection-if-configured'])
    args = parser.parse_args()
    if args.command == 'prepare':
        prepare()
    elif args.command == 'candidate':
        candidate()
    elif args.command == 'publish':
        publish()
    else:
        config = pipeline.config()
        if args.command == 'check-connection-if-configured' and config['curseforge']['projectId'] is None:
            print('CurseForge listing not created; publishing stays unavailable until projectId is set.')
            return
        connection(config)
        print('CurseForge token and Hytale minor catalog entry verified; no file uploaded.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        sys.exit(str(error))
