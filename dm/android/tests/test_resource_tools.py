import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

TOOLS = Path(__file__).resolve().parents[1] / "tools"
sys.path.insert(0, str(TOOLS))
import resource_tools as resources
from make_fixture import make_fixture
from verify import contract_check


class ResourceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.game = {
            "schemaVersion": 1, "kind": "game-resources", "contractId": "test-game-v1",
            "productVersion": "test-version", "nsoSha256": {"main": hashlib.sha256(b"original").hexdigest()},
            "archives": [{"name": "00-00000001.data", "size": 4}],
        }
        self.update = self.root / "Update"
        (self.update / "ExeFS").mkdir(parents=True)
        self.data = self.update / "RomFS" / "data"
        self.data.mkdir(parents=True)
        (self.update / "ExeFS" / "main").write_bytes(b"original")
        (self.data / "00-00000001.data").write_bytes(b"data")
        (self.data / "build_manifest.psv").write_bytes(b"synthetic test presence")
        self.output = self.data / "game-manifest.json"
        self.fixture = copy.deepcopy(resources.load_contract("fixture"))
        for entry in self.fixture["archives"]:
            entry["size"] = 1024

    def prepare(self):
        return resources.prepare_game(self.update, self.output, self.game)

    def fixture_manifest(self):
        return resources.new_manifest(self.fixture, [], [
            {**entry, "sha256": "0" * 64} for entry in self.fixture["archives"]])

    def test_preparation_exact_metadata_and_unchanged_inputs(self):
        before = {path: path.read_bytes() for path in self.update.rglob("*") if path.is_file()}
        manifest = self.prepare()
        self.assertEqual(manifest, {
            "schemaVersion": 1, "kind": "game-resources", "contractId": "test-game-v1",
            "productVersion": "test-version",
            "exefs": [{"name": "main", "size": 8, "sha256": hashlib.sha256(b"original").hexdigest()}],
            "archives": [{"name": "00-00000001.data", "size": 4,
                          "sha256": "3a6eb0790f39ac87c94f3856b2dd2c5d110e6811602261a9a923d3bb23adc8b7"}],
        })
        for path, contents in before.items():
            self.assertEqual(path.read_bytes(), contents)
        self.assertEqual(self.output.read_bytes(), resources.manifest_bytes(manifest))
        self.prepare()
        self.assertEqual(self.output.read_bytes(), resources.manifest_bytes(manifest))

    def test_failed_original_preserves_previous_manifest(self):
        self.output.write_bytes(b"old publication")
        (self.update / "ExeFS" / "main").write_bytes(b"wrong")
        with self.assertRaisesRegex(resources.ValidationError, "Original NSO hash mismatch: main"):
            self.prepare()
        self.assertEqual(self.output.read_bytes(), b"old publication")

    def test_production_contract_cannot_accept_synthetic_nso(self):
        with self.assertRaises(resources.ValidationError) as error:
            resources.prepare_game(self.update, self.output)
        self.assertIn("Original NSO hash mismatch: main", str(error.exception))
        self.assertIn("Missing path:", str(error.exception))
        self.assertFalse(self.output.exists())

    def test_missing_paths_collect_actionable_errors(self):
        (self.data / "build_manifest.psv").unlink()
        (self.data / "00-00000001.data").unlink()
        (self.update / "ExeFS" / "main").unlink()
        with self.assertRaises(resources.ValidationError) as error:
            self.prepare()
        message = str(error.exception)
        for name in ("build_manifest.psv", "00-00000001.data", "main"):
            self.assertIn(name, message)
        self.assertFalse(self.output.exists())

    def test_wrong_archive_size_before_archive_hashing(self):
        (self.data / "00-00000001.data").write_bytes(b"bad")
        with mock.patch.object(resources, "hash_file", wraps=resources.hash_file) as hashed:
            with self.assertRaisesRegex(resources.ValidationError, "expected 4"):
                self.prepare()
            self.assertEqual([call.args[0].name for call in hashed.call_args_list], ["main"])

    def test_package_directory_rejected_without_hashing(self):
        package_dir = self.root / "packages"
        package_dir.mkdir()
        (package_dir / "game.nsp").write_bytes(b"package placeholder")
        with mock.patch.object(resources, "hash_file") as hashed:
            with self.assertRaisesRegex(resources.ValidationError, "package files are not accepted"):
                resources.prepare_game(package_dir, self.root / "game-manifest.json", self.game)
            hashed.assert_not_called()
        self.assertEqual((package_dir / "game.nsp").read_bytes(), b"package placeholder")

    def test_prepare_rejects_fixture_contract(self):
        with self.assertRaisesRegex(resources.ValidationError, "production game contract"):
            resources.prepare_game(self.update, self.output, self.fixture)
        self.assertFalse(self.output.exists())

    def test_preparation_rejects_hardlinked_output(self):
        os.link(self.update / "ExeFS" / "main", self.output)
        with self.assertRaisesRegex(resources.ValidationError, "Output collides with input"):
            self.prepare()
        self.assertEqual(self.output.read_bytes(), b"original")

    def test_output_must_have_manifest_filename(self):
        with self.assertRaisesRegex(resources.ValidationError, "game-manifest.json"):
            resources.prepare_game(self.update, self.data / "other.json", self.game)

    def test_failed_atomic_replace_preserves_previous_manifest(self):
        self.output.write_bytes(b"old publication")
        with mock.patch.object(resources.os, "replace", side_effect=OSError("injected replacement failure")):
            with self.assertRaisesRegex(OSError, "injected replacement failure"):
                self.prepare()
        self.assertEqual(self.output.read_bytes(), b"old publication")
        self.assertEqual(list(self.data.glob(".game-manifest-*.tmp")), [])

    def test_cancelled_hashing_preserves_previous_manifest(self):
        self.output.write_bytes(b"old publication")
        with mock.patch.object(resources, "hash_file", side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.prepare()
        self.assertEqual(self.output.read_bytes(), b"old publication")

    def test_cancelled_publication_preserves_previous_manifest(self):
        self.output.write_bytes(b"old publication")
        with mock.patch.object(resources.os, "replace", side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                self.prepare()
        self.assertEqual(self.output.read_bytes(), b"old publication")
        self.assertEqual(list(self.data.glob(".game-manifest-*.tmp")), [])

    def test_failed_sync_preserves_previous_manifest(self):
        self.output.write_bytes(b"old publication")
        with mock.patch.object(resources.os, "fsync", side_effect=OSError("injected sync failure")):
            with self.assertRaisesRegex(OSError, "injected sync failure"):
                self.prepare()
        self.assertEqual(self.output.read_bytes(), b"old publication")
        self.assertEqual(list(self.data.glob(".game-manifest-*.tmp")), [])

    def test_duplicate_keys_rejected_at_every_depth(self):
        for raw in (b'{"schemaVersion":1,"schemaVersion":1}',
                    b'{"archives":[{"name":"fixture-1.data","name":"fixture-1.data"}]}'):
            with self.subTest(raw=raw), self.assertRaisesRegex(resources.ValidationError, "Duplicate JSON key"):
                resources.parse_json(raw)

    def test_bom_invalid_utf8_and_oversized_json_rejected(self):
        for raw in (b"\xef\xbb\xbf{}", b"\xff", b" " * 65537, b"[" * 2000 + b"]" * 2000,
                    b'{"size":' + b"9" * 6000 + b"}", b'{"size":NaN}'):
            with self.subTest(size=len(raw)), self.assertRaises(resources.ValidationError):
                resources.parse_json(raw)

    def test_unknown_and_missing_manifest_fields(self):
        for key in ("kind", "exefs", "archives", "schemaVersion"):
            manifest = self.fixture_manifest()
            del manifest[key]
            with self.subTest(key=key), self.assertRaises(resources.ValidationError):
                resources.validate_manifest(manifest, self.fixture)
        manifest = self.fixture_manifest()
        manifest["unknown"] = "unexpected"
        with self.assertRaises(resources.ValidationError):
            resources.validate_manifest(manifest, self.fixture)

    def test_wrong_entry_shapes_and_unknown_entry_fields(self):
        for value in (None, "fixture-1.data", 1, True, [], {"name": "fixture-1.data"}):
            manifest = self.fixture_manifest()
            manifest["archives"][0] = value
            with self.subTest(value=value), self.assertRaises(resources.ValidationError):
                resources.validate_manifest(manifest, self.fixture)
        manifest = self.fixture_manifest()
        manifest["archives"][0]["unknown"] = "unexpected"
        with self.assertRaises(resources.ValidationError):
            resources.validate_manifest(manifest, self.fixture)

    def test_unsupported_versions_and_wrong_profile(self):
        for key, value in (("schemaVersion", True), ("schemaVersion", 1.0), ("schemaVersion", 2),
                           ("kind", "game-resources"), ("contractId", "unknown"), ("productVersion", "unknown")):
            manifest = self.fixture_manifest()
            manifest[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(resources.ValidationError):
                resources.validate_manifest(manifest, self.fixture)

    def test_duplicate_archive_names(self):
        manifest = self.fixture_manifest()
        manifest["archives"][1] = copy.deepcopy(manifest["archives"][0])
        with self.assertRaisesRegex(resources.ValidationError, "Duplicate resource name"):
            resources.validate_manifest(manifest, self.fixture)

    def test_malicious_and_unknown_names(self):
        for name in ("../fixture-1.data", "dir/fixture-1.data", "dir\\fixture-1.data", "/absolute", "C:drive.data",
                     ".", "..", "fixture..data", "nul\x00.data", "fixture-4.data"):
            manifest = self.fixture_manifest()
            manifest["archives"][0]["name"] = name
            with self.subTest(name=name), self.assertRaises(resources.ValidationError):
                resources.validate_manifest(manifest, self.fixture)

    def test_invalid_sizes_and_hashes(self):
        for size in (0, -1, 1024.0, True, "1024", 1 << 63):
            manifest = self.fixture_manifest()
            manifest["archives"][0]["size"] = size
            with self.subTest(size=size), self.assertRaises(resources.ValidationError):
                resources.validate_manifest(manifest, self.fixture)
        for digest in ("A" * 64, "a" * 63, "z" * 64, 7):
            manifest = self.fixture_manifest()
            manifest["archives"][0]["sha256"] = digest
            with self.subTest(digest=digest), self.assertRaises(resources.ValidationError):
                resources.validate_manifest(manifest, self.fixture)

    def test_nso_metadata_digest_must_match_contract(self):
        manifest = self.prepare()
        manifest["exefs"][0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(resources.ValidationError, "Original NSO declaration mismatch"):
            resources.validate_manifest(manifest, self.game)

    def test_fixture_generation_is_deterministic_and_verifiable(self):
        first = self.root / "fixture-a"
        second = self.root / "fixture-b"
        second.mkdir()
        make_fixture(first, self.fixture)
        make_fixture(second, self.fixture)
        self.assertEqual(sorted(path.name for path in first.iterdir()),
                         ["fixture-1.data", "fixture-2.data", "fixture-3.data", "game-manifest.json"])
        for name in ("fixture-1.data", "fixture-2.data", "fixture-3.data", "game-manifest.json"):
            self.assertEqual((first / name).read_bytes(), (second / name).read_bytes())
        self.assertEqual((first / "fixture-1.data").read_bytes(), bytes(range(1, 256)) + b"\x00" +
                         (bytes(range(1, 256)) + b"\x00") * 3)
        with mock.patch.object(resources, "load_contract", return_value=self.fixture):
            manifest = resources.verify_resources(first / "game-manifest.json", first, "fixture")
        self.assertEqual(manifest["kind"], "synthetic-fixture")
        self.assertEqual(len(manifest["archives"]), 3)

    def test_fixture_generation_never_reuses_nonempty_directory(self):
        destination = self.root / "fixture"
        destination.mkdir()
        (destination / "keep").write_bytes(b"keep")
        with self.assertRaisesRegex(resources.ValidationError, "empty or not exist"):
            make_fixture(destination, self.fixture)
        self.assertEqual((destination / "keep").read_bytes(), b"keep")

    def test_failed_fixture_sync_has_no_manifest_or_archives(self):
        destination = self.root / "fixture"
        with mock.patch("make_fixture.os.fsync", side_effect=OSError("fixture sync failed")):
            with self.assertRaisesRegex(OSError, "fixture sync failed"):
                make_fixture(destination, self.fixture)
        self.assertFalse(destination.exists())

    def test_cancelled_fixture_publication_removes_uncommitted_archives(self):
        destination = self.root / "fixture"
        destination.mkdir()
        with mock.patch("make_fixture.publish_manifest", side_effect=KeyboardInterrupt):
            with self.assertRaises(KeyboardInterrupt):
                make_fixture(destination, self.fixture)
        self.assertEqual(list(destination.iterdir()), [])

    def test_corruption_and_missing_archive_are_reported(self):
        destination = self.root / "fixture"
        make_fixture(destination, self.fixture)
        (destination / "fixture-1.data").write_bytes(b"x" * 1024)
        (destination / "fixture-2.data").unlink()
        with mock.patch.object(resources, "load_contract", return_value=self.fixture):
            with self.assertRaises(resources.ValidationError) as error:
                resources.verify_resources(destination / "game-manifest.json", destination, "fixture")
        self.assertIn("SHA256 mismatch: fixture-1.data", str(error.exception))
        self.assertIn("fixture-2.data", str(error.exception))

    def test_verify_rejects_declared_size_before_opening_archives(self):
        manifest = self.fixture_manifest()
        manifest["archives"][0]["size"] = 7
        path = self.root / "game-manifest.json"
        path.write_bytes(resources.manifest_bytes(manifest))
        with mock.patch.object(resources, "load_contract", return_value=self.fixture), mock.patch.object(resources, "hash_file") as hashed:
            with self.assertRaisesRegex(resources.ValidationError, "Archive size declaration mismatch"):
                resources.verify_resources(path, self.root, "fixture")
            hashed.assert_not_called()

    def test_symlink_input_and_output_rejected(self):
        linked = self.root / "linked"
        try:
            linked.symlink_to(self.update, target_is_directory=True)
        except OSError as exc:
            self.skipTest(f"Host cannot create symlinks: {exc}")
        with self.assertRaisesRegex(resources.ValidationError, "Symlink or reparse"):
            resources.prepare_game(linked, self.output, self.game)
        target = self.root / "old-manifest.json"
        target.write_bytes(b"old")
        self.output.symlink_to(target)
        with self.assertRaisesRegex(resources.ValidationError, "Symlink or reparse"):
            self.prepare()
        self.assertEqual(target.read_bytes(), b"old")

    @unittest.skipUnless(os.name == "nt", "Windows junction check")
    def test_windows_junction_escape_rejected(self):
        junction = self.root / "junction"
        result = subprocess.run(["cmd", "/c", "mklink", "/J", str(junction), str(self.update)], capture_output=True)
        if result.returncode:
            self.skipTest("Host cannot create junction")
        self.addCleanup(lambda: os.rmdir(junction) if junction.exists() else None)
        with self.assertRaisesRegex(resources.ValidationError, "Symlink or reparse"):
            resources.prepare_game(junction, self.output, self.game)

    def test_contract_matches_upstream_sources(self):
        contract_check()

    def test_cli_default_profile_rejects_fixture_and_help_has_no_skip(self):
        path = self.root / "game-manifest.json"
        path.write_bytes(resources.manifest_bytes(self.fixture_manifest()))
        result = subprocess.run([sys.executable, str(TOOLS / "verify.py"), "manifest", "--manifest", str(path),
                                 "--resources", str(self.root)], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("mismatched manifest kind", result.stderr)
        for script in ("prepare_game.py", "make_fixture.py", "verify.py"):
            result = subprocess.run([sys.executable, str(TOOLS / script), "--help"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0)
            self.assertNotIn("--skip", result.stdout)


if __name__ == "__main__":
    unittest.main()
