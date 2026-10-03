"""Apply reviewed exceptions: protocols, date patterns, query sorting and animation IDs."""
from pathlib import Path
import re,json
from tree_sitter import Language,Parser
import tree_sitter_kotlin
ROOT=Path(__file__).resolve().parents[2]
p=Parser(Language(tree_sitter_kotlin.language()))
unsafe={'warning_%1$s','fieldBorder','ring%1$s','tabTint','tabPill','%1$s ASC','HH:mm','EEE','d MMM','%02d:%02d','%02d:%02d:%02d'}
for path in (ROOT/'app/src/main/java').rglob('*.kt'):
    raw=path.read_bytes();tree=p.parse(raw);edits=[]
    def walk(n):
        if n.type=='call_expression' and raw[n.start_byte:n.start_byte+13]==b'AppText.text(':
            arguments=next((x for x in n.named_children if x.type=='value_arguments'),None)
            if arguments:
                a=arguments.named_children
                if len(a)>1:
                    fallback=raw[a[1].start_byte:a[1].end_byte].decode().replace('\\$','$')
                    try:english=json.loads(fallback)
                    except json.JSONDecodeError:english=None
                    if english in unsafe:
                        text=english
                        for i,argument in enumerate(a[2:],1):
                            expr=raw[argument.start_byte:argument.end_byte].decode()
                            text=text.replace('%'+str(i)+'$s','${'+expr+'}')
                        literal=json.dumps(text,ensure_ascii=False)
                        edits.append((n.start_byte,n.end_byte,literal.encode()));return
        for child in n.children:walk(child)
    walk(tree.root_node)
    for start,end,new in sorted(edits,reverse=True):raw=raw[:start]+new+raw[end:]
    if edits:path.write_bytes(raw)
print('Reviewed technical exceptions')
