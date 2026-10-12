"""Generate synthetic files for the separate fixture application."""

import argparse
import hashlib
import os
from pathlib import Path
import shutil
import sys
import tempfile
from resource_tools import CHUNK, ValidationError, checked_path, load_contract, new_manifest, publish_manifest


def make_fixture(output, contract=None):
    contract = load_contract("fixture") if contract is None else contract
    if contract["kind"] != "synthetic-fixture" or contract["nsoSha256"]:
        raise ValidationError("Only a synthetic fixture contract is supported")
    output = Path(output).absolute()
    checked_path(output.parent, directory=True)
    existed = output.exists()
    if existed:
        checked_path(output, directory=True)
        if any(output.iterdir()):
            raise ValidationError("Fixture output directory must be empty or not exist")
    else:
        checked_path(output, missing=True)
        output.mkdir()
    staging = Path(tempfile.mkdtemp(prefix=".fixture-", dir=output))
    published = []
    try:
        entries = []
        for index, entry in enumerate(sorted(contract["archives"], key=lambda item: item["name"]), start=1):
            block = bytes((value + index) % 256 for value in range(256)) * (CHUNK // 256)
            digest = hashlib.sha256()
            remaining = entry["size"]
            with (staging / entry["name"]).open("wb") as stream:
                while remaining:
                    piece = block[:min(remaining, len(block))]
                    stream.write(piece)
                    digest.update(piece)
                    remaining -= len(piece)
                stream.flush()
                os.fsync(stream.fileno())
            entries.append({**entry, "sha256": digest.hexdigest()})
        if set(output.iterdir()) != {staging}:
            raise ValidationError("Fixture output changed during generation")
        for entry in entries:
            destination = output / entry["name"]
            (staging / entry["name"]).replace(destination)
            published.append(destination)
        manifest = new_manifest(contract, [], entries)
        publish_manifest(output / "game-manifest.json", manifest)
        return manifest
    except BaseException:
        for path in published:
            path.unlink(missing_ok=True)
        raise
    finally:
        shutil.rmtree(staging)
        if not existed and output.exists() and not any(output.iterdir()):
            output.rmdir()


def main():
    parser = argparse.ArgumentParser(description="Generate three deterministic synthetic 16 MiB fixture files and metadata. No game files or NSOs. Use only with org.d2rm.android.fixture.")
    parser.add_argument("--output", required=True, help="Explicit empty or nonexisting output directory; parent must exist")
    args = parser.parse_args()
    try:
        make_fixture(args.output)
    except (OSError, ValidationError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Cancelled; fixture manifest was not published.", file=sys.stderr)
        return 130
    print("Generated 3 synthetic 16 MiB archives. Runtime unavailable.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
