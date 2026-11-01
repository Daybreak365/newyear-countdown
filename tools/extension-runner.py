"""Run one generated extension in a disposable, network-disabled Java 25 Docker server.

The operator supplies a prepared Fabric server kit (server.jar, libraries, mods,
and an already accepted eula.txt) and a locally installed Java 25 container image.
The kit must include this mod and matching Fabric API. No production world is copied.
"""
import argparse
import hashlib
import json
import pathlib
import subprocess
import tempfile
import uuid


def run(args):
    kit = pathlib.Path(args.kit).resolve(strict=True)
    artifact = pathlib.Path(args.jar).resolve(strict=True)
    report = pathlib.Path(args.report).resolve()
    if not (kit / "server.jar").is_file():
        raise ValueError("Kit needs a Fabric server launcher named server.jar")
    allowed = {"server.jar", "eula.txt", "libraries", "mods", ".fabric", "versions", "fabric-server-launcher.properties"}
    if any(path.name not in allowed or path.is_symlink() for path in kit.iterdir()):
        raise ValueError("Use a dedicated test kit containing only bootstrap files, libraries and mods; no worlds, accounts or config")
    if "eula=true" not in (kit / "eula.txt").read_text(encoding="utf-8").lower().replace(" ", ""):
        raise ValueError("The operator must accept Minecraft's EULA in the test kit first")
    if hashlib.sha256(artifact.read_bytes()).hexdigest() != args.hash:
        raise ValueError("Artifact does not match requested SHA-256")
    name = "wishai-test-" + uuid.uuid4().hex[:12]
    with tempfile.TemporaryDirectory(prefix="wishai-report-") as temp:
        output = pathlib.Path(temp)
        output_file = output / "result.json"
        output_file.write_text("{}", encoding="utf-8")
        # Fixed shell program; all external values are separate positional arguments.
        program = """
set -eu
cp /kit/server.jar /work/server.jar
cp /kit/eula.txt /work/eula.txt
for entry in libraries mods .fabric versions fabric-server-launcher.properties; do if [ -e /kit/$entry ]; then cp -a /kit/$entry /work/$entry; fi; done
mkdir -p /work/mods /work/config
printf '%s\\n' 'online-mode=false' 'server-ip=127.0.0.1' 'server-port=25565' 'view-distance=2' 'simulation-distance=2' 'level-type=minecraft:flat' > /work/server.properties
printf '%s\\n' '{"enabled":true,"autonomyEnabled":false}' > /work/config/newyearcountdown-ai.json
if [ "$2" = true ]; then cp /artifact/extension.jar /work/mods/wishai-test-extension.jar; fi
ulimit -f 32768
exec java -Xms256m -Xmx1024m -Dwishai.integration.jar=/artifact/extension.jar -Dwishai.integration.class="$1" -Dwishai.integration.report=/report/result.json -jar /work/server.jar nogui
"""
        command = ["docker", "run", "--rm", "--pull=never", "--name", name,
                   "--network=none", "--read-only", "--cap-drop=ALL",
                   "--security-opt=no-new-privileges", "--pids-limit=128", "--memory=2g", "--cpus=2",
                   "--tmpfs=/tmp:rw,nosuid,size=128m", "--tmpfs=/work:rw,nosuid,size=2g",
                   "--mount", f"type=bind,source={kit},target=/kit,readonly",
                   "--mount", f"type=bind,source={artifact},target=/artifact/extension.jar,readonly",
                   "--mount", f"type=bind,source={output_file},target=/report/result.json",
                   "--workdir=/work", "--entrypoint=sh", args.image, "-c", program, "runner", args.class_name, args.restart]
        try:
            subprocess.run(command, timeout=160, check=True)
            if output_file.stat().st_size > 131072:
                raise ValueError("Integration report exceeds 128 KiB")
            result = json.loads(output_file.read_text(encoding="utf-8"))
            if result.get("sha256") != args.hash or not result.get("passed"):
                raise ValueError("Minecraft integration failed: " + json.dumps(result, ensure_ascii=False))
            assertions = result.get("assertions", {})
            if not assertions or any(value is not True for value in assertions.values()):
                raise ValueError("Missing or failing integration assertions")
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text(json.dumps(result, ensure_ascii=False), encoding="utf-8")
        finally:
            subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=10, check=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ("kit", "image", "jar", "report", "hash", "class-name"):
        parser.add_argument("--" + flag, required=True)
    parser.add_argument("--restart", choices=("true", "false"), default="false")
    run(parser.parse_args())
