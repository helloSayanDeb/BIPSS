"""Extract presentation templates without rewriting stored values, commands or routes.
Catalog consumed by render-time Android string resources. No user data is processed.
"""
from pathlib import Path
from collections import defaultdict
import hashlib, json, re
from tree_sitter import Language, Parser
import tree_sitter_kotlin

ROOT = Path(__file__).resolve().parents[2]
parser = Parser(Language(tree_sitter_kotlin.language()))
source = ROOT / 'app/src/main/java/com/bipss/app'
entries = {}

def template(n, raw):
    text=''; args=[]; pending=''
    def flush(part):
        def replace(m):
            args.append(m[0]); return '%'+str(len(args))+'$s'
        # tree-sitter-kotlin represents $name as string_content, not interpolation.
        part=re.sub(r'(?<!\\)\$[A-Za-z_][A-Za-z_0-9]*',replace,part)
        return decode(part)
    for c in n.children:
        part=raw[c.start_byte:c.end_byte].decode('utf-8')
        if c.type in ('string_content','escape_sequence'): pending+=part
        elif c.type == 'interpolation':
            text+=flush(pending);pending=''
            args.append(part);text+='%'+str(len(args))+'$s'
    return text+flush(pending),args
def decode(s):
    return re.sub(r'\\([nrt"\\$])', lambda m: {'n':'\n','r':'\r','t':'\t','"':'"','\\':'\\','$':'$'}[m[1]], s)
def human(s):
    letters = re.findall('[A-Za-z]', re.sub(r'%\d+\$s','',s))
    if len(letters) < 2 or len(s) > 6000: return False
    if any(x in s for x in ['https://','http://','android.','com.bipss','SELECT ','CREATE TABLE','ALTER TABLE','PRAGMA','INSERT ','DELETE FROM','<resources','\\b','\\s','\\d','(?','[A-Z','[a-z','{0,']): return False
    if re.fullmatch(r'[a-z0-9_./:]+',s): return False
    if s.startswith(('Bearer ', 'bipss_', 'ACTION_', 'file:', 'content:')): return False
    return True
for path in source.rglob('*.kt'):
    if '/localization/' in path.as_posix(): continue
    raw=path.read_bytes()
    def visit(n):
        if n.type == 'string_literal':
            text,args=template(n,raw)
            if human(text):
                key='copy_'+hashlib.sha256(text.encode()).hexdigest()[:12]
                entry=entries.setdefault(text,dict(key=key,english=text,paths=[],arguments=[]))
                location=str(path.relative_to(ROOT))+':'+str(n.start_point.row+1)
                if location not in entry['paths']: entry['paths'].append(location)
                entry['arguments'].append(args)
        for c in n.children: visit(c)
    tree=parser.parse(raw)
    visit(tree.root_node)

dest=ROOT/'tools/localization/catalog.json'
dest.write_text(json.dumps(sorted(entries.values(),key=lambda e:e['english']),ensure_ascii=False,indent=2),encoding='utf-8')
print('templates',len(entries),'characters',sum(len(s) for s in entries),'dynamic',sum('%1$s' in s for s in entries))
print('catalog',dest)
