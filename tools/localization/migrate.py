"""One-time, syntax-aware extraction of display literals into Android resources.
Never localizes matching predicates, serialized keys, protocols, user values or URLs.
The migration manifest records each edit for review; original files are in ignored build/.
"""
from pathlib import Path
import json,re,hashlib,importlib.util
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('catalog',Path(__file__).with_name('catalog.py'))
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
backup=ROOT/'build/localization-originals'
manifest=[]
skip_calls={'Regex','ofPattern','SimpleDateFormat','getString','getStringExtra','putExtra','navigate','popUpTo','stringPreferencesKey','booleanPreferencesKey','intPreferencesKey','longPreferencesKey','floatPreferencesKey','doublePreferencesKey','has','contains','startsWith','endsWith','equals','replace','replaceFirst','split','matches','require','check','error','IllegalArgumentException','IllegalStateException','IOException','File','getIdentifier','forName','getInstance','setAction','parse','testTag','Log.d','Log.w','Log.e','Log.i','Log.v','appendLine'}
skip_files={'HistoryIntent.kt','ModelManifest.kt','ModelDownloadManager.kt','ChatbotEngine.kt','NavGraph.kt','Routes.kt','DeepLink.kt'}
domain={'RiskScore.kt','RiskScoreCalculator.kt','DailyWindows.kt','InsightInputs.kt','Fidelity.kt','DataMode.kt','AlertRanker.kt','EnvContext.kt','Advisories.kt','AutonomicLoad.kt','CyclePredictor.kt'}
ai={'DailyCoach.kt','HistoryCoach.kt','MorningDose.kt','TemplatedResponder.kt','SymptomCoach.kt','GreetingHelper.kt'}

def allowed(path):
    rel=path.relative_to(c.source).as_posix()
    return path.name not in skip_files and (rel.startswith('ui/') or rel.startswith('notifications/') or
        rel.startswith('navigation/') or rel.startswith('sos/') or
        (rel.startswith('ai/') and path.name in ai) or (rel.startswith('domain/') and path.name in domain))

for path in c.source.rglob('*.kt'):
    if not allowed(path):continue
    raw=path.read_bytes(); tree=c.parser.parse(raw); edits=[]
    def txt(n):return raw[n.start_byte:n.end_byte].decode('utf-8')
    def safe(n):
        p=n.parent
        while p:
            if p.type in ('annotation','enum_entry'):return False
            if p.type=='when_entry' and n.end_byte < p.start_byte+txt(p).find('->'):return False
            if p.type=='binary_expression' and any(ch.type in ('==','!=','in','!in','to') for ch in p.children):return False
            if p.type=='call_expression':
                name=txt(p.named_children[0])
                if name in skip_calls or name.split('.')[-1] in skip_calls or name.startswith('Log.'): return False
            if p.type=='property_declaration':
                prefix=txt(p).split('=',1)[0]
                if 'const val' in prefix or re.search(r'\b(questions|patterns|urgent|negation)\s*[:=]?',prefix):return False
            if p.type=='function_declaration':
                name=next((txt(x) for x in p.named_children if x.type=='identifier'),'')
                if name in ('normalize','waterCommand','activityCommand','habitCommand','isKnownRequest'):return False
                break
            p=p.parent
        return True
    def visit(n):
        if n.type=='string_literal':
            english,args=c.template(n,raw)
            # Unit/format strings and symbol-only strings remain machine-stable.
            if c.human(english) and safe(n) and english not in {'BIPSS','BMI','HRV','PRV','HR','SOS','AQI','UV','CPCB','IMD','NDMA','GPS','REM','VO2','VO₂','PPG','ONNX'}:
                key='copy_'+hashlib.sha256(english.encode()).hexdigest()[:12]
                # Recursively migrate expressions inside interpolations, too.
                values=[]
                for arg in args:
                    expression=arg[2:-1] if arg.startswith('${') else arg[1:]
                    values.append(expression)
                fallback=json.dumps(english,ensure_ascii=False).replace('$','\\$')
                result='AppText.text(R.string.'+key+', '+fallback+('' if not values else ', '+', '.join(values))+')'
                edits.append((n.start_byte,n.end_byte,result.encode()))
                manifest.append({'path':str(path.relative_to(ROOT)),'line':n.start_point.row+1,'key':key,'english':english})
                return
        for child in n.children:visit(child)
    visit(tree.root_node)
    if not edits:continue
    saved=backup/path.relative_to(ROOT);saved.parent.mkdir(parents=True,exist_ok=True)
    if saved.exists() and saved.read_bytes()!=raw:raise RuntimeError('Already migrated: '+str(path))
    if not saved.exists():saved.write_bytes(raw)
    for start,end,result in sorted(edits,reverse=True):raw=raw[:start]+result+raw[end:]
    content=raw.decode('utf-8')
    content=re.sub(r'(package [^\r\n]+)',r'\1\n\nimport com.bipss.app.localization.AppText\nimport com.bipss.app.R',content,count=1)
    # Prevent duplicate R imports.
    seen=False;lines=[]
    for line in content.splitlines():
        if line=='import com.bipss.app.R':
            if seen:continue
            seen=True
        lines.append(line)
    path.write_text('\n'.join(lines)+'\n',encoding='utf-8')
(ROOT/'tools/localization/migration.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
print('Migrated',len(manifest),'literals in',len(set(x['path'] for x in manifest)),'files')
