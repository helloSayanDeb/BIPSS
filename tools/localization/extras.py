"""Fixed Coach vocabulary and enum labels missed by the initial presentation migration."""
from pathlib import Path
import json,re,hashlib,concurrent.futures
from tree_sitter import Language,Parser
import tree_sitter_kotlin
import translate_catalog as translator
ROOT=Path(__file__).resolve().parents[2]
p=Parser(Language(tree_sitter_kotlin.language()))
terms=set()
for rel in ['app/src/main/java/com/bipss/app/ai/HistoryIntent.kt','build/localization-originals/app/src/main/java/com/bipss/app/ai/DailyCoach.kt']:
    text=(ROOT/rel).read_text(encoding='utf-8')
    for match in re.finditer(r'has\(([^)]*)\)',text):
        terms.update(re.findall(r'"([^"\\]+)"',match[1]))
terms.update(['my','today','yesterday','tomorrow','why','what','how','compare','average','best','goal','steps','sleep','recovery','strain','water','heart rate','blood oxygen','skin temperature','body mass index','fitness age','sleep stages','deep sleep','light sleep','period','cycle','notes','breathing','exercise','calories','protein','food','log','add','millilitres','walk','run','yoga','workout','minutes','no','not','without','do not','cannot','dizzy','chest pain','headache','fever','nausea','trouble breathing','not feeling well','better','worse','hello','thanks','tell me more','what next','log water','last night','how much','how many','good','satisfactory','moderate','poor','very poor','severe','low','medium','high','modelled','today','days','hours','minutes'])
for path in (ROOT/'app/src/main/java/com/bipss/app').rglob('*.kt'):
    if '/localization/' in path.as_posix():continue
    raw=path.read_bytes();tree=p.parse(raw)
    def walk(n,enum=False):
        enum=enum or n.type=='enum_entry'
        if enum and n.type=='string_literal':
            s=raw[n.start_byte+1:n.end_byte-1].decode()
            if '\\' not in s and '$' not in s and len(s)>1:terms.add(s)
        for c in n.children:walk(c,enum)
    walk(tree.root_node)
catalog=json.loads((ROOT/'tools/localization/catalog.json').read_text(encoding='utf-8'))
known={e['english'] for e in catalog}
extra=[dict(key='copy_'+hashlib.sha256(s.encode()).hexdigest()[:12],english=s,paths=['offline Coach vocabulary / fixed display labels'],arguments=[]) for s in sorted(terms) if s not in known]
(ROOT/'tools/localization/catalog.json').write_text(json.dumps(catalog+extra,ensure_ascii=False,indent=2),encoding='utf-8')
assets=ROOT/'app/src/main/assets/localization';assets.mkdir(parents=True,exist_ok=True)
(assets/'coach_phrases.json').write_text(json.dumps(sorted(terms),ensure_ascii=False),encoding='utf-8')
translator.ENTRIES=[dict(key='copy_'+hashlib.sha256(s.encode()).hexdigest()[:12],english=s) for s in sorted(terms)]
translator.OUT=ROOT/'tools/localization/extra-translations';translator.OUT.mkdir(parents=True,exist_ok=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    for lang in pool.map(translator.run,translator.LANGS):print('EXTRA DONE',lang,flush=True)
