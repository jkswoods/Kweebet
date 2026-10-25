"""Offline release regression checks. No requests to GitHub, CurseForge or Hytale."""
import copy
from contextlib import contextmanager
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import release_dispatch as release
import release_pipeline as pipeline


def published(tag):
    return {'tag_name': tag, 'draft': False, 'published_at': '2026-10-25T00:00:00Z'}


class Versioning(unittest.TestCase):
    def test_bumps_and_resets(self):
        for bump, expected in [('Patch', '1.3.41'), ('Minor', '1.4.0'), ('Major', '2.0.0')]:
            self.assertEqual(release.next_version('0.7', '1.3.40', [], bump)[0], expected)

    def test_equal_mod_versions_on_two_families_are_independent(self):
        values = [published('hytale0.6-v1.3.40'), published('hytale0.7-v1.3.40'), published('hytale0.6-v9.0.0')]
        self.assertEqual(release.next_version('0.7', '0.0.0', values, 'Patch'), ('1.3.41', 'hytale0.7-v1.3.40'))
        self.assertEqual(release.next_version('0.6', '0.0.0', values, 'Patch')[0], '9.0.1')

    def test_highest_numeric_version_wins_over_list_order(self):
        values = [published('hytale0.7-v1.9.9'), published('hytale0.7-v1.10.0'), published('hytale0.7-v1.2.99')]
        self.assertEqual(release.next_version('0.7', '9.0.0', values, 'Patch')[0], '1.10.1')

    def test_first_family_release_uses_manifest_and_ignores_legacy_tags(self):
        self.assertEqual(release.next_version('0.7', '0.2.3-dev.1', [published('v5.0.0'), published('hytale0.6-v4.0.0')], 'Patch'), ('0.2.4', None))

    def test_published_alpha_and_beta_occupy_their_numeric_version(self):
        self.assertEqual(release.next_version('0.7', '0.0.0', [published('hytale0.7-v1.3.40-beta.2')], 'Patch')[0], '1.3.41')

    def test_drafts_do_not_advance_the_version(self):
        draft = dict(published('hytale0.7-v99.0.0'), draft=True)
        self.assertEqual(release.next_version('0.7', '0.2.0-dev.1', [draft], 'Minor')[0], '0.3.0')

    def test_invalid_semver_or_bump_fails(self):
        for version in ['1.2', '1.02.3', '1.2.3-alpha.01']:
            with self.subTest(version=version), self.assertRaises(ValueError):
                release.next_version('0.7', version, [], 'Patch')
        with self.assertRaises(ValueError):
            release.next_version('0.7', '1.2.3', [], 'Auto')

    def test_title_and_filename_include_both_versions(self):
        config = {'modId': 'Example', 'hytaleFamily': '0.7', 'version': '1.3.40', 'artifact': 'build/mod.jar'}
        self.assertEqual(release.title(config), 'Example 1.3.40 — Hytale 0.7')
        self.assertEqual(pipeline.filename(config), 'Example-hytale0.7-1.3.40.jar')


class Dispatch(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.original = Path.cwd()
        os.chdir(self.temp.name)
        self.addCleanup(os.chdir, self.original)
        self.config = {'modId': 'Example', 'hytaleFamily': '0.7', 'version': '1.3.41', 'artifact': 'build/Example-hytale0.7-1.3.41.jar', 'serverVersion': '>=0.7.3 <0.8.0', 'sdkVersion': '0.7.3', 'sdkSha256': 'a' * 64, 'javaVersion': '25', 'curseforge': {'projectId': 123, 'gameVersion': '0.7', 'releaseType': 'release', 'manualRelease': False}}
        self.env = {'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF_TYPE': 'branch', 'GITHUB_REF_NAME': 'hytale0.7', 'GITHUB_SHA': 'b' * 40, 'GITHUB_RUN_ID': '1234', 'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_REPOSITORY': 'example/mod', 'VERSION_BUMP': 'Patch', 'RELEASE_TYPE': 'Release', 'GITHUB_STEP_SUMMARY': str(Path.cwd()/'summary.md')}
        self.addCleanup(patch.stopall)
        patch.dict(os.environ, self.env).start()
        self.plan = {'schema': 1, **release.identity(self.config), 'version': '1.3.41', 'tag': 'hytale0.7-v1.3.41', 'previous': 'hytale0.7-v1.3.40', 'fingerprint': release.fingerprint(self.config), 'created': '2026-10-25T00:00:00Z', 'commit': 'c' * 40}
        release.write(release.PLAN, self.plan)
        Path('candidate').mkdir()
        binary = Path('candidate') / pipeline.filename(self.config)
        with zipfile.ZipFile(binary, 'w') as archive:
            archive.writestr('manifest.json', json.dumps({'Version': self.config['version'], 'ServerVersion': self.config['serverVersion']}))
        self.selected = {'run': 1234, 'attempt': 1, 'commit': 'c'*40, 'sha256': pipeline.sha(binary), 'filename': binary.name, 'tag': self.plan['tag']}
        release.write('candidate/candidate.json', self.selected | {'sdkSha256': self.config['sdkSha256']})
        self.release = {'id': 55, 'tag_name': self.plan['tag'], 'name': release.title(self.config), 'body': release.marker(self.plan), 'draft': True, 'prerelease': False, 'html_url': 'https://example.invalid/release'}

    def test_saved_plan_rejects_different_branch_channel_or_source(self):
        for key, value in [('branch', 'hytale0.6'), ('channel', 'Alpha'), ('source', 'd'*40), ('run', 5678)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                release.check_plan(self.plan | {key: value}, self.config)

    def test_working_branch_and_tag_are_rejected(self):
        for values in [{'GITHUB_REF_NAME': 'codex/work'}, {'GITHUB_REF_TYPE': 'tag'}]:
            with patch.dict(os.environ, values), self.assertRaisesRegex(ValueError, 'support branch'):
                release.identity(self.config)

    def test_saved_candidate_hash_and_identity_are_checked(self):
        release.verify(self.config, self.plan)
        binary = Path('candidate') / pipeline.filename(self.config)
        binary.write_bytes(binary.read_bytes() + b'changed')
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            release.verify(self.config, self.plan)

    def test_archive_paths_cannot_escape_destination(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as archive:
            archive.writestr('../outside', 'no')
        data.seek(0)
        with patch.object(release, 'artifact', return_value=zipfile.ZipFile(data)), self.assertRaisesRegex(ValueError, 'Unexpected'):
            release.restore('release-plan', '.release', ['plan.json'])
        self.assertFalse(Path('../outside').exists())

    def test_missing_listing_fails_before_network(self):
        config = copy.deepcopy(self.config)
        config['curseforge']['projectId'] = None
        with patch.object(release, 'curseforge') as request, self.assertRaisesRegex(ValueError, 'listing'):
            release.connection(config)
        request.assert_not_called()

    def test_curl_token_uses_stdin_and_upload_has_no_retries(self):
        seen = {}
        def request(args, *, data):
            seen.update(args=args, data=data)
            Path(args[args.index('--output')+1]).write_text('{"id": 42}')
            return '200'
        with patch.dict(os.environ, {'CURSEFORGE_API_TOKEN': 'test-token', 'CURSEFORGE_API_BASE': release.BASE}), patch.object(release, 'run', side_effect=request):
            value = release.curseforge('/api/projects/123/upload-file', upload=(Path('metadata.json'), Path('mod.jar')))
        self.assertEqual(value['id'], 42)
        self.assertNotIn('test-token', ' '.join(seen['args']))
        self.assertIn('X-Api-Token: test-token', seen['data'])
        self.assertEqual(seen['args'][seen['args'].index('--retry')+1], '0')
        self.assertNotIn('--location', seen['args'])

    def test_other_curseforge_hosts_are_rejected_before_credentials_are_sent(self):
        with patch.dict(os.environ, {'CURSEFORGE_API_BASE': 'https://example.invalid'}), patch.object(release, 'run') as request, self.assertRaises(ValueError):
            release.curseforge('/api/game/hytale/versions')
        request.assert_not_called()

    def publish_mocks(self, present):
        patch.object(pipeline, 'config', return_value=self.config).start()
        patch.object(pipeline, 'commit', return_value=self.plan['commit']).start()
        patch.object(release, 'rows', return_value=[self.release]).start()
        patch.object(release, 'attach').start()
        patch.object(release, 'assets', return_value=present).start()
        patch.object(release, 'restore', return_value=False).start()

    def test_unknown_upload_outcome_never_reposts(self):
        self.publish_mocks({'curseforge-upload-intent.json': {'id': 77}})
        with patch.object(release, 'curseforge') as request, self.assertRaisesRegex(ValueError, 'already attempted'):
            release.publish()
        request.assert_not_called()

    def test_confirmed_upload_resumes_github_finalization_without_reposting(self):
        self.publish_mocks({'curseforge-upload.json': {'id': 78}})
        receipt = {'tag': self.plan['tag'], 'sha256': self.selected['sha256'], 'projectId': 123, 'fileId': 999}
        def api(path, method='GET', body=None):
            if method == 'PATCH':
                self.assertFalse(body['draft'])
                return self.release | {'draft': False}
            return {'default_branch': 'hytale0.7'}
        with patch.object(release, 'asset_bytes', return_value=json.dumps(receipt).encode()), patch.object(release, 'gh', side_effect=api), patch.object(release, 'curseforge') as request:
            release.publish()
        request.assert_not_called()

    def test_changed_existing_asset_is_never_overwritten(self):
        Path('asset.jar').write_bytes(b'new bytes')
        with patch.object(release, 'assets', return_value={'asset.jar': {'id': 1}}), patch.object(release, 'asset_bytes', return_value=b'old bytes'), self.assertRaisesRegex(ValueError, 'never overwrite'):
            release.attach(self.release, 'asset.jar')

    def test_alpha_beta_map_to_github_prerelease(self):
        for channel in ['Alpha', 'Beta']:
            plan = self.plan | {'channel': channel}
            release.release_check(self.release | {'body': release.marker(plan), 'prerelease': True}, self.config, plan)
            with self.assertRaises(ValueError):
                release.release_check(self.release, self.config, plan)

    def test_version_commit_is_deterministic_and_preserves_compatibility(self):
        def git(*args):
            return subprocess.check_output(['git', *args],text=True).strip()
        git('init','-q')
        git('config','user.name','fixture')
        git('config','user.email','fixture@users.noreply.github.com')
        descriptor = copy.deepcopy(self.config)
        descriptor['manifest'] = 'src/manifest.json'
        descriptor.pop('version')
        descriptor['artifact'] = 'build/{modId}-hytale{hytaleFamily}-{version}.jar'
        release.write('release.json', descriptor)
        release.write('src/manifest.json', {'Version':'1.3.40', 'ServerVersion':self.config['serverVersion']})
        git('add','release.json','src/manifest.json')
        git('commit','-qm','Fixture')
        original = git('rev-parse','HEAD')
        first = release.stamp(self.plan)
        commit = git('rev-parse','HEAD')
        git('reset','--hard',original)
        second = release.stamp(self.plan)
        self.assertEqual(git('rev-parse','HEAD'),commit)
        self.assertEqual(first,second)
        self.assertEqual(first['version'],'1.3.41')
        self.assertEqual(first['serverVersion'],self.config['serverVersion'])


if __name__ == '__main__':
    unittest.main()
