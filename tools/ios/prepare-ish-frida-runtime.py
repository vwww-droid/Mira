#!/usr/bin/env python3
from __future__ import annotations

import argparse
import io
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from dataclasses import dataclass
from pathlib import Path

FRIDA_VERSION = "16.0.7"
FRIDA_TOOLS_VERSION = "12.1.0"
RUNTIME_VERSION = "official-frida-tools-12.1.0-frida-16.0.7-v4"
ALPINE_VERSION = "v3.19"
ALPINE_ARCH = "x86"
ALPINE_REPOS = (
    f"https://dl-cdn.alpinelinux.org/alpine/{ALPINE_VERSION}/main/{ALPINE_ARCH}",
    f"https://dl-cdn.alpinelinux.org/alpine/{ALPINE_VERSION}/community/{ALPINE_ARCH}",
)
ROOT_PACKAGES = ("python3", "gcompat")
PURE_PYTHON_SPECS = (
    "colorama<1.0.0,>=0.2.7",
    "prompt-toolkit<4.0.0,>=2.0.0",
    "pygments<3.0.0,>=2.0.2",
    "wcwidth",
)

SCRIPT_PATH = Path(__file__).resolve()
ROOT_DIR = SCRIPT_PATH.parents[2]
FRIDA_TOOLS_DIR = ROOT_DIR / "third_party" / "frida-tools"
LLVM_CLANG_CANDIDATES = (
    Path("/opt/homebrew/opt/llvm/bin/clang"),
    Path("/usr/local/opt/llvm/bin/clang"),
)


@dataclass(frozen=True)
class AlpinePackage:
    name: str
    version: str
    repo_url: str
    depends: tuple[str, ...]
    provides: frozenset[str]

    @property
    def filename(self) -> str:
        return f"{self.name}-{self.version}.apk"

    @property
    def url(self) -> str:
        return f"{self.repo_url}/{self.filename}"


def log(message: str) -> None:
    print(f"[mira-ios-frida] {message}", flush=True)


def download(url: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.is_file() and target.stat().st_size > 0:
        return
    tmp = target.with_suffix(target.suffix + ".tmp")
    with urllib.request.urlopen(url) as response, tmp.open("wb") as sink:
        shutil.copyfileobj(response, sink)
    tmp.replace(target)


def run(cmd: list[str]) -> None:
    log("run: " + " ".join(cmd))
    subprocess.run(cmd, check=True)


def read_apk_index(repo_url: str, cache_dir: Path) -> str:
    archive_path = cache_dir / f"{repo_url.rstrip('/').split('/')[-2]}-{repo_url.rstrip('/').split('/')[-1]}-APKINDEX.tar.gz"
    download(f"{repo_url}/APKINDEX.tar.gz", archive_path)
    with tarfile.open(archive_path, "r:gz") as archive:
        return archive.extractfile("APKINDEX").read().decode("utf-8")


def normalize_dep(token: str) -> str:
    token = token.strip()
    if not token or token.startswith("!"):
        return ""
    token = re.split(r"(?<!:)[<>=~]", token, maxsplit=1)[0]
    return token.strip()


def parse_depends(raw_depends: str) -> tuple[str, ...]:
    tokens = []
    for raw in raw_depends.split():
        normalized = normalize_dep(raw)
        if normalized:
            tokens.append(normalized)
    return tuple(tokens)


def parse_provides(raw_provides: str) -> frozenset[str]:
    provided: set[str] = set()
    for raw in raw_provides.split():
        name = raw.split("=", 1)[0].strip()
        if name:
            provided.add(name)
    return frozenset(provided)


def parse_package_index(raw_text: str, repo_url: str) -> dict[str, AlpinePackage]:
    packages: dict[str, AlpinePackage] = {}
    for block in raw_text.strip().split("\n\n"):
        if not block.strip():
            continue
        fields: dict[str, str] = {}
        for line in block.splitlines():
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            fields[key] = value.strip()
        name = fields.get("P")
        version = fields.get("V")
        arch = fields.get("A")
        if not name or not version or not arch or arch != ALPINE_ARCH:
            continue
        packages[name] = AlpinePackage(
            name=name,
            version=version,
            repo_url=repo_url,
            depends=parse_depends(fields.get("D", "")),
            provides=parse_provides(fields.get("p", "")),
        )
    return packages


def build_provider_index(packages: dict[str, AlpinePackage]) -> dict[str, list[str]]:
    providers: dict[str, list[str]] = {}
    for package in packages.values():
        for capability in {package.name, *package.provides}:
            providers.setdefault(capability, []).append(package.name)
    return providers


def choose_provider(capability: str, packages: dict[str, AlpinePackage], providers: dict[str, list[str]]) -> str | None:
    if capability in packages:
        return capability
    candidates = providers.get(capability, [])
    if not candidates:
        return None
    return sorted(candidates)[0]


def resolve_packages(packages: dict[str, AlpinePackage], root_packages: tuple[str, ...]) -> list[AlpinePackage]:
    providers = build_provider_index(packages)
    ordered: list[AlpinePackage] = []
    resolved: set[str] = set()
    visiting: set[str] = set()

    def visit(capability: str, from_package: str | None = None) -> None:
        provider = choose_provider(capability, packages, providers)
        if provider is None:
            if capability.startswith(("so:", "cmd:", "pc:")):
                log(f"skip unresolved capability provided by base rootfs: {capability}")
                return
            raise KeyError(f"Packages 索引中缺少依赖: {capability}")
        if from_package is not None and provider == from_package:
            return
        if provider in resolved:
            return
        if provider in visiting:
            raise RuntimeError(f"发现循环依赖: {provider}")
        visiting.add(provider)
        package = packages[provider]
        for dependency in package.depends:
            visit(dependency, from_package=provider)
        visiting.remove(provider)
        resolved.add(provider)
        ordered.append(package)

    for package_name in root_packages:
        visit(package_name)
    return ordered


def extract_apk(apk_path: Path, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(apk_path, "r:gz") as archive:
        members = [member for member in archive.getmembers() if not Path(member.name).name.startswith(".")]
        archive.extractall(destination, members=members)


def install_apk_packages(data_root: Path, cache_dir: Path) -> str:
    cache_dir.mkdir(parents=True, exist_ok=True)
    merged_packages: dict[str, AlpinePackage] = {}
    for repo_url in ALPINE_REPOS:
        merged_packages.update(parse_package_index(read_apk_index(repo_url, cache_dir), repo_url))
    packages = resolve_packages(merged_packages, ROOT_PACKAGES)
    for package in packages:
        apk_path = cache_dir / package.filename
        download(package.url, apk_path)
        extract_apk(apk_path, data_root)
    return detect_python_version(data_root)


def detect_python_version(data_root: Path) -> str:
    candidates = sorted(
        path.name
        for path in (data_root / "usr" / "lib").glob("python*")
        if path.is_dir() and re.fullmatch(r"python\d+\.\d+", path.name)
    )
    if not candidates:
        raise FileNotFoundError(f"在 {data_root / 'usr' / 'lib'} 下未找到 pythonX.Y 目录")
    return candidates[-1].removeprefix("python")


def site_packages_dir(data_root: Path) -> Path:
    return data_root / "opt" / "mira" / "frida-python" / "site-packages"


def download_wheels(destination: Path, specs: tuple[str, ...], extra_args: list[str] | None = None) -> list[Path]:
    destination.mkdir(parents=True, exist_ok=True)
    before = {path.resolve() for path in destination.glob("*")}
    cmd = [
        sys.executable,
        "-m",
        "pip",
        "download",
        "--disable-pip-version-check",
        "--only-binary=:all:",
        "--no-deps",
        "--dest",
        str(destination),
    ]
    if extra_args:
        cmd.extend(extra_args)
    cmd.extend(specs)
    run(cmd)
    after = sorted({path.resolve() for path in destination.glob("*")} - before)
    if not after:
        after = sorted(path.resolve() for path in destination.glob("*"))
    return list(after)


def install_wheel(wheel_path: Path, site_packages: Path) -> None:
    with zipfile.ZipFile(wheel_path) as archive:
        for entry in archive.infolist():
            if entry.filename.endswith("/"):
                continue
            parts = Path(entry.filename).parts
            if not parts:
                continue
            if parts[0].endswith(".data"):
                if len(parts) < 3 or parts[1] not in {"purelib", "platlib"}:
                    continue
                relative_target = Path(*parts[2:])
            else:
                relative_target = Path(*parts)
            target_path = site_packages / relative_target
            target_path.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(entry, "r") as source, target_path.open("wb") as target:
                shutil.copyfileobj(source, target)


def install_pure_python_dependencies(site_packages: Path, wheel_cache_dir: Path) -> list[str]:
    wheel_paths = download_wheels(wheel_cache_dir, PURE_PYTHON_SPECS)
    installed: list[str] = []
    for wheel_path in wheel_paths:
        install_wheel(wheel_path, site_packages)
        installed.append(wheel_path.name)
    return installed


def copytree_replace(source: Path, destination: Path) -> None:
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(source, destination)


def install_vendored_frida_tools(site_packages: Path) -> None:
    if not FRIDA_TOOLS_DIR.is_dir():
        raise FileNotFoundError(f"缺少 vendored frida-tools: {FRIDA_TOOLS_DIR}")
    copytree_replace(FRIDA_TOOLS_DIR / "frida_tools", site_packages / "frida_tools")
    copytree_replace(FRIDA_TOOLS_DIR / "frida_tools.egg-info", site_packages / "frida_tools.egg-info")


def install_frida_wheel(site_packages: Path, wheel_cache_dir: Path) -> str:
    wheel_paths = download_wheels(
        wheel_cache_dir,
        (f"frida=={FRIDA_VERSION}",),
        extra_args=["--platform", "manylinux1_i686", "--implementation", "cp", "--abi", "abi3"],
    )
    wheel_path = next((path for path in wheel_paths if path.name.startswith(f"frida-{FRIDA_VERSION}-")), None)
    if wheel_path is None:
        raise FileNotFoundError(f"未下载到 frida wheel: {wheel_cache_dir}")
    install_wheel(wheel_path, site_packages)
    return wheel_path.name


def write_executable(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    current = path.stat().st_mode
    path.chmod(current | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def resolve_host_clang() -> Path:
    for candidate in LLVM_CLANG_CANDIDATES:
        if candidate.exists():
            return candidate
    clang_path = shutil.which("clang")
    if clang_path:
        return Path(clang_path)
    raise FileNotFoundError("缺少可用的 clang, 无法构建 frida glibc shim")


def build_frida_glibc_shim(cache_dir: Path) -> Path:
    output_path = cache_dir / "libfrida-glibc-compat.so"
    if output_path.exists():
        return output_path

    source_path = cache_dir / "frida_glibc_compat.c"
    source_path.parent.mkdir(parents=True, exist_ok=True)
    source_path.write_text(
        "\n".join(
            [
                "typedef unsigned int uint32_t;",
                "typedef unsigned short uint16_t;",
                "",
                "typedef union { double d; struct { uint32_t lo; uint32_t hi; } w; } mira_ieee_double;",
                "typedef union { long double ld; struct { uint32_t lo; uint32_t mid; uint16_t hi; uint16_t pad[3]; } w; } mira_ieee_long_double;",
                "",
                "int __isnan(double x) {",
                "    mira_ieee_double u;",
                "    u.d = x;",
                "    uint32_t exp = (u.w.hi >> 20) & 0x7ffu;",
                "    uint32_t frac_hi = u.w.hi & 0xfffffu;",
                "    return exp == 0x7ffu && ((frac_hi | u.w.lo) != 0);",
                "}",
                "",
                "int __signbit(double x) {",
                "    mira_ieee_double u;",
                "    u.d = x;",
                "    return (int) (u.w.hi >> 31);",
                "}",
                "",
                "int __signbitl(long double x) {",
                "    mira_ieee_long_double u;",
                "    u.ld = x;",
                "    return (int) (u.w.hi >> 15);",
                "}",
                "",
            ]
        ),
        encoding="utf-8",
    )

    clang = resolve_host_clang()
    run(
        [
            str(clang),
            "-target",
            "i386-linux-musl",
            "-shared",
            "-fPIC",
            "-nostdlib",
            "-fuse-ld=lld",
            str(source_path),
            "-Wl,-soname,libfrida-glibc-compat.so",
            "-o",
            str(output_path),
        ]
    )
    return output_path


def install_frida_glibc_shim(data_root: Path, cache_dir: Path) -> None:
    shim_path = build_frida_glibc_shim(cache_dir)
    target_path = data_root / "opt" / "mira" / "frida-python" / "lib" / shim_path.name
    target_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(shim_path, target_path)


def write_runtime_scripts(data_root: Path) -> None:
    state_dir = data_root / "opt" / "mira" / "frida-state"
    state_dir.mkdir(parents=True, exist_ok=True)
    (state_dir / RUNTIME_VERSION).write_text("ready\n", encoding="utf-8")

    frida_setup = f"""#!/bin/sh
set -eu

STAMP="/opt/mira/frida-state/{RUNTIME_VERSION}"

if [ ! -f "$STAMP" ]; then
    echo "frida-setup: bundled runtime is incomplete" >&2
    exit 1
fi

exit 0
"""

    frida_wrapper = """#!/bin/sh
set -eu

/usr/bin/frida-setup

export PYTHONUNBUFFERED=1
export PYTHONPATH="/opt/mira/frida-python/site-packages${PYTHONPATH:+:$PYTHONPATH}"
export LD_PRELOAD="/opt/mira/frida-python/lib/libfrida-glibc-compat.so${LD_PRELOAD:+:$LD_PRELOAD}"

if [ "${1:-}" = "--status" ]; then
    shift
    exec /usr/bin/python3 -c "import frida, json; dev=frida.get_device_manager().add_remote_device('127.0.0.1:27042'); ps=dev.enumerate_processes(); first=ps[0] if ps else None; print(json.dumps({'frida': frida.__version__, 'connected': True, 'processCount': len(ps), 'pid': getattr(first, 'pid', None), 'target': getattr(first, 'name', None)}, separators=(',', ':')))"
fi

mira_needs_default_target=1
for mira_arg in "$@"; do
    case "$mira_arg" in
        -h|--help|--version|-D|--device|-U|--usb|-R|--remote|-H|--host|-f|--file|-F|--attach-frontmost|-n|--attach-name|-N|--attach-identifier|-p|--attach-pid|-W|--await)
            mira_needs_default_target=0
            ;;
    esac
done

if [ "$mira_needs_default_target" = "1" ]; then
    exec /usr/bin/python3 -m frida_tools.repl -H 127.0.0.1 -n Gadget "$@"
fi

exec /usr/bin/python3 -m frida_tools.repl "$@"
"""

    write_executable(data_root / "usr" / "bin" / "frida-setup", frida_setup)
    write_executable(data_root / "usr" / "bin" / "frida", frida_wrapper)


def write_source_manifest(
    data_root: Path,
    python_version: str,
    pure_wheels: list[str],
    frida_wheel: str,
) -> None:
    manifest_path = data_root / "opt" / "mira" / "frida-python" / "SOURCE.txt"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        f"alpine-version: {ALPINE_VERSION}",
        f"alpine-arch: {ALPINE_ARCH}",
        f"python-version: {python_version}",
        f"frida-version: {FRIDA_VERSION}",
        f"frida-tools-version: {FRIDA_TOOLS_VERSION}",
        f"frida-runtime: {RUNTIME_VERSION}",
        f"frida-wheel: {frida_wheel}",
        "pure-python-wheels:",
    ]
    lines.extend(f"  - {wheel}" for wheel in pure_wheels)
    lines.append(f"vendored-frida-tools: {FRIDA_TOOLS_DIR}")
    manifest_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def validate_layout(data_root: Path) -> None:
    required_paths = (
        data_root / "usr" / "bin" / "python3",
        data_root / "usr" / "bin" / "frida",
        data_root / "usr" / "bin" / "frida-setup",
        data_root / "opt" / "mira" / "frida-python" / "site-packages" / "frida" / "__init__.py",
        data_root / "opt" / "mira" / "frida-python" / "site-packages" / "frida_tools" / "__init__.py",
        data_root / "opt" / "mira" / "frida-python" / "site-packages" / "_frida.abi3.so",
        data_root / "opt" / "mira" / "frida-python" / "lib" / "libfrida-glibc-compat.so",
        data_root / "opt" / "mira" / "frida-state" / RUNTIME_VERSION,
        data_root / "lib" / "ld-linux.so.2",
        data_root / "lib" / "libc.so.6",
    )
    for path in required_paths:
        if not path.exists():
            raise FileNotFoundError(f"构建结果缺少必需文件: {path}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rootfs", required=True, help="Path to MiraISHRoot.fakefs directory")
    parser.add_argument("--cache-dir", required=True, help="Cache directory for downloaded artifacts")
    parser.add_argument("--stamp-path", help="Optional explicit path for the rootfs stamp file")
    args = parser.parse_args()

    rootfs = Path(args.rootfs)
    data_root = rootfs / "data" if (rootfs / "data").is_dir() else rootfs
    if not data_root.is_dir():
        raise SystemExit(f"rootfs directory not found: {data_root}")
    stamp_path = Path(args.stamp_path) if args.stamp_path else rootfs / ".mira-ish-rootfs.stamp"

    cache_dir = Path(args.cache_dir)
    python_version = install_apk_packages(data_root, cache_dir / "apk")
    site_packages = site_packages_dir(data_root)
    site_packages.mkdir(parents=True, exist_ok=True)
    pure_wheels = install_pure_python_dependencies(site_packages, cache_dir / "wheels")
    install_vendored_frida_tools(site_packages)
    frida_wheel = install_frida_wheel(site_packages, cache_dir / "frida-wheel")
    install_frida_glibc_shim(data_root, cache_dir / "shim")
    write_runtime_scripts(data_root)
    write_source_manifest(data_root, python_version, pure_wheels, frida_wheel)
    validate_layout(data_root)

    stamp_path.parent.mkdir(parents=True, exist_ok=True)
    stamp_path.write_text(
        "\n".join(
            [
                "rootfs=appstore-apk",
                f"frida_runtime={RUNTIME_VERSION}",
                f"frida_version={FRIDA_VERSION}",
                f"frida_tools_version={FRIDA_TOOLS_VERSION}",
                f"python_version={python_version}",
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
