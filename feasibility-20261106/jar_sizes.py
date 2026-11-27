"""jar_sizes.py JAR: compressed and uncompressed bytes of the engine jar by query-language package, to give a baseline for what a new language front end could add. Read-only."""
import sys
import zipfile

z = zipfile.ZipFile(sys.argv[1])
infos = z.infolist()
tot_c = sum(i.compress_size for i in infos)
print(f"jar: {sys.argv[1].split('/')[-1]}  {tot_c / 1048576:.2f} MiB compressed, {len(infos)} entries")
for label, prefix in (
    ("SQL (all of query/sql)", "com/arcadedb/query/sql/"),
    ("  of which ANTLR parser/lexer classes", "com/arcadedb/query/sql/antlr/"),
    ("  of which parser AST nodes", "com/arcadedb/query/sql/parser/"),
    ("  of which executor steps", "com/arcadedb/query/sql/executor/"),
    ("openCypher (all of query/opencypher)", "com/arcadedb/query/opencypher/"),
    ("Postgres wire (com/arcadedb/postgres)", "com/arcadedb/postgres/"),
):
    sel = [i for i in infos if i.filename.startswith(prefix)]
    c = sum(i.compress_size for i in sel)
    u = sum(i.file_size for i in sel)
    print(f"  {label:45s} {len(sel):5d} classes  {c / 1024:8.0f} KiB compressed  {u / 1024:8.0f} KiB raw  {100 * c / tot_c:5.1f}% of the jar")
