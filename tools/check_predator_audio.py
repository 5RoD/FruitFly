"""Small asset contract check, not a substitute for listening in Minecraft."""
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "src/main/resources/assets/flybrain"
names = {"idle", "walk", "run", "groom", "takeoff", "air", "land", "strike", "feeding", "hurt", "death", "crawl", "scare"}
manifest = json.loads((root / "sounds.json").read_text())
language = json.loads((root / "lang/en_us.json").read_text())
for name in names:
    entry = manifest[name]
    assert entry["subtitle"] in language
    for sound in entry["sounds"]:
        data = (root / "sounds" / (sound["name"].split(":")[1] + ".ogg")).read_bytes()
        assert data[:4] == b"OggS" and b"vorbis" in data[:128]
        assert 1000 < len(data) < 200_000
print("AUDIO PASS: 13 original Vorbis cues and subtitles; no missing resources")
blockstate = json.loads((root / "blockstates/scene_marker.json").read_text())
assert blockstate["variants"][""]["model"] == "flybrain:block/scene_marker"
assert json.loads((root / "models/block/scene_marker.json").read_text())["elements"] == []
print("MARKER ASSET PASS: valid blockstate and empty invisible model")
