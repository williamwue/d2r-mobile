"""Rerunnable local validation gates for Android resource tooling."""

import argparse
import platform
import re
import shutil
import subprocess
import sys
from resource_tools import ANDROID, ValidationError, load_contract, verify_resources


def contract_check():
    contract = load_contract("game")
    dm = ANDROID.parent
    version = (dm / "PRODUCT").read_text(encoding="utf-8").strip()
    extraction = (dm / "tools" / "extract_game.sh").read_text(encoding="utf-8")
    validator = (dm / "host" / "romfs_validate.c").read_text(encoding="utf-8")
    hashes = dict((name, digest) for digest, name in re.findall(r"^([0-9a-f]{64}) (main|sdk|subsdk0)$", extraction, re.MULTILINE))
    names_match = re.search(r"static const char \*need\[\]\s*=\s*\{([^}]+)\}", validator)
    sizes_match = re.search(r"static const long long size\[\]\s*=\s*\{([^}]+)\}", validator)
    if names_match is None or sizes_match is None:
        raise ValidationError("Cannot locate upstream archive table")
    names = re.findall(r'"([^"\n]+)"', names_match[1])
    sizes = [int(value) for value in re.findall(r"\d+", sizes_match[1])]
    expected = [{"name": name, "size": size} for name, size in zip(names, sizes)]
    if version != contract["productVersion"] or hashes != contract["nsoSha256"] or len(names) != len(sizes) or expected != contract["archives"]:
        raise ValidationError("Production contract drift from PRODUCT, extract_game.sh or romfs_validate.c")
    print(f"Contract matches PRODUCT, 3 original NSO hashes and {len(expected)} archive names/sizes.")


def main():
    parser = argparse.ArgumentParser(description="Local Android resource validation. Fixture acceptance requires explicit --profile fixture. No extraction, credentials, or game execution.")
    commands = parser.add_subparsers(dest="command", required=True)
    manifest = commands.add_parser("manifest", help="Strict manifest and streamed archive SHA256 verification")
    manifest.add_argument("--manifest", required=True)
    manifest.add_argument("--resources", required=True, help="Folder containing required archive files directly")
    manifest.add_argument("--profile", choices=("game", "fixture"), default="game")
    commands.add_parser("contract-check", help="Check frozen production contract against upstream source tables")
    commands.add_parser("unit", help="Run Python unittest behavior tests")
    commands.add_parser("env", help="Optional local Python and tool availability diagnostic")
    args = parser.parse_args()
    try:
        if args.command == "manifest":
            result = verify_resources(args.manifest, args.resources, args.profile)
            print(f"Verified {len(result['archives'])} archives for {result['contractId']}. NSOs are metadata only. Runtime unavailable.")
        elif args.command == "contract-check":
            contract_check()
        elif args.command == "unit":
            return subprocess.call([sys.executable, "-m", "unittest", "discover", "-s", str(ANDROID / "tests"), "-v"])
        else:
            print(f"Python {platform.python_version()} on {platform.system()} {platform.machine()}")
            for name in ("java", "adb", "cmake"):
                print(f"{name}: {'available' if shutil.which(name) else 'unavailable'}")
    except (OSError, ValidationError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Cancelled.", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
