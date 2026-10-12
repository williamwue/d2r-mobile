"""Prepare metadata from an already extracted Update directory."""

import argparse
import sys
from resource_tools import ValidationError, prepare_game


def main():
    parser = argparse.ArgumentParser(description="Validate original production NSOs and archive sizes, then stream SHA256 and atomically write metadata. Does not extract packages or copy game files.")
    parser.add_argument("--update-root", required=True, help="Already extracted Update folder containing ExeFS and RomFS/data")
    parser.add_argument("--output", required=True, help="game-manifest.json in an existing directory; select RomFS/data to keep archives beside it")
    args = parser.parse_args()
    try:
        manifest = prepare_game(args.update_root, args.output)
    except (OSError, ValidationError) as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("Cancelled; previous manifest preserved.", file=sys.stderr)
        return 130
    print(f"Prepared {len(manifest['archives'])} archives. Original NSOs stay on the host. Runtime unavailable.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
