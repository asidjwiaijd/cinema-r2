"""Install the dedicated Cinema R2 client from Mojang/Fabric official metadata.
Never alters existing modpack files or authentication records.
"""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request

GAME_ROOT = Path(r"E:\PCL 正式版 2.13.0.1\.minecraft")
PROJECT = Path(__file__).resolve().parents[1]
VERSION = "0.4.7"
NAME = "Cinema-R2-26.1.2-Fabric"
INSTANCE = GAME_ROOT / "versions" / NAME
JAVA_SOURCE = PROJECT / ".tools" / "pcl-java25-runtime"
JAVA_HOME = INSTANCE / "jre25"
MPV_LIBRARY = Path(r"E:\mc\runtime\libmpv-20261009\libmpv-2.dll")
ASSETS_SOURCE = Path.home() / ".gradle" / "caches" / "fabric-loom" / "assets"

def json_url(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)

def download(job):
    url, target, sha1 = job
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and (not sha1 or hashlib.sha1(target.read_bytes()).hexdigest() == sha1):
        return
    with urllib.request.urlopen(url, timeout=90) as response:
        data = response.read()
    if sha1 and hashlib.sha1(data).hexdigest() != sha1:
        raise RuntimeError(f"Download checksum mismatch: {target.name}")
    temporary = target.with_name(target.name + ".cinema-download")
    temporary.write_bytes(data)
    temporary.replace(target)

def windows_allowed(library):
    rules = library.get("rules")
    if not rules:
        return True
    allowed = False
    for rule in rules:
        os_rule = rule.get("os", {})
        if os_rule.get("name", "windows") != "windows":
            continue
        if "arch" in os_rule and os_rule["arch"] not in {"x86_64", "amd64"}:
            continue
        allowed = rule["action"] == "allow"
    return allowed

def main():
    if not MPV_LIBRARY.is_file():
        raise RuntimeError("Install libmpv with tools/install_libmpv.ps1 before creating this instance")
    INSTANCE.mkdir(parents=True, exist_ok=True)
    metadata_path = INSTANCE / (NAME + ".json")
    if metadata_path.exists():
        raise RuntimeError("Cinema instance already exists; inspect it before re-running installation")
    manifest = json_url("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
    base = json_url(next(v["url"] for v in manifest["versions"] if v["id"] == "26.1.2"))
    fabric = json_url("https://meta.fabricmc.net/v2/versions/loader/26.1.2/0.19.5/profile/json")
    merged = dict(base)
    merged.update(id=NAME, clientVersion="26.1.2", mainClass=fabric["mainClass"])
    merged["libraries"] = base["libraries"] + fabric["libraries"]
    merged["arguments"] = {kind: base.get("arguments", {}).get(kind, []) + fabric.get("arguments", {}).get(kind, []) for kind in ("game", "jvm")}
    jobs = []
    client = base["downloads"]["client"]
    client_target = INSTANCE / (NAME + ".jar")
    cached_client = Path.home() / ".gradle/caches/fabric-loom/26.1.2/minecraft-client.jar"
    if cached_client.exists() and hashlib.sha1(cached_client.read_bytes()).hexdigest() == client["sha1"]:
        shutil.copy2(cached_client, client_target)
    else:
        jobs.append((client["url"], client_target, client["sha1"]))
    for library in merged["libraries"]:
        if not windows_allowed(library):
            continue
        downloads = library.get("downloads", {})
        artifact = downloads.get("artifact")
        if artifact:
            jobs.append((artifact["url"], GAME_ROOT / "libraries" / artifact["path"], artifact.get("sha1")))
        else:
            coords = library["name"].split(":")
            group, name, version = coords[:3]
            classifier = "-" + coords[3] if len(coords) > 3 else ""
            path = f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}{classifier}.jar"
            jobs.append((library.get("url", "https://libraries.minecraft.net/").rstrip("/") + "/" + path, GAME_ROOT / "libraries" / path, None))
        classifier = library.get("natives", {}).get("windows", "").replace("${arch}", "64")
        native = downloads.get("classifiers", {}).get(classifier)
        if native:
            jobs.append((native["url"], GAME_ROOT / "libraries" / native["path"], native.get("sha1")))
    # Identical library targets are downloaded only once.
    jobs = list({str(job[1]): job for job in jobs}.values())
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(download, jobs))
    print(f"Verified/downloaded {len(jobs)} official runtime artifacts", flush=True)
    index_info=base["assetIndex"]
    index_target=GAME_ROOT / "assets/indexes" / (index_info["id"]+".json")
    download((index_info["url"],index_target,index_info["sha1"]))
    asset_index=json.loads(index_target.read_text(encoding="utf-8"))
    asset_jobs=[]
    for obj in asset_index["objects"].values():
        digest=obj["hash"];target=GAME_ROOT / "assets/objects" / digest[:2] / digest
        if target.is_file() and target.stat().st_size==obj["size"]:continue
        cached=ASSETS_SOURCE / "objects" / digest[:2] / digest
        if cached.is_file() and hashlib.sha1(cached.read_bytes()).hexdigest()==digest:
            target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(cached,target)
        else:asset_jobs.append(("https://resources.download.minecraft.net/"+digest[:2]+"/"+digest,target,digest))
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:list(pool.map(download,asset_jobs))
    print(f"Verified {len(asset_index['objects'])} assets; downloaded {len(asset_jobs)} missing objects",flush=True)
    shutil.copytree(JAVA_SOURCE, JAVA_HOME, dirs_exist_ok=True)
    mods = INSTANCE / "mods"
    mods.mkdir(exist_ok=True)
    shutil.copy2(PROJECT / f"build/libs/cinema-r2-{VERSION}.jar", mods)
    api_url = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.155.3+26.1.2/fabric-api-0.155.3+26.1.2.jar"
    with urllib.request.urlopen(api_url + ".sha1", timeout=60) as response:
        api_sha = response.read().decode().strip()
    download((api_url, mods / "fabric-api-0.155.3+26.1.2.jar", api_sha))
    config = INSTANCE / "config"
    config.mkdir(exist_ok=True)
    native=INSTANCE / "cinema-native/windows-x86_64/libmpv-2.dll"
    native.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(MPV_LIBRARY,native)
    (config / "cinemar2-client.json").write_text(json.dumps({"mpvLibraryPath": "./cinema-native/windows-x86_64/libmpv-2.dll", "volume": 0.7, "audioBoost": 1.5, "diskCache": True, "cacheDiskGB": 8, "cacheMemoryMB": 512, "cacheDirectory": "cinema-cache"}, indent=2), encoding="utf8")
    # Dedicated-server media settings are configured separately by the console bridge.
    if not (config / "cinemar2-server.json").exists():
        (config / "cinemar2-server.json").write_text(json.dumps({"allowedMediaHosts": ["video.mc.cxmlbt-tec.top","live.mc.acgzjd.cn","live.al.mc.acgzjd.cn"], "viewDistance": 256, "protectCinema": False, "managementUrl": "", "managementToken": ""}, indent=2), encoding="utf8")
    pcl = INSTANCE / "PCL"
    pcl.mkdir(exist_ok=True)
    java_bin = str(JAVA_HOME / "bin") + "\\"
    selected_java = {"Path": java_bin, "VersionString": "25.0.4.1", "IsJre": False, "Is64Bit": True, "IsUserImport": True}
    settings = "VersionArgumentIndie:1\nVersionArgumentIndieV2:True\nVersionArgumentTitle:Cinema R2 26.1.2\nVersionArgumentJavaV2:2\n"
    (pcl / "config.json").write_text(json.dumps({"InstanceMigratedJava":True}),encoding="utf-8")
    (pcl / "Setup.ini").write_text(settings, encoding="utf-8-sig")
    metadata_path.write_text(json.dumps(merged, ensure_ascii=False, indent=2), encoding="utf8")
    # Validate all shared hashed asset files referenced by this Minecraft version.
    asset_index = json.loads((GAME_ROOT / "assets/indexes" / (base["assetIndex"]["id"] + ".json")).read_text(encoding="utf8"))
    missing = []
    for obj in asset_index["objects"].values():
        path = GAME_ROOT / "assets/objects" / obj["hash"][:2] / obj["hash"]
        if not path.exists() or path.stat().st_size != obj["size"]:
            missing.append(path.name)
    if missing:
        raise RuntimeError(f"Missing asset objects: {len(missing)}")
    print(f"Installed {NAME}; validated {len(asset_index['objects'])} game assets", flush=True)
    print(f"Instance: {INSTANCE}", flush=True)

if __name__ == "__main__":
    main()
