from pathlib import Path
import json
root=Path(__file__).resolve().parents[1]
meta=json.loads((root/'src/main/resources/fabric.mod.json').read_text())
assert meta['environment']=='*'
assert meta['entrypoints']['main'] and meta['entrypoints']['client']
assert meta['breaks'][meta['id']+'-ui']=='*'
assert 'splitEnvironmentSourceSets()' in (root/'build.gradle').read_text()
for entry in meta['entrypoints']['client']:
 assert (root/'src/client/java'/Path(entry.replace('.','/')+'.java')).exists(),entry
for p in (root/'src/main/java').rglob('*.java'):
 assert 'import net.minecraft.client.' not in p.read_text(),p
assert not (root/'ui').exists()
for m in meta.get('mixins',[]):
 if isinstance(m,dict):
  assert m['environment']=='client'
  assert (root/'src/client/resources'/m['config']).exists()
print('PASS: side-separated entrypoints, no main client imports, legacy UI incompatibility and mixin placement')
