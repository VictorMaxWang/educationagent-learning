"""汇总已核读资料和真实代码位置；原件、单篇证据记录保持不变。"""
from __future__ import annotations
import csv, html, json, re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / '团队成果参考库'

def walk(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from walk(child)
    elif isinstance(value, list):
        for child in value:
            yield from walk(child)

def code_line(path, symbol):
    text = path.read_text(encoding='utf-8-sig')
    names = re.findall(r'[A-Za-z_$][\w$]*', symbol)
    for name in names:
        for number, line in enumerate(text.splitlines(), 1):
            if re.search(r'\b' + re.escape(name) + r'\s*\(', line) and not line.lstrip().startswith(('*','//','import ')):
                return number
    return None

def main():
    manifest = json.loads((LIB/'资料清单.json').read_text(encoding='utf-8'))
    sources, mappings, errors = [], [], []
    for entry in manifest:
        identifier = entry['id']
        original = LIB/entry['archivedOriginal']
        if not original.is_file() or original.stat().st_size != entry['bytes']:
            errors.append(f'{identifier}:原件副本缺失或大小不符')
        if entry['kind'] == 'catalog':
            continue
        folder = LIB/identifier
        record_path = folder/'证据映射.json'
        if not record_path.is_file() or not (folder/'借鉴记录.md').is_file():
            errors.append(f'{identifier}:缺少借鉴记录或证据映射')
            continue
        record = json.loads(record_path.read_text(encoding='utf-8'))
        evidence = record.get('references', record.get('evidence', []))
        screenshots = []
        for reference in evidence:
            shot = reference.get('screenshot')
            if not shot or not (folder/shot).is_file():
                errors.append(f"{identifier}:{reference.get('referenceId',reference.get('id'))}:缺少截图")
            else:
                screenshots.append(shot)
        seen = set()
        for node in walk(record):
            relative = node.get('path')
            if not isinstance(relative, str) or not relative.startswith(('src/','desktop-app/','course-engine/','vendor/')):
                continue
            symbol = str(node.get('symbol',''))
            key = (relative, symbol)
            if key in seen:
                continue
            seen.add(key)
            code = ROOT/relative
            if not code.is_file():
                errors.append(f'{identifier}:代码不存在:{relative}')
                continue
            mappings.append({'sourceId':identifier,'path':relative,'symbol':symbol,
                             'line':code_line(code,symbol) if symbol else None,
                             'status':node.get('status','SEE_SOURCE_RECORD'),
                             'scope':node.get('implementationScope',node.get('detail',node.get('note','')))})
        scope = record.get('readingCoverage', record.get('reviewScope',record.get('reviewCoverage',{}).get('note','定向方法核读')))
        sources.append({**entry,'title':record.get('title',entry['title']),
                        'readingScope':scope,'summary':record.get('summary',record.get('technicalReading','')),
                        'evidenceCount':len(evidence),'screenshots':screenshots,
                        'codeMappingCount':len(seen),'recordStatus':record.get('reviewStatus',record.get('status','RECORDED'))})
    counts = Counter(entry['kind'] for entry in sources)
    totals = {'sources':len(sources),'required':counts['required'],'papers':counts['paper'],'patentDocuments':counts['patent'],
              'patentFamilies':len({re.sub(r'[ABU]$','',entry['id']) for entry in sources if entry['kind']=='patent'}),
              'evidenceLocations':sum(entry['evidenceCount'] for entry in sources),'codeMappings':len(mappings),
              'missingOrInvalid':errors}
    payload = {'checkedOn':'2026-10-16','totals':totals,'sources':sources,'codeMappings':mappings}
    (LIB/'00_总览.json').write_text(json.dumps(payload,ensure_ascii=False,indent=2),encoding='utf-8')
    with (LIB/'代码位置索引.csv').open('w',encoding='utf-8-sig',newline='') as stream:
        writer=csv.DictWriter(stream,fieldnames=['sourceId','path','symbol','line','status','scope']);writer.writeheader();writer.writerows(mappings)
    intro = f"已归档 {len(sources)} 份实际取得的完整文件：3 份指定成果、21 篇其他论文、34 份专利文献（30 个申请号主体；A/B版本分别保留）。共 {totals['evidenceLocations']} 处具体原文定位。另保留原合集目录作为来源说明。"
    boundary = '每份资料均有原文、借鉴记录和真实段落图。扫描件保留完整PDF及人工核读证据；网页原件可能缺公式/附图，Chrome打印副本不是官方PDF。两篇学位论文只核读记录中列明的相关章节。未取得独立软著登记材料，不据此判断团队没有软著。'
    implementations = [
        ('M001','固定输入与独立复核的设计借鉴','ClassroomValidationEvidence.record / matches，生成、导入、编辑和补图后绑定内容指纹；浏览器执行检查仍明确为NOT_RUN。'),
        ('M002','确认、拒绝与尚未确认分别记录','ClassroomRuntimeSupport.merge、ClassroomConversation、CoursePlayer保留三态回执。'),
        ('M003','先自述再核对','StudyReflectionCard，按页面内容保存本机自述，只预填提问框。'),
        ('CN116975235A / CN117454988A','按问题目标组织答复','QuestionIntentSupport.directive区分问因、步骤、比较和证据。'),
        ('CN116933542A / CN121352000A','相关性、覆盖与重复度共同选择证据','EvidenceSelector.select从最多8候选取3条，按确定性规则选择。'),
        ('CN117454662A / CN119151105A','失败对象局部修复','retrySlide / retryMedia，只补失败插图，检查版本后提交。'),
        ('P013 / P020 / P017','三个可操作教学实验','理想阵列模型、合成图像滤波、P017五项评价公式；没有移植原群智能优化器。'),
    ]
    lines=['# 团队成果参考库','',intro,'',boundary,'','## 本轮实际落地','']
    lines += [f'- **{ids} · {feature}**：{detail}' for ids,feature,detail in implementations]
    lines += ['','其他条目在单篇记录中区分已有实现类比、设计借鉴、教学素材与未来候选；条目数量不代表新增算法数量，也不代表原实验已复现。','',
              '[完整代码位置索引](代码位置索引.csv) · [机器可读总览](00_总览.json) · [离线可点击总览](00_总览.html)','','## 逐份资料','',
              '|编号|资料|原文|具体证据|代码映射数|','|---|---|---|---|---|']
    cards=[]
    for item in sources:
        identifier=item['id'];original=item['archivedOriginal']
        lines.append(f"|{identifier}|[{item['title'].replace('|',' / ')}]({identifier}/借鉴记录.md)|[完整{item['format'].upper()}]({original})|{item['evidenceCount']}处|{item['codeMappingCount']}|")
        code_links=[]
        for mapping in (m for m in mappings if m['sourceId']==identifier):
            label=html.escape(mapping['symbol'] or mapping['path'])
            position=f":{mapping['line']}" if mapping['line'] else ''
            code_links.append(f"<li><a href='../{html.escape(mapping['path'],quote=True)}'>{label}</a> <small>{html.escape(mapping['path'])}{position} · {html.escape(str(mapping['status']))}</small></li>")
        shot_links=''.join(f"<a href='{identifier}/{html.escape(shot,quote=True)}' target='_blank'><img loading='lazy' src='{identifier}/{html.escape(shot,quote=True)}' alt='{identifier} 原文具体段落截图'></a>" for shot in item['screenshots'])
        cards.append(f"<details data-search='{html.escape(identifier+' '+item['title']+' '+item['summary'],quote=True)}'><summary><b>{identifier}</b> {html.escape(item['title'])}<small>{item['evidenceCount']}处证据 · {item['codeMappingCount']}处代码</small></summary><p>{html.escape(item['summary'])}</p><p>{html.escape(str(item['readingScope']))}</p><p><a href='{html.escape(original,quote=True)}'>打开完整原文</a> · <a href='{identifier}/借鉴记录.md'>完整借鉴记录</a> · <a href='{identifier}/证据映射.json'>定位与采纳范围 JSON</a></p><ul>{''.join(code_links)}</ul><div class='shots'>{shot_links}</div></details>")
    (LIB/'00_总览.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    document="""<!doctype html><html lang='zh-CN'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>团队成果参考库</title><style>body{font:16px/1.7 system-ui,'Microsoft YaHei',sans-serif;background:#f6f3e9;color:#253a34;max-width:1120px;margin:40px auto;padding:0 22px}h1{font-size:34px}input{box-sizing:border-box;width:100%;padding:14px;border:1px solid #bbcbbb;border-radius:14px;margin:20px 0;background:white}details{background:#fffef9;border:1px solid #d8dfd3;border-radius:14px;padding:18px;margin:14px 0}summary{cursor:pointer}summary b{color:#567660;margin-right:10px}small{display:block;color:#617266;font-size:13px;overflow-wrap:anywhere}a{color:#3e7157}li{margin:6px 0}.shots{display:grid;gap:16px}.shots img{max-width:100%;height:auto;border:1px solid #d8dfd3}section{background:#e9efdf;padding:20px;border-radius:18px}.count{font-weight:600}footer{margin-top:30px;font-size:13px;color:#617266}</style><h1>团队成果参考库</h1>"""
    document+=f"<p class='count'>{html.escape(intro)}</p><p>{html.escape(boundary)}</p><section><b>本轮实际落地</b><ul>"+''.join(f'<li><b>{html.escape(ids)} · {html.escape(feature)}</b>：{html.escape(detail)}</li>' for ids,feature,detail in implementations)+"</ul><p>各条目明确区分设计借鉴、公式教学、已有能力类比和未来候选。没有将原文的实验效果写成本系统效果。</p></section><label for='filter'>按编号、标题、方法搜索</label><input id='filter' type='search' placeholder='例如：M003、资源、阵列、图像'><div id='records'>"+''.join(cards)+"</div><footer>核对日期：2026-10-16。保留团队提供的文件版本；本页离线运行，无外部脚本、字体或网络请求。</footer><script>document.querySelector('#filter').addEventListener('input',e=>{const q=e.target.value.toLowerCase().trim();document.querySelectorAll('details').forEach(d=>d.hidden=!d.dataset.search.toLowerCase().includes(q));});</script></html>"
    (LIB/'00_总览.html').write_text(document,encoding='utf-8')
    public_lines=[line for line in lines[:lines.index('## 逐份资料')] if not line.startswith('[完整代码位置索引]')]
    (ROOT/'docs/团队成果参考与代码映射.md').write_text('\n'.join(public_lines)+ '\n\n完整团队原件、逐份记录、截图、代码位置CSV和可点击总览保存在工作区根目录“团队成果参考库”，源代码发布快照不携带原件。\n',encoding='utf-8')
    print(json.dumps(totals,ensure_ascii=False,indent=2))
    if errors:
        raise SystemExit(1)

if __name__=='__main__':main()
