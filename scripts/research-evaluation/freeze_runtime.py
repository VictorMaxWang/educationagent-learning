"""冻结评测字节码，避免后续 Maven 清理输出目录影响正在进行的真实请求。"""
from pathlib import Path
import argparse,datetime,hashlib,json,zipfile

ROOT=Path(__file__).resolve().parents[2]
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    output=args.output.resolve()
    if output.exists():raise SystemExit('Frozen runtime already exists; choose a new name')
    entries={}
    for folder in ['target/classes','target/test-classes']:
        base=ROOT/folder
        for file in base.rglob('*.class'):
            key=file.relative_to(base).as_posix()
            if key in entries:raise RuntimeError('Duplicate compiled class: '+key)
            entries[key]=file
    harness='com/demo/edu/classroom/evaluation/ResearchEvaluationCli.class'
    if harness not in entries:raise RuntimeError('Compile the evaluation harness first')
    sources={}
    for base in [ROOT/'src/main/java/com/demo/edu/classroom',ROOT/'src/test/java/com/demo/edu/classroom/evaluation']:
        for path in base.rglob('*.java'):
            sources[path.relative_to(ROOT).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest()
    manifest={'schema':'frozen-research-runtime-v1','createdAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'harnessClassSha256':hashlib.sha256(entries[harness].read_bytes()).hexdigest(),
              'classCount':len(entries),'sources':sources,'includesConfigurationFiles':False}
    output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,'x',zipfile.ZIP_DEFLATED) as archive:
        for key,path in sorted(entries.items()):archive.write(path,key)
        archive.writestr('research-evaluation-build.json',json.dumps(manifest,ensure_ascii=False,indent=2))
    manifest['jarSha256']=hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({key:manifest[key] for key in ['classCount','harnessClassSha256','jarSha256','includesConfigurationFiles']}))
if __name__=='__main__':main()
