#!/usr/bin/env python3
"""以网页实际例题为输入验收四语言；与另写一套后端样例分开记录。"""
import argparse, json, os, time, uuid, urllib.request
from pathlib import Path

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--examples',required=True);parser.add_argument('--report',required=True)
    parser.add_argument('--url',default='http://127.0.0.1:8899');args=parser.parse_args()
    examples=json.loads(Path(args.examples).read_text()); report=[]
    def run(example,stdin=None,expected=None):
        body={'id':'page-'+uuid.uuid4().hex,'language':example['language'],'code':example['code'],'stdin':example['stdin'] if stdin is None else stdin}
        request=urllib.request.Request(args.url+'/run',data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+os.environ['CODE_RUNNER_TOKEN'],'Content-Type':'application/json'})
        started=time.monotonic()
        with urllib.request.urlopen(request,timeout=65) as response: result=json.load(response)
        actual=result['stdout'].replace('\r\n','\n').strip(); wanted=example['expectedStdout'].strip() if expected is None else expected
        assert result['status']=='completed',(example['language'],example['id'],result.get('reason'),result.get('diagnostics'),result.get('stderr'))
        assert actual==wanted,(example['language'],example['id'],actual,wanted)
        assert len(result['trace'])>1,(example['language'],example['id'],'missing real steps')
        assert any(step['frames'] and step['frames'][0]['locals'] for step in result['trace']),'missing variables'
        if example['id']=='recursion': assert max(len(s['frames']) for s in result['trace'])>1,'missing call stack'
        entry={'language':example['language'],'example':example['id'],'changedInput':stdin is not None,'status':'PASS','steps':len(result['trace']),'stdout':actual,'seconds':round(time.monotonic()-started,2)}
        report.append(entry);Path(args.report).write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(entry,ensure_ascii=False),flush=True)
    for example in examples:
        run(example)
        if example['id']=='area': run(example,'5 7','35')
    assert len(examples)==16 and len(report)==20
    print('PASS: 16 webpage exercises and 4 input-change checks',flush=True)
if __name__=='__main__': main()
