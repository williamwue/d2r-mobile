import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "tools" / "verify_arm64_bridge.py"


class Arm64BridgeCliTest(unittest.TestCase):
    def run_cli(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), *args], text=True, capture_output=True)

    def test_missing_arguments_fail_without_optional_dependencies(self):
        result = self.run_cli()
        self.assertEqual(2, result.returncode)
        self.assertIn("--ndk", result.stderr)
        self.assertIn("--output", result.stderr)

    def test_invalid_ndk_fails_before_output_creation(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "result"
            result = self.run_cli("--ndk", temporary, "--output", str(output))
            self.assertEqual(2, result.returncode)
            self.assertIn("installed Android NDK", result.stderr)
            self.assertFalse(output.exists())

    def test_invalid_budget_and_artifact_fail_before_build(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "source.properties").write_text("Pkg.Revision = 28.2.13676358\n")
            output = root / "result"
            for options, message in [(["--instruction-budget", "0"], "must be positive"),
                                     (["--android-elf", str(root / "absent.so")], "artifact does not exist")]:
                result = self.run_cli("--ndk", temporary, "--output", str(output), *options)
                self.assertEqual(2, result.returncode)
                self.assertIn(message, result.stderr)
                self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
