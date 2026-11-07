"""Fetch pinned upstream archives, verify hashes, and extract to a new build workspace."""
import argparse
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--cache', type=Path, required=True)
parser.add_argument('--work', type=Path, required=True)
args = parser.parse_args()
lock = json.loads(Path(__file__).with_name('sources.lock.json').read_text(encoding='utf-8'))
args.cache.mkdir(parents=True, exist_ok=True)
(args.work / 'src').mkdir(parents=True, exist_ok=True)
for source in lock['sources']:
    archive = args.cache / source['archive']
    if not archive.exists():
        with urllib.request.urlopen(source['url'], timeout=120) as response:
            archive.write_bytes(response.read())
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != source['sha256']:
        raise RuntimeError('Archive checksum differs: ' + source['name'])
    target = args.work / 'src' / source['name']
    marker = target / '.cinema-source-sha256'
    if target.exists():
        # Allow a manually extracted original archive on the first build only.
        if marker.exists() and marker.read_text().strip() != digest:
            raise RuntimeError('Use a new workspace for changed sources: ' + source['name'])
    else:
        with tarfile.open(archive) as bundle:
            top = bundle.getmembers()[0].name.split('/')[0]
            bundle.extractall(args.work / 'src', filter='data')
        (args.work / 'src' / top).rename(target)
    marker.write_text(digest + '\n')
    print('Verified ' + source['name'], flush=True)
