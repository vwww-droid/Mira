#!/usr/bin/env python3
"""Rewrite an unsigned APK without padding gaps while preserving ZIP metadata."""
import argparse
import zipfile
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    with zipfile.ZipFile(args.source, "r") as source, zipfile.ZipFile(
        args.destination, "w", allowZip64=True
    ) as destination:
        for info in source.infolist():
            copied = zipfile.ZipInfo(info.filename, info.date_time)
            copied.compress_type = info.compress_type
            copied.comment = info.comment
            copied.extra = info.extra
            copied.internal_attr = info.internal_attr
            copied.external_attr = info.external_attr
            copied.create_system = info.create_system
            copied.flag_bits = info.flag_bits
            destination.writestr(copied, source.read(info.filename))


if __name__ == "__main__":
    main()
