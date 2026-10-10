"""Install the dedicated Cinema R2 client from Mojang/Fabric official metadata.
Never alters existing modpack files or authentication records.
"""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request

GAME_ROOT = Path(r"C:\Users\cxmnbt\Downloads\Compressed\.minecraft")
PROJECT = Path(__file__).resolve().parents[1]
NAME = "Cinema-R2-1.21.1-Fabric"
INSTANCE = GAME_ROOT / "versions" / NAME
JAVA_SOURCE = PROJECT / ".tools" / "jdk-21.0.12.1+1"
JAVA_HOME = Path(r"E:\mc\runtime\jdk-21")
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
    base = json_url(next(v["url"] for v in manifest["versions"] if v["id"] == "1.21.1"))
    fabric = json_url("https://meta.fabricmc.net/v2/versions/loader/1.21.1/0.16.10/profile/json")
    merged = dict(base)
    merged.update(id=NAME, clientVersion="1.21.1", mainClass=fabric["mainClass"])
    merged["libraries"] = base["libraries"] + fabric["libraries"]
    merged["arguments"] = {kind: base.get("arguments", {}).get(kind, []) + fabric.get("arguments", {}).get(kind, []) for kind in ("game", "jvm")}
    jobs = []
    client = base["downloads"]["client"]
    client_target = INSTANCE / (NAME + ".jar")
    cached_client = Path.home() / ".gradle/caches/fabric-loom/1.21.1/minecraft-client.jar"
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
    if not ASSETS_SOURCE.exists():
        raise RuntimeError("Expected verified Loom assets cache is unavailable")
    shutil.copytree(ASSETS_SOURCE, GAME_ROOT / "assets", dirs_exist_ok=True)
    shutil.copytree(JAVA_SOURCE, JAVA_HOME, dirs_exist_ok=True)
    mods = INSTANCE / "mods"
    mods.mkdir(exist_ok=True)
    shutil.copy2(PROJECT / "dist/cinema-r2-0.3.0.jar", mods)
    api_url = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.116.12+1.21.1/fabric-api-0.116.12+1.21.1.jar"
    with urllib.request.urlopen(api_url + ".sha1", timeout=60) as response:
        api_sha = response.read().decode().strip()
    download((api_url, mods / "fabric-api-0.116.12+1.21.1.jar", api_sha))
    config = INSTANCE / "config"
    config.mkdir(exist_ok=True)
    (config / "cinemar2-client.json").write_text(json.dumps({"mpvLibraryPath": str(MPV_LIBRARY), "volume": 0.7, "diskCache": True, "cacheDiskGB": 8, "cacheMemoryMB": 512, "cacheDirectory": "E:/mc/cinema-cache"}, indent=2), encoding="utf8")
    # Dedicated-server media settings are configured separately by the console bridge.
    if not (config / "cinemar2-server.json").exists():
        (config / "cinemar2-server.json").write_text(json.dumps({"allowedMediaHosts": [], "viewDistance": 64}, indent=2), encoding="utf8")
    pcl = INSTANCE / "PCL"
    pcl.mkdir(exist_ok=True)
    java_bin = str(JAVA_HOME / "bin") + "\\"
    selected_java = {"Path": java_bin, "VersionString": "21.0.12", "IsJre": False, "Is64Bit": True, "IsUserImport": True}
    settings = "VersionArgumentIndie:1\nVersionArgumentIndieV2:True\nVersionArgumentTitle:Cinema R2\nVersionArgumentJavaSelect:" + json.dumps(selected_java, ensure_ascii=False) + "\n"
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
