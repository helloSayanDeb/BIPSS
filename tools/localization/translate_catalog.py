"""Build-time translation of app-owned copy only; never shipped as an online service.
Resumable per-language catalog. Placeholders are verified before accepting a batch.
"""
from pathlib import Path
import concurrent.futures, json, re, time, urllib.request, urllib.parse, sys
sys.stdout.reconfigure(encoding='utf-8')
ROOT=Path(__file__).resolve().parents[2]
ENTRIES=json.loads((ROOT/'tools/localization/catalog.json').read_text(encoding='utf-8'))
LANGS=['hi','bn','ta','te','kn','gu','mr','ml','pa','ur','or','as','ne','sa','sd','doi','mai','gom','mni-Mtei','sat']
OUT=ROOT/'tools/localization/translations';OUT.mkdir(parents=True,exist_ok=True)
def request(lang, q, mode='xml'):
    # Protect runtime substitutions; never send real substituted values.
    protected=[]
    def protect(m):
        protected.append(m[0]);return '<x'+str(len(protected))+'/>' if mode=='xml' else chr(0xe000+len(protected))
    q=re.sub(r'%(?:\d+\$)?(?:\.\d+)?[sdf]',protect,q)
    url='https://translate.googleapis.com/translate_a/single?'+urllib.parse.urlencode(dict(client='gtx',sl='en',tl=lang,dt='t',q=q))
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url,timeout=45) as response: data=json.load(response)
            break
        except Exception:
            if attempt==3: raise
            time.sleep(2*(attempt+1))
    result=''.join(piece[0] for piece in data[0] if piece and piece[0])
    for i,value in enumerate(protected,1):
        result=re.sub(r'<\s*x\s*'+str(i)+r'\s*/\s*>',lambda _:value,result,flags=re.I)
        result=result.replace(chr(0xe000+i),value).replace(chr(0xf000+i),value)
    return result
def placeholders(s):return sorted(re.findall(r'%(?:\d+\$)?(?:\.\d+)?[sdf]',s))
def run(lang):
    path=OUT/(lang+'.json')
    saved=json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}
    saved={e['key']:saved[e['key']] for e in ENTRIES if e['key'] in saved and placeholders(saved[e['key']])==placeholders(e['english'])}
    pending=[e for e in ENTRIES if e['key'] not in saved]
    batches=[]; batch=[]; size=0
    for e in pending:
        if size+len(e['english'])>2400 and batch: batches.append(batch);batch=[];size=0
        batch.append(e);size+=len(e['english'])+12
    if batch:batches.append(batch)
    for bi,batch in enumerate(batches):
        q='\n'.join(f'[{i:04d}] '+e['english'].replace('\n',' ') for i,e in enumerate(batch))
        translated=request(lang,q)
        segments=list(re.finditer(r'\[\s*(\d{4})\s*\]',translated))
        parsed={int(m[1]):translated[m.end():segments[j+1].start() if j+1<len(segments) else len(translated)].strip() for j,m in enumerate(segments)}
        for i,e in enumerate(batch):
            original=e['english'];result=parsed.get(i,'')
            if not result or placeholders(result)!=placeholders(original):
                result=request(lang,original)
            if not result or placeholders(result)!=placeholders(original):
                result=request(lang,original,mode='pua')
            if not result or placeholders(result)!=placeholders(original):
                # Preserve every value if the translator drops a slot even in isolation.
                # Logged for editorial review rather than accepting a damaged template.
                parts=re.split(r'(%(?:\d+\$)?(?:\.\d+)?[sdf])',original)
                result=''.join(part if re.fullmatch(r'%(?:\d+\$)?(?:\.\d+)?[sdf]',part) or not part.strip()
                    else part[:len(part)-len(part.lstrip())]+request(lang,part.strip())+part[len(part.rstrip()):] for part in parts)
                print('REVIEW',lang,e['key'],'fragment fallback',flush=True)
            if placeholders(result)!=placeholders(original):raise ValueError('Invalid fallback '+e['key'])
            # Keep the original composition's boundary whitespace.
            result=original[:len(original)-len(original.lstrip())]+result.strip()+original[len(original.rstrip()):]
            saved[e['key']]=result
        path.write_text(json.dumps(saved,ensure_ascii=False,indent=2),encoding='utf-8')
        print(lang,len(saved),'/',len(ENTRIES),flush=True)
        time.sleep(.25)
    return lang
if __name__=='__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        futures={pool.submit(run,lang):lang for lang in (sys.argv[1:] or LANGS)}
        for future in concurrent.futures.as_completed(futures):
            try:print('DONE',future.result(),flush=True)
            except Exception as e:print('FAILED',futures[future],repr(e),flush=True)
