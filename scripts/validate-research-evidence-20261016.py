"""核验本轮原文版本、真实裁图及代码锚点，并生成可离线浏览的引用索引。

只写本轮证据目录；不改原件、历史验收、业务代码或Git。代码提交号必须由发布者显式传入。
"""
from __future__ import annotations
import argparse, csv, hashlib, html, json, math, re
from html.parser import HTMLParser
from datetime import datetime
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit
from zoneinfo import ZoneInfo
from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parents[1]
HERE = ROOT/'团队成果参考库/本轮算法深化_20261016'

def read(path): return json.loads(path.read_text(encoding='utf-8-sig'))
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def dump(path,value): path.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')
def href(path): return quote(str(path).replace('\\','/'),safe='/:#')
def esc(value): return html.escape(str(value))

def check_links():
    class Links(HTMLParser):
        def __init__(self): super().__init__(); self.values=[]
        def handle_starttag(self,tag,attrs):
            if tag in ('a','img'):
                for key,value in attrs:
                    if key in ('href','src') and value: self.values.append(value)
    files=list(HERE.rglob('*.md'))+list(HERE.glob('*.html'))+list((ROOT/'docs/research-20261016').glob('*.md'))
    files += [ROOT/'docs'/name for name in ('技术架构与创新_20261016.md','核心算法说明_20261016.md','参考项目迁移清单_20261016.md')]
    count=0
    for path in files:
        text=path.read_text(encoding='utf-8-sig')
        if path.suffix=='.html':
            parser=Links();parser.feed(text);links=parser.values
        else: links=re.findall(r'!?\[[^\]]*\]\(([^)]+)\)',text)
        for link in links:
            target=urlsplit(link.strip('<>'))
            if target.scheme or not target.path: continue
            resolved=path.parent/unquote(target.path)
            if not resolved.is_file(): raise ValueError(f'Broken local link: {path.name} -> {link}')
            count+=1
    return {'documents':len(files),'localLinksChecked':count,'status':'PASS'}

def portable_bundle(result,mapping,locations):
    """仓库只携带来源/实现定位，不携带全文、页面像素、原始调用或本机绝对代码路径。"""
    directory=ROOT/'docs/research-20261016';directory.mkdir(exist_ok=True)
    sources=[]
    for source in result['sources']:
        sid=source['id'];version=source['sourceVersion']
        sources.append({'sourceId':sid,'title':source['title'],'kind':source['kind'],'family':source['family'],
                        'originalSha256':version['originalSha256'],'renderPdfSha256':version['renderPdfSha256'],
                        'originalBytes':version['originalBytes'],
                        'locators':[{key:e[key] for key in ('key','page','anchor','locator','adoption')}
                                    for e in result['excerpts'] if e['sourceId']==sid]})
    related=ROOT/'团队成果参考库/CN120296243B'
    related_version={'sourceId':'CN120296243B','title':'一种基于改进蜜獾种群算法的在线学习资源推荐方法及系统',
                     'family':'CN120296243','role':'同族版本记录，不单列算法贡献',
                     'originalFormat':'HTML','originalSha256':sha(related/'原文.html'),
                     'renderPdfSha256':sha(related/'原文_Chrome打印.pdf')}
    source_manifest={'schemaVersion':1,'edition':'20261016','codeCommit':mapping['codeCommit'],
                     'availability':'全文和真实裁图保存在项目本地团队成果参考库/本轮算法深化_20261016；此目录仅提供便携定位清单。',
                     'sources':sources,'relatedVersions':[related_version],'familyRelations':mapping['familyRelations']}
    dump(directory/'来源清单.json',source_manifest)
    portable_mapping={key:mapping[key] for key in ('schemaVersion','edition','codeCommit','codeCommitStatus','mapping','notAdopted','familyRelations')}
    portable_mapping['upstreamProject']='用户提供的childcare-smart完整子项目；upstream条目为其项目相对路径。'
    dump(directory/'实现映射.json',portable_mapping)
    dump(directory/'技术选型清单.json',read(HERE/'技术选型清单.json'))
    dump(directory/'验证状态.json',result['evaluation'])
    comparison=(HERE/'原式适配对照.md').read_text(encoding='utf-8')
    comparison=comparison.replace('每个采用点均可从[图文总览](00_总览.html)打开完整原页及真实裁图。',
                                  '每个采用点的原页及真实裁图保存在交付电脑的项目本地团队成果参考库中。')
    (directory/'原式适配对照.md').write_text(comparison,encoding='utf-8')
    rubric=read(HERE/'评分依据.json') if (HERE/'评分依据.json').exists() else {}
    if rubric:
        rubric.pop('sourcePath',None);rubric['sourceFileName']='全球校园人工智能算法精英大赛评分标准.mhtml'
        dump(directory/'评分依据.json',rubric)
    with (directory/'代码位置索引.csv').open('w',encoding='utf-8-sig',newline='') as stream:
        fields=['mappingId','role','path','line','anchor','sha256','commit'];writer=csv.DictWriter(stream,fieldnames=fields)
        writer.writeheader()
        for row in locations: writer.writerow({**{k:row[k] for k in fields if k in row},'commit':mapping['codeCommit'] or 'PENDING'})
    index=['# 晓陶学伴：来源与实现便携清单','',
           '此目录进入私有源码仓库，保存来源定位、方法适配、代码锚点、验证状态和自编验收样例的网站截图；不包含论文全文及原页截图、用户私密资料或内部模型调用记录。',
           '',f"对应源码提交：`{mapping['codeCommit'] or 'PENDING（待最终发布回填）'}`。",'',
           '- [来源清单](来源清单.json)：标题、原件版本哈希、PDF页码、原文段落/公式、采用方式。',
           '- [实现映射](实现映射.json)：已有/本轮/未采用、业务模块、测试与上游代码函数。',
           '- [代码位置](代码位置索引.csv)：主项目条目使用仓库相对路径；upstream条目相对childcare-smart项目。',
           '- [技术选型](技术选型清单.json)、[评分依据](评分依据.json)、[实际验证状态](验证状态.json)。','',
           '- [原式适配对照](原式适配对照.md)：逐条说明原公式、业务转换、真实代码和没有采用的部分。','',
           '- [工程评测结果](工程评测结果.md)、[可移植冻结输入](frozen-optimizer-inputs/README.md)：24组算法输入已离线重放一致，完整30条分母与失败另列。','',
           '完整原件及真实裁图位于交付电脑的 `D:/A-project/newbot_agent3.0/团队成果参考库`，本轮索引位于其 `本轮算法深化_20261016` 子目录。该本地资料不在源码仓库中，不构造指向缺失全文的仓库链接。','',
           '## 核心来源','', '|ID|题名|本轮使用|', '|---|---|---|']
    for s in sources:index.append(f"|{s['sourceId']}|{s['title']}|{s['kind']}：{'；'.join(e['locator'] for e in s['locators'])}|")
    index += ['', 'CN120296243A/B仅按一族记录；P017与CN119025756A的对应思想交叉核读，不重复计算独立创新。', '',
              '## 复核说明','',
              '证据索引生成器需要本地原件与Poppler截图产物，因此它是交付资料核验脚本，不作为不含原文的克隆仓库CI必跑项。业务测试与算法校验可独立运行。',
              '完整原文段落是设计/方法来源；本项目结果以自己的测试和正式站回执为准。没有真实教师评审或学生研究时，不把工程指标描述成教育效果。']
    (directory/'README.md').write_text('\n'.join(index),encoding='utf-8')

def code_location(item, root, role, mapping_id):
    path=(root/item['path']).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file(): raise ValueError('Missing or invalid source: '+str(path))
    lines=path.read_text(encoding='utf-8-sig').splitlines()
    matches=[i+1 for i,line in enumerate(lines) if item['anchor'] in line]
    if len(matches)!=1: raise ValueError(f"Code anchor must be unique: {item['path']} {item['anchor']} found={matches}")
    return {'mappingId':mapping_id,'role':role,'path':item['path'],'line':matches[0],
            'anchor':item['anchor'],'sha256':sha(path),'absolutePath':str(path)}

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--commit',help='本轮发布源码的40位Git提交号；不自动取脏根目录HEAD')
    args=parser.parse_args()
    design=read(HERE/'引用设计.json'); evidence=read(HERE/'原文与截图清单.json'); mapping=read(HERE/'实现映射.json')
    if args.commit:
        if not re.fullmatch('[0-9a-fA-F]{40}',args.commit): raise ValueError('Commit must contain 40 hex characters')
        mapping['codeCommit']=args.commit.lower(); mapping['codeCommitStatus']='RELEASE_COMMIT_SUPPLIED'
        dump(HERE/'实现映射.json',mapping)
    sources={s['id']:s for s in evidence['sources']}
    for source in sources.values():
        original=HERE.parent/source['id']/source['original']
        pdf=HERE.parent/source['id']/source['renderSource']
        if sha(original)!=source['sourceVersion']['originalSha256'] or sha(pdf)!=source['sourceVersion']['renderPdfSha256']:
            raise ValueError('Original version changed: '+source['id'])
    checked=[]
    for ex in evidence['excerpts']:
        current=read((HERE/ex['image']).with_suffix('.json'))
        if current['visualReview']!='PASS': raise ValueError('Screenshot not visually reviewed: '+ex['image'])
        if current['sourceVersion']!=sources[ex['sourceId']]['sourceVersion']: raise ValueError('Wrong source binding')
        if not current['anchorTextLocated'] and not current.get('scannedVisualAnchor'): raise ValueError('Unlocated anchor')
        with Image.open(HERE/current['originalPage']) as page, Image.open(HERE/current['image']) as crop:
            w,h=current['pagePoints']; x0,y0,x1,y1=current['bboxPoints']
            box=(math.floor(x0*page.width/w),math.floor(y0*page.height/h),math.ceil(x1*page.width/w),math.ceil(y1*page.height/h))
            expected=page.crop(box).convert('RGB'); actual=crop.convert('RGB')
            if expected.size!=actual.size or ImageChops.difference(expected,actual).getbbox():
                raise ValueError('Crop pixels differ from real page: '+current['image'])
        checked.append(current)
    evidence['excerpts']=checked
    dump(HERE/'原文与截图清单.json',evidence)
    locations=[]
    upstream=Path(mapping['upstreamRoot'])
    for row in mapping['mapping']:
        for role in ('code','tests','upstream'):
            for item in row.get(role,[]): locations.append(code_location(item,upstream if role=='upstream' else ROOT,role,row['id']))
        for sid in row['sources']:
            if sid!='CHILDCARE' and sid not in sources: raise ValueError('Missing citation source '+sid)
    stamp=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(timespec='seconds')
    evaluation=read(HERE/'验证状态.json') if (HERE/'验证状态.json').exists() else {'integration':'PENDING','release':'PENDING'}
    result={'schemaVersion':1,'generatedAt':stamp,'codeCommit':mapping['codeCommit'],
            'codeCommitStatus':mapping['codeCommitStatus'],'sourceCount':len(sources),'excerptCount':len(checked),
            'pageCount':len({e['originalPage'] for e in checked}),'codeAnchorCount':len(locations),
            'checks':{'originalVersion':'PASS','screenshotPixelIdentity':'PASS','visualReview':'PASS','codeAnchors':'PASS'},
            'evaluation':evaluation,'sources':list(sources.values()),'excerpts':checked,'mapping':mapping['mapping'],
            'codeLocations':locations,'familyRelations':mapping['familyRelations'],'notAdopted':mapping['notAdopted']}
    dump(HERE/'00_总览.json',result)
    with (HERE/'代码位置索引.csv').open('w',encoding='utf-8-sig',newline='') as stream:
        fields=['mappingId','role','path','line','anchor','sha256','absolutePath','commit']
        writer=csv.DictWriter(stream,fieldnames=fields);writer.writeheader()
        for row in locations: writer.writerow({**row,'commit':mapping['codeCommit'] or 'PENDING'})
    parts=['# 本轮算法深化：原文—实现—验证索引','',f'版本日期：2026-10-16；索引生成：{stamp}。',
           f"源码提交：`{mapping['codeCommit'] or 'PENDING（以最终发布后回填为准）'}`。",
           '',f"已核验{len(sources)}份核心全文、{len(checked)}处原文采用点、{len(locations)}处代码/测试/参考项目锚点。原件和历史验收未改动。",
           '', '## 阅读入口','',
           '- [离线图文总览](00_总览.html)：逐项展开真实段落截图与原页。',
           '- [完整映射JSON](实现映射.json)、[代码位置CSV](代码位置索引.csv)、[原文与截图版本](原文与截图清单.json)。',
           '- [技术选型JSON](技术选型清单.json)、[技术架构](../../docs/技术架构与创新_20261016.md)、[核心算法](../../docs/核心算法说明_20261016.md)、[迁移清单](../../docs/参考项目迁移清单_20261016.md)。',
           '- [原式适配对照](原式适配对照.md)：原文定义与业务改写、采用原因及限制。',
           '', '## 本轮采用与边界','', '|成果|类型|采用位置|全文|', '|---|---|---|---|']
    cards=[]
    for sid,source in sources.items():
        original=f"../{sid}/{source['original']}"; pdf=f"../{sid}/{source['renderSource']}"
        excerpts=[e for e in checked if e['sourceId']==sid]
        parts.append(f"|{sid} {source['title']}|{source['kind']}|{'；'.join(e['locator'] for e in excerpts)}|[原件]({href(original)}) / [分页PDF]({href(pdf)})|")
        text=[f"# {sid}：{source['title']}",'',f"采用方式：{source['kind']}。全文：[原件]({href('../../'+sid+'/'+source['original'])})；[分页PDF]({href('../../'+sid+'/'+source['renderSource'])})。",
              '',f"原件SHA256：`{source['sourceVersion']['originalSha256']}`；渲染PDF SHA256：`{source['sourceVersion']['renderPdfSha256']}`。",'']
        detail=[]
        for ex in excerpts:
            text += [f"## {ex['key']} / PDF第{ex['page']}页",'',f"位置：{ex['locator']}。",f"原文定位锚点：`{ex['anchor']}`。",'',ex['adoption'],'',
                     f"![真实原文裁图]({href('../'+ex['image'])})",f"[完整原页]({href('../'+ex['originalPage'])})；[坐标与原文提取]({href('../'+str(Path(ex['image']).with_suffix('.json')))})。",'']
            detail.append(f"<details><summary>{esc(ex['key'])} · 第{ex['page']}页 · {esc(ex['locator'])}</summary><p>{esc(ex['adoption'])}</p><p><code>{esc(ex['anchor'])}</code></p><a href='{href(ex['image'])}'><img loading='lazy' src='{href(ex['image'])}' alt='{esc(ex['locator'])}'></a><p><a href='{href(ex['originalPage'])}'>查看完整原页</a> · <a href='{href(str(Path(ex['image']).with_suffix('.json')))}'>坐标及来源版本</a></p></details>")
        used=[m for m in mapping['mapping'] if sid in m['sources']]
        text += ['## 对应实现与未采用部分','']
        for item in used:
            text += [f"### {item['id']} · {item['change']}",'',item['adoption'],f"未采用：{item['notAdopted']}",'']
            for loc in locations:
                if loc['mappingId']==item['id'] and loc['role']!='upstream':
                    text.append(f"- {loc['role']}：[{loc['path']}:{loc['line']}]({href('../../../'+loc['path'])})，`{loc['anchor']}`。")
            text += ['',f"验证状态：{item['resultStatus']}；最终集成和发布状态见总览。",'']
        (HERE/'逐项借鉴记录').mkdir(exist_ok=True)
        (HERE/'逐项借鉴记录'/f'{sid}.md').write_text('\n'.join(text),encoding='utf-8')
        cards.append(f"<section id='{sid}'><p class='tag'>{esc(source['kind'])}</p><h2>{sid} · {esc(source['title'])}</h2><p><a href='{href(original)}'>原文全文</a> · <a href='{href(pdf)}'>分页PDF</a> · <a href='逐项借鉴记录/{sid}.md'>逐项采用与代码记录</a></p>{''.join(detail)}</section>")
    parts += ['','## 原始方法与业务转换','',
              '- P017和鸽群专利采用多维目标定义；业务评分只覆盖本课目标，时间改为硬约束。',
              '- 萤火虫专利的媒介/内容类型拆分用于教学用途及明确偏好；不推断学生能力。',
              '- 蜜獾专利采用先评价再接受的设计思想；A/B同族版本不重复计算贡献。',
              '- M001用于同一完整问题比较和独立验证；评分差额不等于教学损失。',
              '- M002采用共享相关性标签、未知不当失败和完整分母；不复现连续几何积分。',
              '- M003用于可跳过的先表述—核对—提示—自检设计；不作因果推断。','',
              '## 已有实现、本轮深化、未采用','', '|映射|变化|方式|采用与排除|', '|---|---|---|---|']
    for row in mapping['mapping']: parts.append(f"|{row['id']}|{row['change']}|{row['kind']}|{row['adoption']} 未采用：{row['notAdopted']}|")
    parts += ['','## 验证状态','', '原文版本、截图像素、逐张视觉及代码锚点检查均通过。以下状态以实际日志为准，不能由文件存在推断在线通过。','',
              '```json',json.dumps(evaluation,ensure_ascii=False,indent=2),'```','',
              '同族版本：[CN120296243B原HTML](../CN120296243B/原文.html)及[打印PDF](../CN120296243B/原文_Chrome打印.pdf)保留；本轮未把A/B计成两项算法贡献。','',
              '运行 `scripts/validate-research-evidence-20261016.py` 可重算代码行并核验；发布后传 `--commit <完整40位SHA>`。脚本不修改原文、历史验收或业务代码。']
    (HERE/'00_总览.md').write_text('\n'.join(parts),encoding='utf-8')
    style="body{margin:0;background:#f6f5ef;color:#213b35;font:16px/1.7 system-ui,'Microsoft YaHei',sans-serif}main{max-width:1080px;margin:auto;padding:48px 24px}h1{font-size:32px;line-height:1.3}h2{font-size:22px}section{background:#fffef9;border:1px solid #dce3d8;border-radius:16px;padding:24px;margin:24px 0}.tag{color:#557260;font-size:13px}a{color:#286751;text-underline-offset:4px}details{border-top:1px solid #e1e6df;padding:16px 0}summary{cursor:pointer;font-weight:600}img{display:block;max-width:100%;height:auto;margin:16px auto;background:white;border:1px solid #ddd}code{overflow-wrap:anywhere;font-size:13px}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:#eff2eb;padding:20px;border-radius:12px}nav{display:flex;flex-wrap:wrap;gap:12px}.note{color:#62736a}"
    document=f"<!doctype html><html lang='zh-CN'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>晓陶学伴 · 本轮算法深化证据</title><style>{style}</style><main><p class='tag'>2026-10-16 · 团队成果参考库</p><h1>从原文段落到实际代码</h1><p>{len(sources)}份核心全文 · {len(checked)}处采用点 · {len(locations)}处代码与测试锚点。每张裁图都可返回完整原页。</p><p class='note'>源码提交：{esc(mapping['codeCommit'] or '待最终发布回填')}。论文提供方法或设计依据；本项目的教学效果仍需专门研究。</p><nav><a href='00_总览.md'>Markdown总览</a><a href='实现映射.json'>机器映射</a><a href='代码位置索引.csv'>代码行索引</a><a href='技术选型清单.json'>技术选型</a></nav><section><h2>验证状态</h2><pre>{esc(json.dumps(evaluation,ensure_ascii=False,indent=2))}</pre></section>{''.join(cards)}<section><h2>同族与来源范围</h2><p>CN120296243A/B按同族记录；<a href='../CN120296243B/原文.html'>B版全文</a>、<a href='../CN120296243B/原文_Chrome打印.pdf'>B版打印PDF</a>。P017与CN119025756A的推荐思想交叉核读，不重复计算独立创新。</p><p>本总览引用既存本地全文，新增当前采用点与截图。原文和历史结果未覆盖。来源段落、公式和统计边界以完整原件为准。</p></section></main></html>"
    (HERE/'00_总览.html').write_text(document,encoding='utf-8')
    report={k:result[k] for k in ('generatedAt','codeCommit','sourceCount','excerptCount','pageCount','codeAnchorCount','checks')}
    dump(HERE/'索引核验结果.json',report)
    portable_bundle(result,mapping,locations)
    report['links']=check_links()
    dump(HERE/'索引核验结果.json',report)
    print(json.dumps(report,ensure_ascii=False))

if __name__=='__main__': main()
