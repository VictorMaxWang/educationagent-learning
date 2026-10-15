"""为本项目建立团队成果参考库：原件只读，全文、页面与出处保持可追溯。"""
from __future__ import annotations
import argparse,json,re,shutil,subprocess,zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
SOURCE=Path(r'D:\A-project\祁老师论文专利软著合集')
LIBRARY=ROOT/'团队成果参考库'

def identity(path:Path)->str:
    if path.parent==SOURCE:
        if path.suffix.lower()=='.docx':return 'M001'
        if path.name.startswith('Certifying Expected'):return 'M002'
        if path.name.startswith('AI summarising'):return 'M003'
        return 'CATALOG'
    patent=re.match(r'(CN\d+[ABU])_',path.name)
    if patent:return patent[1]
    paper=re.search(r'_(P\d+)_',path.name)
    if paper:return paper[1]
    raise ValueError(f'无法分配稳定编号：{path.name}')

def main()->None:
    parser=argparse.ArgumentParser();parser.add_argument('--extract',action='store_true');args=parser.parse_args()
    LIBRARY.mkdir(exist_ok=True);records=[]
    files=sorted((p for p in SOURCE.rglob('*') if p.is_file()),key=lambda p:str(p).casefold())
    for path in files:
        identifier=identity(path);directory=LIBRARY/identifier;directory.mkdir(exist_ok=True)
        original=directory/('原文'+path.suffix.lower())
        if original.exists():
            if original.stat().st_size!=path.stat().st_size:raise RuntimeError(f'既有归档与原件大小不同，保留现场：{identifier}')
        else:shutil.copy2(path,original)
        kind='catalog' if identifier=='CATALOG' else 'patent' if identifier.startswith('CN') else 'required' if identifier.startswith('M') else 'paper'
        title=re.sub(r'^(?:\d{4}_P\d+_|CN\d+[ABU]_)','',path.stem).replace('_网页文本','')
        record={'id':identifier,'kind':kind,'title':title,'sourcePath':str(path),'sourceFileName':path.name,'archivedOriginal':str(original.relative_to(LIBRARY)).replace('\\','/'),'bytes':path.stat().st_size,'format':path.suffix[1:].lower()}
        if args.extract:
            if path.suffix.lower()=='.pdf':
                text_path=directory/'全文_分页.txt'
                result=subprocess.run(['pdftotext','-layout','-enc','UTF-8',str(original),str(text_path)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=100)
                if result.returncode:raise RuntimeError(f'PDF全文抽取失败：{identifier}，退出码{result.returncode}')
                text=text_path.read_text(encoding='utf-8');pages=text.split('\f')
                if pages and not pages[-1].strip():pages.pop()
                record.update(pageCount=len(pages),textCharacters=sum(len(p.strip()) for p in pages),extractionStatus='TEXT' if any(p.strip() for p in pages) else 'NEEDS_OCR')
                (directory/'全文_分页.json').write_text(json.dumps([{'page':i+1,'text':text} for i,text in enumerate(pages)],ensure_ascii=False,indent=2),encoding='utf-8')
            elif path.suffix.lower()=='.docx':
                with zipfile.ZipFile(original) as archive:xml=ET.fromstring(archive.read('word/document.xml'))
                ns={'w':'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
                paragraphs=[''.join(node.itertext()) for node in []]
                paragraphs=[''.join(n.text or '' for n in p.iter() if n.tag.rsplit('}',1)[-1]=='t') for p in xml.findall('.//w:p',ns)]
                (directory/'全文_段落.txt').write_text('\n\n'.join(paragraphs),encoding='utf-8')
                record.update(paragraphCount=len(paragraphs),textCharacters=sum(map(len,paragraphs)),extractionStatus='OOXML_TEXT')
            else:record['extractionStatus']='HTML_ORIGINAL'
        (directory/'来源信息.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8');records.append(record)
    (LIBRARY/'资料清单.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
    counts={kind:sum(r['kind']==kind for r in records) for kind in sorted({r['kind'] for r in records})}
    print(json.dumps({'library':str(LIBRARY),'files':len(records),'counts':counts,'needsOcr':[r['id'] for r in records if r.get('extractionStatus')=='NEEDS_OCR']},ensure_ascii=False))

if __name__=='__main__':main()
