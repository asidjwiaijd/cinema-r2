"""Build a portable PCL-compatible CurseForge-format client ZIP from explicit inputs.

No launcher account, existing game config, server credential, world, or media is read.
Minecraft and Fabric Loader are installed by the launcher; Java 25 is bundled locally.
"""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import urllib.request
import zipfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.4.7"
PACK_VERSION = VERSION + "-pcl.1"
MPV_SHA256 = "a3708b0be3d3b5d8f1fb4c2e2a43346356471a78060e598dab774d00e8cc05f3"
MPV_ARCHIVE_SHA256 = "8a15fc07996a33235778256157151c42cbaceee976f0a98860f55047856935b2"
MPV_RELEASE = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/tag/20261009"
NATIVE_PATH = "cinema-native/windows-x86_64/libmpv-2.dll"
SOURCES = {
    "mpv-Copyright.txt": "https://raw.githubusercontent.com/mpv-player/mpv/36abaa32d0/Copyright",
    "mpv-LICENSE.GPL.txt": "https://raw.githubusercontent.com/mpv-player/mpv/36abaa32d0/LICENSE.GPL",
    "mpv-LICENSE.LGPL.txt": "https://raw.githubusercontent.com/mpv-player/mpv/36abaa32d0/LICENSE.LGPL",
    "FFmpeg-LICENSE.md": "https://raw.githubusercontent.com/FFmpeg/FFmpeg/538d10d18/LICENSE.md",
    "FFmpeg-COPYING.GPLv2.txt": "https://raw.githubusercontent.com/FFmpeg/FFmpeg/538d10d18/COPYING.GPLv2",
    "FFmpeg-COPYING.GPLv3.txt": "https://raw.githubusercontent.com/FFmpeg/FFmpeg/538d10d18/COPYING.GPLv3",
    "FFmpeg-COPYING.LGPLv2.1.txt": "https://raw.githubusercontent.com/FFmpeg/FFmpeg/538d10d18/COPYING.LGPLv2.1",
}


def server_list(address, name):
    """Build a fresh Java-edition servers.dat containing only explicit public metadata."""
    if not address or any(c.isspace() or c in "/?#@\\" for c in address):
        raise ValueError("Use a Minecraft host[:port], not a website URL")
    parsed = urlsplit("//" + address)
    if not parsed.hostname or parsed.port is not None and not 1 <= parsed.port <= 65535:
        raise ValueError("Invalid Minecraft server address")
    def string(value):
        # NBT uses Java modified UTF-8; encode UTF-16 surrogate code units separately.
        encoded = b"".join(b"\xc0\x80" if part == 0 else chr(part).encode("utf-8", "surrogatepass")
                           for part, in struct.iter_unpack(">H", value.encode("utf-16-be")))
        if len(encoded) > 65535:
            raise ValueError("Server metadata is too long")
        return struct.pack(">H", len(encoded)) + encoded
    entry = b"\x08" + string("name") + string(name) + b"\x08" + string("ip") + string(address) + b"\x00"
    return b"\x0a\x00\x00\x09" + string("servers") + b"\x0a\x00\x00\x00\x01" + entry + b"\x00"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mpv-library", type=Path, required=True)
    parser.add_argument("--fabric-api", type=Path, required=True)
    parser.add_argument("--java-runtime", type=Path, required=True,
                        help="Windows x64 Java 25 runtime with bin, conf, lib, legal and release")
    parser.add_argument("--mod-jar", type=Path, default=ROOT / f"build/libs/cinema-r2-{VERSION}.jar")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--native-manifest", type=Path, help="Verified cinema-minimal build's native-build.json")
    parser.add_argument("--server-address", help="Prepopulate one Minecraft host[:port] in the multiplayer list")
    parser.add_argument("--server-name", default="Cinema R2 影院", help="Display name for the preconfigured server")
    parser.add_argument("--extra-mod", type=Path, action="append", default=[], help="Include an explicitly selected client mod JAR")
    parser.add_argument("--license-cache", type=Path, default=ROOT / ".tools/pcl-license-cache")
    args = parser.parse_args()
    args.output = args.output or ROOT / f"dist/Cinema-R2-{VERSION}-MC26.1.2-PCL-Windows-x64-Java25{'-Slim' if args.native_manifest else ''}.zip"
    java_release = (args.java_runtime / "release").read_text(encoding="utf-8")
    if 'JAVA_VERSION="25.' not in java_release:
        raise ValueError("Bundled Java runtime must be Java 25")
    java_version = subprocess.check_output([str(args.java_runtime.resolve() / "bin/java.exe"), "-version"],
                                           stderr=subprocess.STDOUT, text=True)
    if 'version "25.' not in java_version or "64-Bit" not in java_version:
        raise ValueError("Java 25 x64 runtime failed its version check")
    dll = args.mpv_library.read_bytes()
    native_manifest = None
    native_files = {}
    if args.native_manifest:
        native_manifest = json.loads(args.native_manifest.read_text(encoding="utf-8"))
        if native_manifest["profile"] != "cinema-minimal-20261022" or native_manifest["target"] != "windows-x86_64":
            raise ValueError("Unsupported custom native profile")
        locked = json.loads((ROOT / "tools/native/sources.lock.json").read_text(encoding="utf-8"))
        if native_manifest["sources"] != locked["sources"]:
            raise ValueError("Native source inventory differs from the pinned recipe")
        patches = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT / "tools/native").glob("*.patch"))}
        if native_manifest["patches"] != patches:
            raise ValueError("Native patch inventory differs from the pinned recipe")
        if hashlib.sha256(dll).hexdigest() != native_manifest["dllSha256"]:
            raise ValueError("Custom libmpv DLL checksum differs from its build inventory")
        if native_manifest["enabledEncoders"] or native_manifest["mpvOptions"]["wasapi"] != "enabled":
            raise ValueError("Custom build does not match the tested decoder-only profile")
        native_root = args.native_manifest.parent.resolve()
        for name, checksum in native_manifest["licenseFiles"].items():
            path = (native_root / name).resolve()
            if not path.is_relative_to(native_root) or ".." in Path(name).parts:
                raise ValueError("Invalid native license path")
            value = path.read_bytes()
            if hashlib.sha256(value).hexdigest() != checksum:
                raise ValueError("Native license inventory mismatch: " + name)
            native_files["overrides/cinema-licenses/native/" + name] = value
        for name in ["native-build.json", "ffmpeg-config.txt", "mpv-build-options.json"]:
            native_files["overrides/cinema-licenses/native/" + name] = (native_root / name).read_bytes()
    elif hashlib.sha256(dll).hexdigest() != MPV_SHA256:
        raise ValueError("libmpv DLL differs from the verified 20261009 Windows x64 build")
    pe = struct.unpack_from("<I", dll, 0x3C)[0]
    if dll[pe:pe + 4] != b"PE\0\0" or struct.unpack_from("<H", dll, pe + 4)[0] != 0x8664:
        raise ValueError("libmpv must be a Windows x86-64 PE library")
    with zipfile.ZipFile(args.mod_jar) as z:
        mod = json.loads(z.read("fabric.mod.json"))
        assert mod["id"] == "cinemar2" and mod["version"] == VERSION
        assert "META-INF/jars/jna-5.15.0.jar" in z.namelist()
    with zipfile.ZipFile(args.fabric_api) as z:
        fabric = json.loads(z.read("fabric.mod.json"))
        assert fabric["id"] == "fabric-api" and fabric["version"] == "0.155.3+26.1.2"
        fabric_license = z.read("LICENSE-fabric-api")
    args.license_cache.mkdir(parents=True, exist_ok=True)

    def license_file(item):
        name, url = item
        path = args.license_cache / name
        if not path.exists():
            with urllib.request.urlopen(url, timeout=30) as response:
                value = response.read()
            if len(value) < 100 or b"<html" in value[:100].lower():
                raise ValueError("Invalid upstream license response: " + name)
            path.write_bytes(value)
        return name, path.read_bytes()

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        licenses = dict(pool.map(license_file, SOURCES.items()))
    manifest = {
        "minecraft": {"version": "26.1.2", "modLoaders": [{"id": "fabric-0.19.5", "primary": True}]},
        "manifestType": "minecraftModpack", "manifestVersion": 1,
        "name": f"Cinema R2 {VERSION} MC26.1.2 Windows x64" + (" Slim" if native_manifest else ""), "version": VERSION + "-pcl.2" if native_manifest else PACK_VERSION, "author": "Cinema R2 contributors",
        "files": [], "overrides": "overrides",
    }
    client = {
        "mpvLibraryPath": "./" + NATIVE_PATH, "volume": 0.7, "audioBoost": 1.5,
        "cacheMemoryMB": 512, "cacheDiskGB": 8, "diskCache": True,
        "cacheDirectory": "cinema-cache", "segmentCacheMB": 2048,
        "tlsCaFile": "", "r2Failover": True,
        "primaryMediaBase": "https://video.mc.cxmlbt-tec.top",
        "cdnMediaBase": "https://live.mc.acgzjd.cn", "backupMediaBase": "https://live.al.mc.acgzjd.cn",
        "cdnTimeoutSeconds": 6, "primaryTimeoutSeconds": 6,
    }
    server = {
        "allowedMediaHosts": ["video.mc.cxmlbt-tec.top", "live.mc.acgzjd.cn", "live.al.mc.acgzjd.cn"],
        "viewDistance": 256, "managementUrl": "", "managementFallbackUrl": "", "managementToken": "",
        "serverId": "client-local", "allowLocalMedia": False, "protectCinema": False,
    }
    readme = f"""Cinema R2 {VERSION} · PCL 客户端整合包修订 1 · Windows x64 · 内置 Java 25

导入：PCL → 版本选择 → 导入整合包，选择本 ZIP（无需解压），建议安装成新的隔离实例。
等待 PCL 安装 Minecraft 26.1.2 与 Fabric Loader 0.19.5，即可启动。
Java 25 x64 已放在实例内 jre25；本版本明确设置“使用版本文件夹中的 Java”，避免误用旧版 Java 21。
不要删除 jre25。若更换启动器，选择该目录的 bin/java.exe；不要改用 Java 21。

已包含：Cinema {VERSION}、Fabric API 0.155.3+26.1.2、JNA（模组内嵌）、libmpv Windows x64、Java 25 x64。
播放器 DLL 和缓存使用实例内相对路径，无需另外安装 FFmpeg、mpv.exe 或填写 E:/mc 路径。
请保留版本隔离；不要把 DLL 单独移出 cinema-native/windows-x86_64。

多人游戏：向服主取得 MC 游戏地址，在多人游戏中添加。网站/CDN 域名不是 MC 进服地址。
没有预置服务器地址，避免将本机回环地址分发给其他玩家。
媒体配置默认连接现有影院 R2/EO/阿里云域名；连接其他影院时按服主要求修改。

本包不含账号、密码、桥接令牌、R2 凭据、世界存档、影片或 Minecraft 游戏文件。
本地单人模式默认关闭影院保护和网页桥，不会自动改造原地图；远端服务器规则由服主决定。
缓存随观看可能占用数 GB；保留足够磁盘空间。首播网络或服务端不可达仍会影响播放。

本整合包仅验证 Windows x64，不用于 macOS、Linux 或 Windows ARM。
第三方许可及上游源码/构建来源见 cinema-licenses/，Cinema 代码采用 MIT，原生库保持其上游许可。
源码及完整说明：https://github.com/asidjwiaijd/cinema-r2
"""
    provenance = {
        "mpv": {"release": MPV_RELEASE, "commit": "36abaa32d0", "dllSha256": MPV_SHA256,
                "archiveSha256": MPV_ARCHIVE_SHA256, "modified": False,
                "source": "https://github.com/mpv-player/mpv/tree/36abaa32d0"},
        "ffmpeg": {"source": "https://github.com/FFmpeg/FFmpeg/tree/538d10d18"},
        "nativeBuildRecipes": "https://github.com/shinchiro/mpv-winbuild-cmake/tree/b2856ee6deed6957380b32465f359e67f67fafd8",
        "fabricApi": {"version": fabric["version"], "license": fabric["license"],
                      "source": "https://github.com/FabricMC/fabric/tree/0.155.3+26.1.2"},
        "cinema": {"version": VERSION, "source": "https://github.com/asidjwiaijd/cinema-r2/tree/main"},
        "java": {"versionOutput": java_version.strip(), "packagedWithJlink": False,
                 "source": "https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.4.1%2B1",
                 "licenses": "jre25/legal/"},
        "note": "This is an unmodified upstream native binary, not an MIT-only runtime. Follow upstream component licenses for redistribution.",
    }
    if native_manifest:
        readme = readme.replace("客户端整合包修订 1", "客户端整合包修订 2 · 精简原生库")
        readme += ("\n本包使用 Cinema 自行编译的精简 libmpv；Java 25、媒体缓存和模组版本保持不变。\n"
                   "保留常见视频/音频、HLS/HTTPS、WASAPI 和 D3D11/DXVA2 解码，移除编码、脚本、光盘和多余输出。\n"
                   "再次分发时请同时提供 " + native_manifest["correspondingSource"] + "，并保留 cinema-licenses/。\n")
        provenance["mpv"] = {"commit": "36abaa32d0", "dllSha256": native_manifest["dllSha256"],
                             "source": "https://github.com/mpv-player/mpv/tree/36abaa32d0", "customBuild": True,
                             "upstreamSourceModified": native_manifest["upstreamSourceModified"], "profile": native_manifest["profile"]}
        provenance["nativeBuildRecipes"] = "tools/native/build-libmpv-minimal.sh (included in corresponding-source ZIP)"
        provenance["nativeRuntime"] = native_manifest
        provenance["note"] = "Custom decoder-only native build. Distribute its corresponding-source ZIP and preserve component licenses."
    files = {
        "manifest.json": json.dumps(manifest, ensure_ascii=False, indent=2).encode(),
        "README-导入说明.txt": readme.encode("utf-8-sig"),
        "overrides/README-Cinema.txt": readme.encode("utf-8-sig"),
        f"overrides/mods/cinema-r2-{VERSION}.jar": args.mod_jar.read_bytes(),
        "overrides/mods/fabric-api-0.155.3+26.1.2.jar": args.fabric_api.read_bytes(),
        "overrides/" + NATIVE_PATH: dll,
        "overrides/config/cinemar2-client.json": json.dumps(client, indent=2).encode(),
        "overrides/config/cinemar2-server.json": json.dumps(server, indent=2).encode(),
        "overrides/PCL/Setup.ini": (
            "VersionArgumentIndie:1\nVersionArgumentIndieV2:True\nVersionArgumentTitle:Cinema R2\n"
            "VersionArgumentJavaV2:2\n"
        ).encode("utf-8-sig"),
        "overrides/PCL/config.json": json.dumps({"InstanceMigratedJava": True}, indent=2).encode(),
        "overrides/cinema-licenses/Cinema-LICENSE.txt": (ROOT / "LICENSE").read_bytes(),
        "overrides/cinema-licenses/Fabric-API-LICENSE.txt": fabric_license,
        "overrides/cinema-licenses/THIRD_PARTY.md": (ROOT / "THIRD_PARTY.md").read_bytes(),
        "overrides/cinema-licenses/SOURCES.json": json.dumps(provenance, ensure_ascii=False, indent=2).encode(),
    }
    extra_info = []
    for path in args.extra_mod:
        name = "overrides/mods/" + path.name
        if path.suffix.lower() != ".jar" or name.lower() in {key.lower() for key in files}:
            raise ValueError("Invalid or duplicate extra mod: " + path.name)
        value = path.read_bytes()
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise ValueError("Damaged extra mod: " + path.name)
            metadata = json.loads(archive.read("fabric.mod.json"))
        files[name] = value
        extra_info.append({"file": path.name, "id": metadata["id"], "version": metadata["version"],
                           "name": metadata.get("name", metadata["id"]), "license": metadata.get("license"),
                           "contact": metadata.get("contact", {})})
    if extra_info:
        files["overrides/cinema-licenses/EXTRA-MODS.json"] = json.dumps(extra_info, ensure_ascii=False, indent=2).encode()
        readme += "\n附带当前实例的客户端模组：\n" + "".join(f"- {m['name']} {m['version']}\n" for m in extra_info)
        readme += "保留原始 JAR 内的许可证；元数据与项目链接见 cinema-licenses/EXTRA-MODS.json。\n"
        files["README-导入说明.txt"] = files["overrides/README-Cinema.txt"] = readme.encode("utf-8-sig")
    if args.server_address:
        address = args.server_address.strip()
        files["overrides/servers.dat"] = server_list(address, args.server_name)
        manifest["name"] += " · 影院服务器"
        manifest["version"] += "-server.1"
        files["manifest.json"] = json.dumps(manifest, ensure_ascii=False, indent=2).encode()
        readme = readme.replace(
            "多人游戏：向服主取得 MC 游戏地址，在多人游戏中添加。网站/CDN 域名不是 MC 进服地址。\n没有预置服务器地址，避免将本机回环地址分发给其他玩家。",
            f"多人游戏：已预置「{args.server_name}」，地址 {address}。启动游戏后打开多人游戏列表进入即可。\n服务器资源包是否下载仍由玩家自行确认。")
        files["README-导入说明.txt"] = files["overrides/README-Cinema.txt"] = readme.encode("utf-8-sig")
    files.update(native_files)
    for name, value in licenses.items():
        files["overrides/cinema-licenses/" + name] = value
    for path in sorted(args.java_runtime.rglob("*")):
        if path.is_file():
            rel = path.relative_to(args.java_runtime)
            if rel.parts[0] not in {"bin", "conf", "lib", "legal", "release", "NOTICE"}:
                raise ValueError("Unexpected Java runtime file: " + str(rel))
            files["overrides/jre25/" + rel.as_posix()] = path.read_bytes()
    inventory = {name: {"bytes": len(value), "sha256": hashlib.sha256(value).hexdigest()}
                 for name, value in files.items()}
    files["pack-files.sha256.json"] = json.dumps(inventory, ensure_ascii=False, indent=2).encode()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for name, value in sorted(files.items()):
            assert not name.startswith("/") and ".." not in Path(name).parts
            z.writestr(name, value)
    with zipfile.ZipFile(args.output) as z:
        assert z.testzip() is None
        for name, info in inventory.items():
            assert hashlib.sha256(z.read(name)).hexdigest() == info["sha256"]
    digest = hashlib.sha256(args.output.read_bytes()).hexdigest()
    args.output.with_suffix(".zip.sha256").write_text(digest + "  " + args.output.name + "\n", encoding="utf-8")
    print(json.dumps({"pack": str(args.output), "bytes": args.output.stat().st_size,
                      "sha256": digest, "files": len(files)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
