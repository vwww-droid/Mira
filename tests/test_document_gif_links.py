"""Keep full-size release GIFs out of documentation image sources."""

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASE = "https://github.com/vwww-droid/Mira/releases/download/v1.1.2/"
DOCUMENTS = {
    "README.md": ("Area", "cydia-ios"),
    "README.zh-CN.md": ("Area", "cydia-ios"),
    "docs/article-draft.md": ("Area", "cydia-ios", "stat-magisk"),
    "docs/article-draft.zh-CN.md": ("Area", "cydia-ios", "stat-magisk"),
}


def test_documents_embed_previews_and_link_originals():
    for name, gifs in DOCUMENTS.items():
        text = (ROOT / name).read_text()
        images = re.findall(r'<img\b[^>]*\bsrc="([^"]+)"', text)
        images += re.findall(r'!\[[^\]]*\]\(([^)]+)\)', text)
        links = re.findall(r'<a\b[^>]*\bhref="([^"]+)"', text)
        links += re.findall(r'(?<!!)\[[^\]]*\]\(([^)]+)\)', text)
        for gif in gifs:
            preview = f"{BASE}{gif}.preview.gif"
            original = f"{BASE}{gif}.gif"
            assert preview in images, (name, "missing preview", gif)
            assert original not in images, (name, "full-size embed", gif)
            assert original in links, (name, "missing original link", gif)
