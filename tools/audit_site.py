#!/usr/bin/env python3
"""Validate every built docmd version, local link, image, script and fragment."""
from html.parser import HTMLParser
import json
from pathlib import Path
import re
from urllib.parse import unquote, urljoin, urlsplit

class Page(HTMLParser):
    def __init__(self):
        super().__init__(); self.refs = []; self.ids = set()
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if attrs.get('id'): self.ids.add(attrs['id'])
        if tag == 'a' and attrs.get('name'): self.ids.add(attrs['name'])
        value = attrs.get('href') if tag in ('a', 'link') else attrs.get('src') if tag in ('img', 'script', 'source') else None
        if value: self.refs.append((tag, value))

config = json.loads(Path('docmd.config.json').read_text())
registry = json.loads(Path('versions.json').read_text())
site = Path(config['out']).resolve()
base = config['url'].rstrip('/') + '/'
base_url = urlsplit(base)
versions = {v['id'] for v in config['versions']['all']} - {'index'}
assert versions == set(registry['families']) and registry['latest'] in versions, 'Version index/configuration disagree'
assert config['versions']['current'] == 'index', 'The root must be the version index'
for version in versions:
    assert re.fullmatch(r'hytale\d+\.\d+', version)
    assert registry['families'][version]['sourceBranch'] == version
    assert (site / version / 'index.html').is_file(), 'Missing version guide: ' + version
# A guide describes a game support line, independently of project releases.
for family, record in registry['families'].items():
    forbidden = {'modversion', 'releaseversion', 'releasetag', 'artifact', 'jar', 'sourcecommit'}
    assert not forbidden.intersection(re.sub(r'[^a-z]', '', key.lower()) for key in record), 'Release-pinned guide registry: ' + family
project = re.sub(r'[^a-z0-9]', '', config['title'].lower())
source_pages = [Path('README.md'), *Path('docs').rglob('*.md'), *Path('versions').rglob('*.md')]
for path in source_pages:
    if not path.is_file(): continue
    content = path.read_text()
    for match in re.finditer(r'https://github\.com/[^/\s)]+/([^/\s)]+)/releases/(?:tag|download)/[^\s)]+', content):
        assert re.sub(r'[^a-z0-9]', '', match[1].lower()) != project, 'Release-pinned download in ' + str(path)
    for match in re.finditer(r'[A-Za-z][A-Za-z0-9_.<>-]*\.jar\b', content):
        assert not re.sub(r'[^a-z0-9]', '', match[0].lower()).startswith(project), 'Project JAR pin in ' + str(path)
    title_words = [re.escape(word) for word in re.findall(r'[A-Za-z0-9]+', config['title'])]
    named_version = r'\s*'.join(title_words) + r'[\s*_`]+v?\d+\.\d+\.\d+'
    assert not re.search(named_version, content, re.I), 'Project-version label in ' + str(path)
pages = {}
for path in site.rglob('*.html'):
    page = Page(); page.feed(path.read_text()); pages[path.resolve()] = page
errors = []
for path, page in pages.items():
    relative = path.relative_to(site).as_posix()
    for tag, reference in page.refs:
        if reference.startswith(('mailto:', 'data:', 'javascript:', 'tel:')): continue
        url = urlsplit(urljoin(base + relative, reference))
        if url.netloc != base_url.netloc: continue
        if not url.path.startswith(base_url.path):
            errors.append((relative, reference, 'outside project base')); continue
        target = site / unquote(url.path[len(base_url.path):])
        if target.is_dir(): target /= 'index.html'
        if not target.is_file():
            errors.append((relative, reference, 'missing file')); continue
        if tag == 'a' and url.fragment and target.resolve() in pages and unquote(url.fragment) not in pages[target.resolve()].ids:
            errors.append((relative, reference, 'missing fragment'))
for path in site.rglob('*'):
    if path.is_file() and path.suffix in ('.html', '.json', '.js', '.css', '.txt', '.xml', '.md'):
        data = path.read_bytes()
        assert not re.search(rb'/(?:Users|home)/[^/\s]+/|/private/tmp/', data), 'Local build path leaked: ' + str(path.relative_to(site))
assert not errors, json.dumps(errors, indent=2)
print(f"Verified {len(pages)} HTML pages across {len(versions)} Hytale families; links, assets and fragments resolve; guide release-pin checks pass.")
