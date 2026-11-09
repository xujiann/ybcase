"""Keep row data on the cloud host; emit only counts and one-way digests."""
import collections,json,pathlib,re,subprocess,sys
database,output=sys.argv[1:3]
if not (database=='ybcase' or re.fullmatch(r'ybcase_release_drill_[0-9]+',database)): raise ValueError('Invalid audit database')
def sql(query):
    r=subprocess.run(['docker','compose','exec','-T','db','psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','hip','-d',database],input=query.encode(),capture_output=True,check=True)
    return json.loads(r.stdout.decode())
def quoted(value):return '"'+value.replace('"','""')+'"'
baseline=json.loads(pathlib.Path(sys.argv[3]).read_text()) if len(sys.argv)>3 else None
columns=baseline['columns'] if baseline else sql("select jsonb_object_agg(table_name,cols) from (select table_name,jsonb_agg(column_name order by ordinal_position) cols from information_schema.columns where table_schema='public' group by table_name)x;")
stats={}
for table,cols in sorted(columns.items()):
    selected=','.join(map(quoted,cols))
    stats[table]=sql(f"select jsonb_build_object('count',count(*),'hashes',coalesce(jsonb_agg(digest order by digest),'[]'::jsonb)) from (select md5(row_to_json(x)::text) digest from (select {selected} from public.{quoted(table)})x)y;")
result={'database':database,'columns':columns,'tables':stats}
pathlib.Path(output).write_text(json.dumps(result),encoding='utf-8')
summary={'database':database,'tableCount':len(stats),'counts':{t:v['count'] for t,v in stats.items()}}
if baseline:
    missing={}
    for table,before in baseline['tables'].items():
        lost=collections.Counter(before['hashes'])-collections.Counter(stats[table]['hashes'])
        if lost:missing[table]=sum(lost.values())
    summary['missingOrChangedLegacyRows']=missing
    summary['strictLegacyDifferences']=[t for t in stats if stats[t]!=baseline['tables'][t]]
    result['comparison']=summary
    pathlib.Path(output).write_text(json.dumps(result),encoding='utf-8')
print(json.dumps(summary))
