"""Launch only the new Cinema instance with a disposable offline test identity.
No launcher account record or authentication credential is read.
"""
import json
from pathlib import Path
import subprocess
import time
import zipfile
from install_local_client import GAME_ROOT, NAME, INSTANCE, JAVA_HOME, windows_allowed

def main():
    metadata = json.loads((INSTANCE / (NAME + ".json")).read_text(encoding="utf8"))
    classpath = [INSTANCE / (NAME + ".jar")]
    natives = INSTANCE / "natives-cinema-test"
    natives.mkdir(exist_ok=True)
    for library in metadata["libraries"]:
        if not windows_allowed(library):
            continue
        downloads = library.get("downloads", {})
        artifact = downloads.get("artifact")
        if artifact:
            jar = GAME_ROOT / "libraries" / artifact["path"]
        else:
            coords = library["name"].split(":")
            group, name, version = coords[:3]
            classifier = "-" + coords[3] if len(coords) > 3 else ""
            jar = GAME_ROOT / "libraries" / f"{group.replace('.', '/')}/{name}/{version}/{name}-{version}{classifier}.jar"
        if not jar.exists():
            raise RuntimeError(f"Missing runtime library: {jar.name}")
        classpath.append(jar)
        with zipfile.ZipFile(jar) as archive:
            for member in archive.infolist():
                if member.filename.lower().endswith(".dll"):
                    (natives / Path(member.filename).name).write_bytes(archive.read(member))
    replacements = {
        "auth_player_name": "CinemaTest", "version_name": NAME,
        "game_directory": str(INSTANCE), "assets_root": str(GAME_ROOT / "assets"),
        "assets_index_name": metadata["assetIndex"]["id"],
        "auth_uuid": "12345678123412341234123456781234", "auth_access_token": "0",
        "clientid": "", "auth_xuid": "", "user_type": "legacy", "version_type": "release",
    }
    arguments = []
    for arg in metadata["arguments"]["game"]:
        if not isinstance(arg, str):
            continue  # Optional demo/quick-play arguments are not used for a smoke test.
        for key, value in replacements.items():
            arg = arg.replace("${" + key + "}", value)
        if "${" in arg:
            raise RuntimeError("Unresolved launcher argument")
        arguments.append(arg)
    command = [str(JAVA_HOME / "bin/java.exe"), "-Xmx2G", "-Djava.library.path=" + str(natives), "-Dminecraft.launcher.brand=cinema-r2-smoke", "-Dminecraft.launcher.version=0.3.0", "-cp", ";".join(map(str, classpath)), metadata["mainClass"], *arguments]
    log = INSTANCE / "cinema-startup-test.log"
    with log.open("w", encoding="utf8") as output:
        process = subprocess.Popen(command, cwd=INSTANCE, stdout=output, stderr=subprocess.STDOUT)
        try:
            deadline = time.time() + 90
            while time.time() < deadline and process.poll() is None:
                text = log.read_text(encoding="utf8", errors="replace")
                if "cinemar2 0.3.0" in text and "minecraft:textures/atlas/gui.png-atlas" in text:
                    print("Production instance loaded Cinema R2 and completed graphics/assets/audio initialization", flush=True)
                    return
                time.sleep(1)
            raise RuntimeError("Production instance startup did not complete; inspect cinema-startup-test.log")
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()

if __name__ == "__main__":
    main()
