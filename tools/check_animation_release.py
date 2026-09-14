"""Check the packaged model and its static/animated resource selection without launching Minecraft."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

path, expected = Path(sys.argv[1]), int(sys.argv[2])
with zipfile.ZipFile(path) as jar:
    original = jar.read('assets/flybrain/models/creature.glb')
    assert hashlib.sha256(original).hexdigest() == '5cfc131dc0ea9955a9a25ca45cca768bf7e244860fc21aa7f29c63f4272802d1'
    asset = 'creature-animated.glb' if expected else 'creature.glb'
    model = jar.read('assets/flybrain/models/' + asset)
    if expected:
        # Approved shipped bake; public checks must not depend on private QA logs.
        assert hashlib.sha256(model).hexdigest() == 'e7c9a14be1f6c6f032052c5f147d8a2268b446062dd73321c90129da9d88afb5', 'Packaged animation differs from approved bake'
    size = struct.unpack_from('<I', model, 12)[0]
    document = json.loads(model[20:20+size])
    assert len(document.get('animations', [])) == expected
    assert len(document['skins'][0]['joints']) == 56
    client = jar.read('dev/flybrain/render/FlyBrainClient.class')
    assert ('models/' + asset).encode() in client
    version = json.loads(jar.read('fabric.mod.json'))['version']
print(f'PASS version={version} animations={expected} joints=56 original_sha256=unchanged')
