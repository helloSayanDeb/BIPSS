"""Generate Android resources from the reviewed catalog. Does not use the network."""
from pathlib import Path
import json,re,xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'app/src/main/res'
entries=json.loads((ROOT/'tools/localization/catalog.json').read_text(encoding='utf-8'))
locales={'hi':'hi','bn':'bn','ta':'ta','te':'te','kn':'kn','gu':'gu','mr':'mr','ml':'ml','pa':'pa','ur':'ur','or':'or','as':'as','ne':'ne','sa':'sa','sd':'sd','doi':'doi','mai':'mai','gom':'kok','mni-Mtei':'mni-Mtei','sat':'sat','brx':'brx','ks':'ks'}
def slots(s):return sorted(re.findall(r'%\d+\$s',s))
def emit(folder,values):
    root=ET.Element('resources')
    for e in entries:
        if e['key'] not in values:continue
        value=values[e['key']]
        if slots(value)!=slots(e['english']):raise ValueError('Placeholders: '+e['key'])
        node=ET.SubElement(root,'string',name=e['key'],formatted='false')
        node.text='"'+value.replace('\\','\\\\').replace('"','\\"').replace("'","\\'").replace('\n','\\n').replace('\r','\\r').replace('\t','\\t')+'"'
    ET.indent(root)
    target=BASE/folder/'copy.xml';target.parent.mkdir(parents=True,exist_ok=True)
    ET.ElementTree(root).write(target,encoding='utf-8',xml_declaration=True)
emit('values',{e['key']:e['english'] for e in entries})
assets=ROOT/'app/src/main/assets/localization';assets.mkdir(parents=True,exist_ok=True)
(assets/'templates.json').write_text(json.dumps([e['english'] for e in entries],ensure_ascii=False),encoding='utf-8')
coverage={}
for source,locale in locales.items():
    path=ROOT/'tools/localization/translations'/f'{source}.json'
    values=json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}
    extra=ROOT/'tools/localization/extra-translations'/f'{source}.json'
    if extra.exists():values.update(json.loads(extra.read_text(encoding='utf-8')))
    override=ROOT/'tools/localization/overrides'/f'{source}.json'
    if override.exists():values.update(json.loads(override.read_text(encoding='utf-8')))
    emit('values-b+'+locale.replace('-','+'),values)
    coverage[locale]={'translated':sum(e['key'] in values for e in entries),'total':len(entries)}
config=ET.Element('locale-config',{'xmlns:android':'http://schemas.android.com/apk/res/android'})
for locale in ['en']+list(locales.values()):ET.SubElement(config,'locale',{'android:name':locale})
ET.indent(config)
target=BASE/'xml/locales_config.xml'
ET.ElementTree(config).write(target,encoding='utf-8',xml_declaration=True)
(ROOT/'tools/localization/coverage.json').write_text(json.dumps(coverage,indent=2)+'\n')
print(json.dumps(coverage))
