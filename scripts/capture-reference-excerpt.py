"""按真实 PDF 页码和原文锚点裁出段落截图，保存定位元数据，不修改 PDF。"""
from __future__ import annotations
import argparse,json,re,subprocess,unicodedata
from pathlib import Path
from xml.etree import ElementTree as ET
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
LIBRARY=ROOT/'团队成果参考库'
PDFTOPPM=Path(r'C:\Users\12804\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\poppler\Library\bin\pdftoppm.exe')

def normalized(text:str)->str:
    return ''.join(c for c in unicodedata.normalize('NFKC',text).casefold() if c.isalnum())

def main()->None:
    parser=argparse.ArgumentParser()
    parser.add_argument('--id',required=True);parser.add_argument('--pdf',type=Path)
    parser.add_argument('--page',type=int,required=True);parser.add_argument('--anchor',required=True)
    parser.add_argument('--key',required=True);parser.add_argument('--padding',type=float,default=9)
    args=parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+',args.id) or not re.fullmatch(r'[A-Za-z0-9_-]+',args.key):raise ValueError('编号和截图key仅使用字母数字短横线下划线')
    directory=LIBRARY/args.id
    pdf=(args.pdf or directory/'原文.pdf').resolve()
    if not pdf.is_file() or args.page<1:raise ValueError('PDF不存在或页码无效')
    result=subprocess.run(['pdftotext','-f',str(args.page),'-l',str(args.page),'-bbox-layout','-enc','UTF-8',str(pdf),'-'],capture_output=True,timeout=90)
    if result.returncode:raise RuntimeError(f'段落坐标提取失败，退出码{result.returncode}')
    # 某些旧PDF的页眉含XML不允许的控制码；只清理定位流，原PDF与截图像素保持原样。
    xml_bytes=re.sub(rb'[\x00-\x08\x0b\x0c\x0e-\x1f]',b'',result.stdout)
    tree=ET.fromstring(xml_bytes)
    page=next(node for node in tree.iter() if node.tag.rsplit('}',1)[-1]=='page')
    width,height=float(page.attrib['width']),float(page.attrib['height'])
    blocks=[];words=[]
    for block in page.iter():
        if block.tag.rsplit('}',1)[-1]!='block':continue
        block_words=[w for w in block.iter() if w.tag.rsplit('}',1)[-1]=='word']
        if not block_words:continue
        index=len(blocks);blocks.append(block)
        words.extend((w,index) for w in block_words)
    contents='';ranges=[]
    for word,index in words:
        start=len(contents);contents+=normalized(word.text or '');ranges.append((start,len(contents),index))
    anchor=normalized(args.anchor);position=contents.find(anchor)
    if not anchor or position<0:raise ValueError(f'本页未找到原文锚点：{args.id} p{args.page}；请核对分页文本，不伪造截图')
    selected=sorted({index for start,end,index in ranges if end>position and start<position+len(anchor)})
    selected_blocks=[blocks[i] for i in selected]
    x0=max(0,min(float(b.attrib['xMin']) for b in selected_blocks)-args.padding)
    y0=max(0,min(float(b.attrib['yMin']) for b in selected_blocks)-args.padding)
    x1=min(width,max(float(b.attrib['xMax']) for b in selected_blocks)+args.padding)
    y1=min(height,max(float(b.attrib['yMax']) for b in selected_blocks)+args.padding)
    cache=ROOT/'target/team-reference-pages';cache.mkdir(parents=True,exist_ok=True)
    stem=cache/f'{args.id}_p{args.page:03d}'
    full_image=stem.with_suffix('.png')
    if not full_image.exists() or full_image.stat().st_mtime<pdf.stat().st_mtime:
        rendered=subprocess.run([str(PDFTOPPM),'-f',str(args.page),'-l',str(args.page),'-singlefile','-r','150','-png',str(pdf),str(stem)],capture_output=True,timeout=120)
        if rendered.returncode or not full_image.exists():raise RuntimeError(f'PDF页面渲染失败，退出码{rendered.returncode}')
    output=directory/'截图';output.mkdir(exist_ok=True)
    png=output/f'{args.key}_p{args.page:03d}.png'
    meta={'sourcePdf':str(pdf),'page':args.page,'anchor':args.anchor,'matchedBlocks':selected,'bboxPoints':[x0,y0,x1,y1],'pagePoints':[width,height],'quote':'\n'.join(' '.join(w.text or '' for w in b.iter() if w.tag.rsplit('}',1)[-1]=='word') for b in selected_blocks),'image':str(png.relative_to(directory)).replace('\\','/')}
    if png.exists():
        metadata=png.with_suffix('.json')
        previous=json.loads(metadata.read_text(encoding='utf-8')) if metadata.exists() else {}
        if any(previous.get(key)!=meta[key] for key in ['sourcePdf','page','anchor','bboxPoints']):raise FileExistsError('截图目标已有其他内容，请使用新的key')
    else:
        with Image.open(full_image) as image:
            scale_x,scale_y=image.width/width,image.height/height
            image.crop((int(x0*scale_x),int(y0*scale_y),int(x1*scale_x+0.999),int(y1*scale_y+0.999))).save(png)
        png.with_suffix('.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(meta,ensure_ascii=False))

if __name__=='__main__':main()
