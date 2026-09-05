from pathlib import Path
from zipfile import ZipFile

import pytest

from mira.release import ReleaseError, validate_archive_root


def write_archive(path: Path, names: list[str]) -> None:
    with ZipFile(path, "w") as archive:
        for name in names:
            archive.writestr(name, b"test")


def test_validate_archive_root_accepts_expected_tree(tmp_path: Path) -> None:
    archive_path = tmp_path / "Mira-unsigned.ipa"
    write_archive(archive_path, ["Payload/Mira.app/Info.plist", "Payload/Mira.app/Mira"])

    validate_archive_root(archive_path, "Payload")


def test_validate_archive_root_rejects_flat_ipa(tmp_path: Path) -> None:
    archive_path = tmp_path / "Mira-unsigned.ipa"
    write_archive(archive_path, ["Mira.app/Info.plist", "Mira.app/Mira"])

    with pytest.raises(ReleaseError, match="预期 Payload/"):
        validate_archive_root(archive_path, "Payload")
