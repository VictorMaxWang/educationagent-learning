"""Create auditable engineering expectations, never simulated teacher labels.

Short source sentences are authored for the fixture itself. They are not research
participants, quotations from the team papers, or measured student outcomes.
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "src/test/resources/research-evaluation"
PROVENANCE = {"kind": "agent-authored-engineering-expectation", "status": "draft-engineering-reference", "humanReviewed": False,
              "purpose": "Check implementation behavior, not educational efficacy or teacher-rated accuracy.",
              "source": "Approved implementation plan: teaching-task gate and source-grounded reflection contract"}

def context(case_id, title, grade, source, goal, direction="academic", practice=False):
    return {"id": case_id, "title": title, "topic": title, "grade": grade, "subject": "综合", "direction": direction,
            "revision": 1, "sourceName": "工程夹具自编资料", "sourceText": source,
            "outlines": [{"id": "scene-1", "description": goal}],
            "scenes": [{"id": "scene-1", "title": title, "type": "quiz" if practice else "slide",
                        "content": {"canvas": [{"text": goal}]}, "actions": []}], "progress": {"sceneIndex": 0}}

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    eligible = [
        ("静夜思朗读", "学习朗读时的停顿、重音与节奏，需要补充真人朗读示范。", "观察朗读停顿与节奏", "recitation"),
        ("英语th发音", "本节说明舌尖与牙齿的位置，希望通过口型示范观察发音。", "观察th的口型与气流", "pronunciation"),
        ("英语问路对话", "本节学习问路和回应，文字对话之外需要听到自然语音与交替说话。", "听辨问路和回应", "listening"),
        ("种子萌发观察", "种子会吸水、露白并长出根和芽，需要观察这些变化的真实过程。", "观察萌发的先后变化", "observation"),
        ("水循环观察", "水会蒸发成水汽，水汽遇冷凝结，降水又回到地表。需要观察实景过程。", "观察蒸发凝结与降水", "observation"),
        ("尺子测量方法", "测量时把零刻度与物体起点对齐，视线正对刻度。需要安全的动作示范。", "观察零刻度对齐方法", "procedure"),
        ("平行四边形面积剪拼", "沿高剪下三角形后平移，可把平行四边形拼成长方形，观察面积不变。", "观察剪拼如何保持面积", "concept"),
        ("沁园春长沙朗诵", "本节已有秋景互动图，仍需补充真人诵读节奏与情感表达。", "听辨朗诵节奏与情感", "recitation"),
    ]
    ineligible = [
        ("这道题错题订正", "学生提交了分数加法答案，本节需要定位分母计算错误。", "核对当前答案", True, "academic"),
        ("代码调试与变量追踪", "学生的循环边界报错，需要检查当前代码执行轨迹。", "定位当前报错行", False, "exploration"),
        ("我考试后很焦虑", "学生希望讨论自己的焦虑感受和今天可以采取的一步行动。", "表达情绪需要", False, "wellbeing"),
        ("父母吵架让我害怕", "这是学生对家庭矛盾的个人叙述。", "在当前对话表达需要", False, "wellbeing"),
        ("青春期私密健康疑问", "学生提出个人身体变化疑问，希望得到适龄解释。", "在当前问答澄清疑问", False, "wellbeing"),
        ("这份答案批改", "需要对照当前题目标准答案评阅学生的答案。", "核对作答与标准答案", True, "academic"),
        ("数组排序代码可视化", "本节已经运行学生代码并显示数组、变量和调用栈，学生要查看下一步。", "使用本次真实轨迹", False, "exploration"),
        ("分数判断练习", "当前正在完成分数大小判断题，需要直接作答后反馈。", "提交当前练习", True, "academic"),
    ]
    uncertain = [
        ("综合学习", "本节尚未选定主题和学习目标。", "待确定要学习什么"),
        ("资料中的主题", "资料只写了一个未解释的名称，未说明要学的知识或观看用途。", "等待补充说明"),
        ("活动准备", "当前没有活动内容、步骤或可观察现象，无法判断视频补充作用。", "活动目标尚未形成"),
        ("明天的课程", "课程内容尚未生成，具体任务为空。", "尚未选定学习任务"),
        ("概念甲与概念乙", "两份目标分别要求学习互不相关的概念，资料没有给出当前主题选择。", "先确认本节主题"),
        ("章节练习之外", "资料没有具体知识结论，也没有说明外部视频补充什么。", "待补充具体学习目标"),
        ("自由探索主题", "学生还未确定探索方向，既没有对象也没有计划观察的现象。", "先确定探索对象"),
        ("参考资料待补充", "现有材料只有标题，正文内容缺失，无法确定适配的视频内容。", "补充资料后再判断"),
    ]
    classification = []
    for stage, grade in [("primary", 5), ("junior", 8), ("senior", 11)]:
        for status, rows in [("eligible", eligible), ("ineligible", ineligible), ("uncertain", uncertain)]:
            for number, row in enumerate(rows, 1):
                title, source, goal = row[:3]
                case_id = f"CLS-{stage}-{status}-{number:02d}"
                course = context(case_id, title, grade, source, goal,
                                 row[4] if status == "ineligible" else "academic",
                                 row[3] if status == "ineligible" else False)
                classification.append({"id": case_id, "stage": stage, "split": "development" if number <= 2 else "held-out",
                    "course": course, "sceneId": "scene-1", "expectedStatus": status,
                    "expectedPurpose": row[3] if status == "eligible" else None,
                    "basis": source, "labelProvenance": PROVENANCE})
    # Eight propositions generate distinct accepted paraphrase/missing/conflict cases.
    propositions = [
        ("长方形面积", "长方形面积等于长乘宽。", "长方形的面积用长和宽相乘求出。", "把长和宽相加就是长方形面积。"),
        ("分数通分", "通分时把异分母分数化成与原数相等的同分母分数。", "通分会把分母改成相同的数，但分数大小保持原样。", "通分以后分数的大小会改变。"),
        ("植物蒸腾", "植物通过叶片散失水分的过程叫蒸腾。", "植物的水分经叶子散出去，这个过程就是蒸腾。", "蒸腾是植物的根吸入水分的过程。"),
        ("水汽凝结", "水汽遇冷可以凝结成小水滴。", "水蒸气冷下来能够变为小水滴。", "水汽受热就一定凝结成水滴。"),
        ("尺子测量", "测量时应把零刻度与物体起点对齐。", "开始测量要让物体的一端对着尺子的零线。", "测量时应该把物体起点对齐尺子的数字一。"),
        ("英语问路礼貌表达", "问路时可以先说Excuse me，再提出问题。", "向别人询问路线前，先用Excuse me礼貌打招呼。", "问路时不能先说Excuse me。"),
        ("光的折射", "光从空气斜射入水中时，传播方向通常发生改变。", "光斜着从空气进入水里，路线通常会偏折。", "光斜着进入水中时传播方向一定不变。"),
        ("递归终止", "递归程序需要可达到的终止条件。", "递归必须能走到一个停止继续调用自己的条件。", "递归程序不需要任何终止条件。"),
    ]
    feedback = []
    def add(kind, number, title, source, understanding, expected, basis, **extra):
        case_id = f"FB-{kind}-{number:02d}"
        item = {"id": case_id, "category": kind, "split": "development" if number == 1 else "held-out",
                "course": context(case_id, title, 5, source, "核对当前资料中的命题：" + title),
                "sceneId": "scene-1", "understanding": understanding, "checkPoint": title,
                "expectedStatuses": expected, "basis": basis, "labelProvenance": PROVENANCE}
        item.update(extra)
        feedback.append(item)
    for i, (title, source, paraphrase, wrong) in enumerate(propositions, 1):
        add("paraphrase", i, title, source, paraphrase, ["covered"], "自编资料与表述语义相同，不能因用词不同判错。")
        add("missing", i, title, source, "我还记得这个标题，但没有说出资料里的具体意思。", ["missing"], "本节明确目标有可靠事实资料，学生未表述该命题。")
        add("conflict", i, title, source, wrong, ["conflict"], "表述与所给原文命题明确冲突，引用必须来自当前资料。")
    for i, (title, source, paraphrase, wrong) in enumerate(propositions[:6], 1):
        add("no-evidence", i, title, "", paraphrase, ["uncertain"], "只有主题与目标，缺少reference/lesson事实来源，不能凭模型常识判定。",
            noEvidence=True)
        feedback[-1]["course"]["scenes"][0]["content"] = {}
    for i, (title, source, paraphrase, wrong) in enumerate(propositions[:5], 1):
        add("ambiguous", i, title, "资料甲：" + source + "\n资料乙：" + wrong + "\n两份资料均未说明来源或适用条件。", paraphrase,
            ["uncertain"], "当前资料相互冲突且没有优先来源，不能强行确认对错。")
        add("version-change", i, title, source, paraphrase, ["stale"], "保存后资料内容版本改变，旧表述核对请求应停在stale，不调用模型。",
            priorSourceText=source + "\n旧版附加说明。", requiresVersionCheck=True)
    assert len(classification) == 72 and len(feedback) == 40
    for name, rows in [("classification-72.json", classification), ("feedback-40.json", feedback)]:
        (OUT / name).write_text(json.dumps({"schemaVersion": "engineering-fixture-v1", "labelProvenance": PROVENANCE, "cases": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    print("classification=72 feedback=40; no human-reviewed or student outcome labels")

if __name__ == "__main__":
    main()
