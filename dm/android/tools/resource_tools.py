"""Strict local resource manifests for the frozen Android contracts."""

import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile

ANDROID = Path(__file__).resolve().parents[1]
MAX_MANIFEST = 65536
MAX_INTEGER = (1 << 63) - 1
CHUNK = 1024 * 1024
HEX = re.compile(r"[0-9a-f]{64}\Z")
NAME = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}\Z")


class ValidationError(ValueError):
    pass


def no_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValidationError(f"Duplicate JSON key: {key}")
        result[key] = value
    return result


def parse_json(raw):
    if len(raw) > MAX_MANIFEST:
        raise ValidationError("JSON exceeds 64 KiB limit")
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=no_duplicate_keys,
                          parse_constant=lambda _: (_ for _ in ()).throw(ValidationError("Non-finite JSON number")))
    except (UnicodeError, ValueError, RecursionError) as exc:
        if isinstance(exc, ValidationError):
            raise
        raise ValidationError("Invalid UTF-8 JSON") from exc


def exact_fields(value, names, label):
    if type(value) is not dict or set(value) != set(names):
        raise ValidationError(f"{label} must have exactly {', '.join(names)}")


def positive_integer(value, label):
    if type(value) is not int or not 1 <= value <= MAX_INTEGER:
        raise ValidationError(f"{label} must be a positive signed 64-bit integer")


def safe_name(value):
    if type(value) is not str or not NAME.fullmatch(value) or ".." in value:
        raise ValidationError("Invalid resource name; use a direct filename only")


def digest_value(value):
    if type(value) is not str or not HEX.fullmatch(value):
        raise ValidationError("SHA256 must be 64 lowercase hexadecimal characters")


def checked_path(path, *, directory=False, missing=False):
    path = Path(os.path.abspath(path))
    for part in [*reversed(path.parents), path]:
        try:
            info = part.lstat()
        except FileNotFoundError:
            if missing and part == path:
                return path
            raise ValidationError(f"Missing path: {part}") from None
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValidationError(f"Symlink or reparse path is not allowed: {part}")
    if directory and not path.is_dir():
        raise ValidationError(f"Expected directory: {path}")
    if not directory and not path.is_file():
        raise ValidationError(f"Expected regular file: {path}")
    return path


def read_json(path):
    path = checked_path(path)
    with path.open("rb") as stream:
        return parse_json(stream.read(MAX_MANIFEST + 1))


def load_contract(profile):
    if profile not in ("game", "fixture"):
        raise ValidationError("Unknown resource profile")
    contract = read_json(ANDROID / "contracts" / f"{profile}-contract.json")
    exact_fields(contract, ("schemaVersion", "kind", "contractId", "productVersion", "nsoSha256", "archives"), "Contract")
    if type(contract["schemaVersion"]) is not int or contract["schemaVersion"] != 1:
        raise ValidationError("Unsupported contract schemaVersion")
    expected_kind = "game-resources" if profile == "game" else "synthetic-fixture"
    if contract["kind"] != expected_kind:
        raise ValidationError("Contract profile mismatch")
    for field in ("contractId", "productVersion"):
        if type(contract[field]) is not str or not contract[field]:
            raise ValidationError(f"Invalid contract {field}")
    if type(contract["nsoSha256"]) is not dict:
        raise ValidationError("Invalid NSO contract")
    for name, digest in contract["nsoSha256"].items():
        safe_name(name)
        digest_value(digest)
    if profile == "fixture" and contract["nsoSha256"]:
        raise ValidationError("Fixture contract must not contain NSOs")
    entries = contract["archives"]
    if type(entries) is not list or not entries or len(entries) > 64:
        raise ValidationError("Invalid archive contract")
    names = set()
    for entry in entries:
        exact_fields(entry, ("name", "size"), "Contract archive")
        safe_name(entry["name"])
        positive_integer(entry["size"], "Archive size")
        if entry["name"] in names:
            raise ValidationError("Duplicate contract archive")
        names.add(entry["name"])
    return contract


def validate_manifest(manifest, contract):
    exact_fields(manifest, ("schemaVersion", "kind", "contractId", "productVersion", "exefs", "archives"), "Manifest")
    for key in ("schemaVersion", "kind", "contractId", "productVersion"):
        if type(manifest[key]) is not type(contract[key]) or manifest[key] != contract[key]:
            raise ValidationError(f"Unsupported or mismatched manifest {key}")
    expected_groups = {
        "exefs": {name: None for name in contract["nsoSha256"]},
        "archives": {item["name"]: item["size"] for item in contract["archives"]},
    }
    all_names = set()
    for group, expected in expected_groups.items():
        entries = manifest[group]
        if type(entries) is not list or len(entries) != len(expected):
            raise ValidationError(f"{group} must contain exactly the contract entries")
        actual = set()
        for entry in entries:
            exact_fields(entry, ("name", "size", "sha256"), group + " entry")
            name = entry["name"]
            safe_name(name)
            positive_integer(entry["size"], "Resource size")
            digest_value(entry["sha256"])
            if name in all_names:
                raise ValidationError(f"Duplicate resource name: {name}")
            all_names.add(name)
            actual.add(name)
            if name not in expected:
                raise ValidationError(f"Unknown resource name: {name}")
            if group == "exefs" and entry["sha256"] != contract["nsoSha256"][name]:
                raise ValidationError(f"Original NSO declaration mismatch: {name}")
            if group == "archives" and entry["size"] != expected[name]:
                raise ValidationError(f"Archive size declaration mismatch: {name}")
        if actual != set(expected):
            raise ValidationError(f"Missing {group} entries")
    return manifest


def hash_file(path, expected_size=None):
    path = checked_path(path)
    before = path.stat()
    positive_integer(before.st_size, "File size")
    if expected_size is not None and before.st_size != expected_size:
        raise ValidationError(f"Wrong size for {path.name}: got {before.st_size}, expected {expected_size}")
    digest = hashlib.sha256()
    total = 0
    with path.open("rb") as stream:
        opened = os.fstat(stream.fileno())
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise ValidationError(f"File changed before reading: {path.name}")
        while block := stream.read(CHUNK):
            total += len(block)
            if total > before.st_size:
                raise ValidationError(f"File grew while reading: {path.name}")
            digest.update(block)
        after = os.fstat(stream.fileno())
    if total != before.st_size or (after.st_size, after.st_mtime_ns, after.st_ctime_ns) != (before.st_size, before.st_mtime_ns, before.st_ctime_ns):
        raise ValidationError(f"File changed while reading: {path.name}")
    checked_path(path)
    current = path.stat()
    if (current.st_dev, current.st_ino, current.st_size, current.st_mtime_ns) != (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns):
        raise ValidationError(f"File replaced while reading: {path.name}")
    return {"name": path.name, "size": total, "sha256": digest.hexdigest()}


def manifest_bytes(manifest):
    raw = (json.dumps(manifest, sort_keys=True, indent=2, ensure_ascii=True) + "\n").encode("utf-8")
    if len(raw) > MAX_MANIFEST:
        raise ValidationError("Manifest exceeds 64 KiB limit")
    return raw


def publish_manifest(output, manifest):
    output = checked_path(output, missing=True)
    checked_path(output.parent, directory=True)
    temp = None
    try:
        fd, name = tempfile.mkstemp(prefix=".game-manifest-", suffix=".tmp", dir=output.parent)
        temp = Path(name)
        with os.fdopen(fd, "wb") as stream:
            stream.write(manifest_bytes(manifest))
            stream.flush()
            os.fsync(stream.fileno())
        checked_path(output, missing=True)
        os.replace(temp, output)
        temp = None
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)


def new_manifest(contract, exefs, archives):
    return {**{key: contract[key] for key in ("schemaVersion", "kind", "contractId", "productVersion")},
            "exefs": sorted(exefs, key=lambda entry: entry["name"]),
            "archives": sorted(archives, key=lambda entry: entry["name"])}


def prepare_game(update_root, output, contract=None):
    contract = load_contract("game") if contract is None else contract
    if contract["kind"] != "game-resources" or not contract["nsoSha256"]:
        raise ValidationError("prepare_game accepts only the original production game contract")
    root = checked_path(update_root, directory=True)
    output = checked_path(output, missing=True)
    if output.name != "game-manifest.json":
        raise ValidationError("Output filename must be game-manifest.json")
    errors = []
    for directory in (root / "ExeFS", root / "RomFS" / "data"):
        try:
            checked_path(directory, directory=True)
        except (ValidationError, OSError) as exc:
            errors.append(f"Required extracted directory {directory}: {exc}")
    if errors:
        raise ValidationError("An already extracted Update folder is required; package files are not accepted.\n" + "\n".join(errors))
    inputs = [root / "RomFS" / "data" / "build_manifest.psv"]
    inputs += [root / "ExeFS" / name for name in sorted(contract["nsoSha256"])]
    inputs += [root / "RomFS" / "data" / entry["name"] for entry in contract["archives"]]
    for path in inputs:
        try:
            checked_path(path)
            if output.exists() and os.path.samefile(output, path):
                raise ValidationError(f"Output collides with input: {path.name}")
        except (ValidationError, OSError) as exc:
            errors.append(str(exc))
    for entry in contract["archives"]:
        path = root / "RomFS" / "data" / entry["name"]
        if path.is_file() and path.stat().st_size != entry["size"]:
            errors.append(f"Wrong size for {entry['name']}: got {path.stat().st_size}, expected {entry['size']}")
    exefs = []
    for name, digest in sorted(contract["nsoSha256"].items()):
        path = root / "ExeFS" / name
        try:
            entry = hash_file(path)
            if entry["sha256"] != digest:
                errors.append(f"Original NSO hash mismatch: {name}; expected game version {contract['productVersion']}")
            exefs.append(entry)
        except (ValidationError, OSError) as exc:
            if str(exc) not in errors:
                errors.append(str(exc))
    if errors:
        raise ValidationError("Resource preparation failed:\n" + "\n".join(errors))
    archives = [hash_file(root / "RomFS" / "data" / entry["name"], entry["size"]) for entry in contract["archives"]]
    manifest = validate_manifest(new_manifest(contract, exefs, archives), contract)
    publish_manifest(output, manifest)
    return manifest


def verify_resources(manifest_path, resources, profile="game"):
    contract = load_contract(profile)
    manifest = validate_manifest(read_json(manifest_path), contract)
    root = checked_path(resources, directory=True)
    errors = []
    for entry in manifest["archives"]:
        try:
            actual = hash_file(root / entry["name"], entry["size"])
            if actual["sha256"] != entry["sha256"]:
                errors.append(f"SHA256 mismatch: {entry['name']}")
        except (OSError, ValidationError) as exc:
            errors.append(str(exc))
    if errors:
        raise ValidationError("Resource verification failed:\n" + "\n".join(errors))
    return manifest
