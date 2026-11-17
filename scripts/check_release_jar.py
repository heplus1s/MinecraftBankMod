import json,zipfile,pathlib
root=pathlib.Path(__file__).resolve().parents[1]
meta=json.loads((root/'src/main/resources/fabric.mod.json').read_text())
jar=root/('build/libs/'+meta['id']+'-1.0.8.jar')
with zipfile.ZipFile(jar) as z:
 m=json.loads(z.read('fabric.mod.json'))
 assert m['version']=='1.0.8'
 assert m['environment']=='*'
 assert not m.get('jars'), 'Do not nest standalone UI artifacts'
 for entry in m['entrypoints']['main']+m['entrypoints']['client']:
  assert entry.replace('.','/')+'.class' in z.namelist(),entry
 assert m['breaks'][m['id']+'-ui']=='*'
 print('PASS release jar manifest and entrypoints:',jar.name)
