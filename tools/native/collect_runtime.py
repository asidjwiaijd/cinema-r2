"""Collect a completed WSL cross-build, notices, feature inventory and corresponding source."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

recipe = Path(__file__).resolve().parent
repo = recipe.parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--work', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--source-output', type=Path, required=True)
args = parser.parse_args()
work, out = args.work.resolve(), args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
dll = work / 'output/libmpv-2.dll'
imports = re.findall(r'DLL Name:\s*(\S+)', (work / 'output/imports.txt').read_text())
assert not any(re.match(r'lib(gcc|stdc\+\+|winpthread|ssp)|av(codec|format|util)|sw(scale|resample)', name, re.I) for name in imports), imports
shutil.copy2(dll, out / dll.name)
lock = json.loads((recipe / 'sources.lock.json').read_text())
notices = {
    'mpv': ['Copyright', 'LICENSE.GPL', 'LICENSE.LGPL'],
    'ffmpeg': ['LICENSE.md', 'COPYING.GPLv2', 'COPYING.GPLv3', 'COPYING.LGPLv2.1', 'COPYING.LGPLv3'],
    'libplacebo': ['LICENSE'], 'libass': ['COPYING'], 'harfbuzz': ['COPYING', 'AUTHORS'],
    'freetype': ['LICENSE.TXT', 'docs/FTL.TXT', 'docs/GPLv2.TXT'],
    'fribidi': ['COPYING', 'AUTHORS'], 'dav1d': ['COPYING'],
    'fast_float': ['LICENSE-APACHE', 'LICENSE-BOOST', 'LICENSE-MIT', 'AUTHORS'],
    'zlib': ['LICENSE'],
    'vulkan-headers': ['LICENSE.md', 'LICENSES/Apache-2.0.txt', 'LICENSES/MIT.txt'],
    'openssl': ['LICENSE.txt', 'AUTHORS.md'],
}
for component, files in notices.items():
    for name in files:
        target = out / 'licenses' / component / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(work / 'src' / component / name, target)
for component, path in [('mingw-w64', '/usr/share/doc/mingw-w64-common/copyright'), ('gcc-runtime', '/usr/share/doc/gcc-mingw-w64-base/copyright')]:
    target = out / 'licenses' / (component + '.txt')
    shutil.copy2(path, target)
(out / 'licenses/ACKNOWLEDGEMENTS.txt').write_text(
    'This software is based in part on the work of the FreeType Team.\n'
    'Cinema libmpv is a custom build. See the mpv/FFmpeg *.patch files in its corresponding source.\n'
    'All dependencies retain their own licenses; Cinema MIT does not relicense this DLL.\n'
    'Distribute the corresponding-source ZIP alongside the native binary or client pack.\n')

components = (work / 'build/ffmpeg/config_components.h').read_text()
enabled = lambda suffix: sorted(re.findall(r'^#define CONFIG_(\w+)_' + suffix + r' 1$', components, re.M))
config = (work / 'build/ffmpeg/config.h').read_text()
ffmpeg_options = re.search(r'^#define FFMPEG_CONFIGURATION "(.*)"$', config, re.M).group(1)
mpv_options = subprocess.check_output(['python3', '-m', 'mesonbuild.mesonmain', 'introspect', '--buildoptions', str(work / 'build/mpv')], text=True)
(out / 'ffmpeg-config.txt').write_text(ffmpeg_options.replace(str(work), '$BUILD_ROOT') + '\n')
(out / 'mpv-build-options.json').write_text(mpv_options.replace(str(work), '$BUILD_ROOT') + '\n')
source_files = [*recipe.glob('*.py'), *recipe.glob('*.sh'), *recipe.glob('*.patch'), recipe / 'sources.lock.json', recipe / 'README.zh-CN.md']
args.source_output.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(args.source_output, 'w', zipfile.ZIP_STORED) as bundle:
    for source in lock['sources']:
        archive = repo / '.tools/native-build/archives' / source['archive']
        assert hashlib.sha256(archive.read_bytes()).hexdigest() == source['sha256']
        bundle.write(archive, 'archives/' + archive.name)
    for source in source_files:
        bundle.write(source, 'tools/native/' + source.name)
    for path in sorted((out / 'licenses').rglob('*')):
        if path.is_file(): bundle.write(path, 'licenses/' + path.relative_to(out / 'licenses').as_posix())

manifest = {
    'profile': 'cinema-minimal-20261022', 'target': 'windows-x86_64', 'minimumWindows': '10',
    'dllSha256': hashlib.sha256(dll.read_bytes()).hexdigest(), 'dllBytes': dll.stat().st_size,
    'originalDllBytes': 120926720,
    'imports': imports,
    'sources': lock['sources'],
    'recipes': 'tools/native/build-libmpv-minimal.sh',
    'upstreamSourceModified': True,
    'patches': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(recipe.glob('*.patch'))},
    'correspondingSource': args.source_output.name,
    'sourceBundleSha256': hashlib.sha256(args.source_output.read_bytes()).hexdigest(),
    'compiler': subprocess.check_output(['x86_64-w64-mingw32-gcc', '--version'], text=True).splitlines()[0],
    'cpu': 'x86-64 baseline with runtime SIMD detection',
    'enabledDecoders': enabled('DECODER'), 'enabledDemuxers': enabled('DEMUXER'),
    'enabledProtocols': enabled('PROTOCOL'), 'enabledHardwareAccelerators': enabled('HWACCEL'),
    'enabledEncoders': enabled('ENCODER'), 'enabledFilters': enabled('FILTER'),
    'mpvOptions': {option['name']: option['value'] for option in json.loads(mpv_options) if option['section'] == 'user'},
    'licenseFiles': {path.relative_to(out).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                     for path in sorted((out / 'licenses').rglob('*')) if path.is_file()},
}
assert not manifest['enabledEncoders']
for item in ['H264', 'HEVC', 'VP9', 'LIBDAV1D', 'AAC', 'MP3', 'FLAC', 'OPUS']:
    assert item in manifest['enabledDecoders'], item
for item in ['HTTPS', 'HTTP', 'TLS', 'CRYPTO']:
    assert item in manifest['enabledProtocols'], item
assert manifest['mpvOptions']['wasapi'] == 'enabled'
assert manifest['mpvOptions']['cplayer'] is False
(out / 'native-build.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps({'bytes': manifest['dllBytes'], 'reductionPercent': round(100 * (1 - manifest['dllBytes'] / manifest['originalDllBytes']), 1), 'sha256': manifest['dllSha256'], 'imports': imports}))
