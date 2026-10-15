# -*- coding: utf-8 -*-
"""
记忆脑图示例图生成器 —— 生成 docs/memory-graph.svg

用途：把「运维观测台 · 记忆脑图」的界面画成一张可放进文档的静态图。

刻意做成脚本而不是手写 SVG，是为了让它**不能和代码漂移**：
分代判定、节点大小、环半径、边类型、配色全部照抄实现，改算法时改这里重新生成即可。

对照实现：
  · 分代      com/demo/memory/graph/MemoryGraphService.java  (generation)
  · 节点大小  com/demo/memory/graph/MemoryGraphService.java  (nodeSize)
  · 边类型    com/demo/memory/graph/MemoryGraphDTO.java      (EDGE_HIERARCHY / EDGE_SIMILARITY)
  · 环半径/根 desktop-app/src/ops/MemoryMapView.jsx           (RING_RADIUS / computeFixedLayout)
  · 配色      desktop-app/src/ops/ops.css                     (--ops-gen-* / --ops-status-critical)

运行： python docs/gen_memory_graph.py
"""

import math
import os
import re
import sys

# ---------------------------------------------------------------- 画布与几何
# 照抄 MemoryMapView.jsx：viewBox 1000x760、圆心 (500,380)
CX, CY = 500.0, 380.0
VIEW_W, VIEW_H = 1000.0, 760.0
LEGEND_H = 150.0

# 各分代所在环的半径（绝对像素，照抄 RING_RADIUS）
RING_RADIUS = {"OLD": 120.0, "SURVIVOR": 200.0, "NEW": 280.0, "PENDING_GC": 320.0}
RING_ORDER = ["OLD", "SURVIVOR", "NEW", "PENDING_GC"]

ROOT_ID = "__root__"
ROOT_LABEL = "用户画像"
ROOT_SIZE = 30.0

# 数据色是「冻结」的，不随主题走（ops.css 开头有完整理由与对比度校验值）
COLOR = {
    "OLD": "#0d366b",
    "SURVIVOR": "#2a78d6",
    "NEW": "#86b6ef",
    "PENDING_GC": "#d03b3b",
}
ROOT_COLOR = "#6B7280"       # var(--text-muted)
GRID = "#E5E7EB"             # var(--border)
LINK_HIERARCHY = "#D1D5DB"   # var(--border-strong)
LINK_SIMILARITY = "#2a78d6"  # var(--ops-series)
SURFACE = "#FFFFFF"          # var(--surface)
INK = "#111827"              # var(--text)
INK_MUTED = "#6B7280"        # var(--text-muted)
FONT = "Microsoft YaHei, PingFang SC, Hiragino Sans GB, sans-serif"

GEN_LABEL = {
    "OLD": "OLD 稳固事实",
    "SURVIVOR": "SURVIVOR 有用",
    "NEW": "NEW 新生成",
    "PENDING_GC": "PENDING_GC 待回收",
}


# ------------------------------------------------------------ 照抄的算法实现
def compute_generation(imp, access, age_days, mtype):
    """照抄 MemoryGraphService.generation —— 顺序不能改。

    PENDING_GC 排在最前：低重要度 / 老情景记忆优先被回收，
    否则一条 importance=1 的记忆会先被算成 NEW 再被删掉，视觉上自相矛盾。
    """
    if imp < 2 or (mtype == "EPISODIC" and age_days > 60):
        return "PENDING_GC"
    if access >= 5 and imp >= 7:
        return "OLD"
    if imp >= 4 or access >= 2:
        return "SURVIVOR"
    return "NEW"


def node_size(access, imp):
    """照抄 MemoryGraphService.nodeSize：10 * (1 + log1p(access) * imp/10)，保留 1 位小数。"""
    return round(10.0 * (1.0 + math.log1p(max(0, access)) * (imp / 10.0)), 1)


def draw_radius(size):
    """照抄前端：r = clamp(size/2, 6, 30)。"""
    return max(6.0, min(30.0, size / 2.0))


# ---------------------------------------------------------------- 示例数据
# 用一组「像真的用过一阵子」的记忆：有稳固的自我陈述、有偶尔提及的偏好、
# 有刚说的一次性事件，也有马上要被回收的碎嘴。
# (内容, 类型, 重要度, 访问次数, 年龄天数)
MEMORIES = [
    ("用户叫张三", "BASIC", 9, 12, 210),
    ("用户是软件工程师", "SEMANTIC", 9, 9, 180),
    ("用户住在杭州", "BASIC", 8, 7, 150),
    ("用户喜欢喝咖啡", "SEMANTIC", 6, 4, 90),
    ("用户不喜欢香菜", "SEMANTIC", 6, 3, 75),
    ("用户养了一只猫", "SEMANTIC", 5, 2, 60),
    ("用户在准备考研", "EPISODIC", 5, 2, 40),
    ("用户昨天去了西湖", "EPISODIC", 3, 0, 1),
    ("用户想学 Rust", "SEMANTIC", 3, 1, 3),
    ("用户下周去北京出差", "EPISODIC", 3, 0, 2),
    ("用户最近在减肥", "EPISODIC", 3, 1, 5),
    ("用户买了机械键盘", "EPISODIC", 3, 0, 6),
    ("用户说过天气不错", "EPISODIC", 1, 0, 20),
    ("用户顺口提过讨厌堵车", "EPISODIC", 1, 0, 12),
    ("用户问过地铁末班车", "EPISODIC", 3, 0, 68),
]

# 余弦相似度 >= 0.7 的两两连线（后端 buildLinks 用 similarityThreshold 筛出来的那类边）
PAIRWISE = [
    (0, 1, 0.78),   # 张三 ↔ 软件工程师
    (2, 7, 0.81),   # 住杭州 ↔ 去了西湖
    (3, 4, 0.72),   # 喜欢喝咖啡 ↔ 不喜欢香菜
    (6, 10, 0.71),  # 准备考研 ↔ 在减肥
    (5, 2, 0.70),   # 养猫 ↔ 住杭州
]


def build_nodes():
    nodes = []
    for i, (content, mtype, imp, access, age) in enumerate(MEMORIES):
        gen = compute_generation(imp, access, age, mtype)
        size = node_size(access, imp)
        nodes.append({
            "id": "m%d" % i,
            "content": content,
            "type": mtype,
            "imp": imp,
            "access": access,
            "age": age,
            "gen": gen,
            "size": size,
            "r": draw_radius(size),
        })
    return nodes


def build_edges(nodes):
    """照抄 MemoryGraphService.buildLinks 的三步。

    ① HIERARCHY：画像类（BASIC/SEMANTIC/WORKING）直接挂根节点
    ② SIMILARITY：余弦 >= 阈值（这里用固定的 PAIRWISE 表代表）
    ③ 兜底：非画像类且一条边都没有 → 补一条 HIERARCHY，避免孤立点
    """
    edges = []
    anchored = set()

    for n in nodes:
        if n["type"] != "EPISODIC":
            edges.append((ROOT_ID, n["id"], min(1.0, n["imp"] / 10.0), "HIERARCHY"))
            anchored.add(n["id"])

    touched = set()
    for a, b, w in PAIRWISE:
        edges.append((nodes[a]["id"], nodes[b]["id"], w, "SIMILARITY"))
        touched.add(nodes[a]["id"])
        touched.add(nodes[b]["id"])

    for n in nodes:
        if n["id"] not in anchored and n["id"] not in touched:
            edges.append((ROOT_ID, n["id"], min(1.0, n["imp"] / 10.0), "HIERARCHY"))
            anchored.add(n["id"])

    return edges


def layout(nodes):
    """照抄前端 computeFixedLayout：按分代分环，环内均匀铺开，每环加固定相位偏移。

    相位 0.62 * ringIndex 是为了避免各环节点沿同一半径排成一列（视觉上像辐条）。
    """
    pos = {ROOT_ID: (CX, CY, ROOT_SIZE, None)}
    by_gen = {g: [] for g in RING_ORDER}
    for n in nodes:
        by_gen[n["gen"]].append(n)

    for ring_index, gen in enumerate(RING_ORDER):
        lst = by_gen[gen]
        if not lst:
            continue
        radius = RING_RADIUS[gen]
        phase = ring_index * 0.62
        for i, n in enumerate(lst):
            angle = phase + (i / len(lst)) * math.pi * 2.0
            x = CX + math.cos(angle) * radius
            y = CY + math.sin(angle) * radius
            pos[n["id"]] = (x, y, n["r"], angle)
    return pos


def pick_ring_label_angle(pos, gen, placed):
    """给环挑一个不会压住节点、也不压住其它环标签的角度。

    静态图和可交互界面不同：界面上环标签只是图例里的一行，压不到东西；
    图上画出来就必须自己避让。四个环的圆都过 (CX-r, CY) 附近，
    只避让节点的话四个标签会全挤到左侧同一处。
    """
    radius = RING_RADIUS[gen]
    best, best_score = -math.pi / 2, -1.0
    for step in range(0, 360, 3):
        ang = math.radians(step)
        lx = CX + math.cos(ang) * radius
        ly = CY + math.sin(ang) * radius
        d_nodes = min(math.hypot(lx - p[0], ly - p[1]) for p in pos.values())
        # 标签压标签比标签压节点更糟，所以给已放置标签的距离乘一个惩罚系数
        d_labels = min([math.hypot(lx - q[0], ly - q[1]) for q in placed] or [1e9])
        score = min(d_nodes, d_labels * 1.6)
        if score > best_score:
            best, best_score = ang, score
    return best


def esc(s):
    return (s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))


def label_anchor(x, y, r, angle):
    """把标签放到节点的「半径外侧」，并按方向选对齐方式，避免互相压字。"""
    dx, dy = math.cos(angle), math.sin(angle)
    if abs(dx) >= 0.35:
        tx = x + dx * (r + 7)
        ty = y + dy * (r + 7) + 3.5
        anchor = "start" if dx > 0 else "end"
    else:
        tx = x
        ty = y + (r + 13) if dy > 0 else y - (r + 8)
        anchor = "middle"
    return tx, ty, anchor


def svg(nodes, edges, pos, ring_angles):
    out = []
    add = out.append
    total_h = VIEW_H + LEGEND_H

    add('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" '
        'width="%d" height="%d" font-family="%s">'
        % (VIEW_W, total_h, int(VIEW_W), int(total_h), FONT))
    add('<title>记忆脑图（分代同心环布局）</title>')
    add('<rect x="0" y="0" width="%d" height="%d" fill="%s"/>' % (VIEW_W, total_h, SURFACE))

    # 标题
    add('<text x="32" y="38" font-size="19" font-weight="600" fill="%s">'
        '记忆脑图 · 分代同心环布局</text>' % INK)
    add('<text x="32" y="60" font-size="12" fill="%s">'
        '数据源 GET /api/memory/graph?userId= — 根节点在圆心，越靠外环越接近被回收</text>' % INK_MUTED)

    # 同心环参考线（recessive：不抢数据的注意力）
    for gen in RING_ORDER:
        add('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="none" stroke="%s" stroke-width="1"/>'
            % (CX, CY, RING_RADIUS[gen], GRID))

    # 环标签：放在挑出来的安全角度上，标注该环的分代
    for gen in RING_ORDER:
        ang = ring_angles[gen]
        radius = RING_RADIUS[gen]
        lx = CX + math.cos(ang) * radius
        ly = CY + math.sin(ang) * radius
        anchor = "middle"
        if math.cos(ang) > 0.35:
            anchor = "start"
        elif math.cos(ang) < -0.35:
            anchor = "end"
        # 注意：SVG 作为独立文件时按 XML 解析，&nbsp; 不是预定义实体，只能用普通分隔符
        add('<text x="%.1f" y="%.1f" font-size="10.5" fill="%s" text-anchor="%s" '
            'font-style="italic">%s · %gpx</text>'
            % (lx, ly, INK_MUTED, anchor, esc(gen), radius))

    # 连线：先画归属边（实线、中性色），再画语义边（虚线、序列色）压在上面
    for edge_type, stroke, dash in (
            ("HIERARCHY", LINK_HIERARCHY, None),
            ("SIMILARITY", LINK_SIMILARITY, "3 3")):
        add('<g stroke="%s" opacity="%.2f"%s>'
            % (stroke, 0.45 if edge_type == "HIERARCHY" else 0.55,
               ' stroke-dasharray="%s"' % dash if dash else ''))
        for s, t, w, etype in edges:
            if etype != edge_type:
                continue
            x1, y1 = pos[s][0], pos[s][1]
            x2, y2 = pos[t][0], pos[t][1]
            add('<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke-width="%.1f"/>'
                % (x1, y1, x2, y2, 1.6 if w > 0.85 else 1.0))
        add('</g>')

    # 记忆节点（先画，根最后画压在中心）
    for n in nodes:
        x, y, r, _ = pos[n["id"]]
        add('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s" stroke="%s" stroke-width="2">'
            '<title>%s\\n分代：%s｜类型：%s｜重要度：%d｜访问：%d 次｜年龄：%d 天</title></circle>'
            % (x, y, r, COLOR[n["gen"]], SURFACE, esc(n["content"]), n["gen"],
               n["type"], n["imp"], n["access"], n["age"]))

    for n in nodes:
        x, y, r, angle = pos[n["id"]]
        tx, ty, anchor = label_anchor(x, y, r, angle)
        add('<text x="%.1f" y="%.1f" font-size="10" fill="%s" text-anchor="%s">%s</text>'
            % (tx, ty, INK_MUTED, anchor, esc(n["content"])))

    # 根节点
    rx, ry = pos[ROOT_ID][0], pos[ROOT_ID][1]
    add('<circle cx="%.1f" cy="%.1f" r="%.1f" fill="%s" stroke="%s" stroke-width="2">'
        '<title>%s（根节点）</title></circle>' % (rx, ry, ROOT_SIZE, ROOT_COLOR, SURFACE, ROOT_LABEL))
    add('<text x="%.1f" y="%.1f" font-size="12" font-weight="600" fill="%s" '
        'text-anchor="middle">%s</text>' % (rx, ry + 46, INK_MUTED, ROOT_LABEL))

    # ---------------------------------------------------------------- 图例
    ly0 = VIEW_H + 16
    add('<line x1="0" y1="%.1f" x2="%d" y2="%.1f" stroke="%s" stroke-width="1"/>'
        % (VIEW_H, int(VIEW_W), VIEW_H, GRID))

    x = 32.0
    for gen in RING_ORDER:
        add('<circle cx="%.1f" cy="%.1f" r="7" fill="%s"/>' % (x + 7, ly0 + 14, COLOR[gen]))
        add('<text x="%.1f" y="%.1f" font-size="12" fill="%s">%s</text>'
            % (x + 20, ly0 + 18, INK, esc(GEN_LABEL[gen])))
        x += 20 + len(GEN_LABEL[gen]) * 8.2 + 26

    add('<circle cx="%.1f" cy="%.1f" r="7" fill="%s"/>' % (x + 7, ly0 + 14, ROOT_COLOR))
    add('<text x="%.1f" y="%.1f" font-size="12" fill="%s">根节点</text>'
        % (x + 20, ly0 + 18, INK))

    hier_n = sum(1 for e in edges if e[3] == "HIERARCHY")
    sim_n = sum(1 for e in edges if e[3] == "SIMILARITY")

    # 线型图例
    ly1 = ly0 + 40
    add('<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke="%s" stroke-width="2" opacity="0.6"/>'
        % (32.0, ly1, 52.0, ly1, LINK_HIERARCHY))
    add('<text x="60" y="%.1f" font-size="12" fill="%s">HIERARCHY 归属边（%d 条）—— 画像类记忆直接挂根节点</text>'
        % (ly1 + 4, INK, hier_n))

    ly2 = ly1 + 20
    add('<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke="%s" stroke-width="2" '
        'stroke-dasharray="3 3" opacity="0.7"/>' % (32.0, ly2, 52.0, ly2, LINK_SIMILARITY))
    add('<text x="60" y="%.1f" font-size="12" fill="%s">SIMILARITY 语义边（%d 条）—— 余弦相似度 ≥ 0.7</text>'
        % (ly2 + 4, INK, sim_n))

    add('<text x="32" y="%.1f" font-size="11.5" fill="%s">'
        '环半径 = 分代（OLD 120px → SURVIVOR 200 → NEW 280 → PENDING_GC 320）；'
        '节点大小 = 10 × (1 + ln(1+访问次数) × 重要度/10)，绘制时 clamp 到 6~30</text>'
        % (ly2 + 30, INK_MUTED))
    add('<text x="32" y="%.1f" font-size="11.5" fill="%s">'
        'EPISODIC 不在"画像类"里；它靠 SIMILARITY 边关联，<tspan font-weight="600">一条边都没有时补一条归属边</tspan>，避免孤立点</text>'
        % (ly2 + 50, INK_MUTED))
    add('<text x="32" y="%.1f" font-size="11.5" fill="%s">'
        'PENDING_GC 表示下次衰减（每日 03:00）就会被删除：重要度 &lt; 2，或情景记忆超过 60 天</text>'
        % (ly2 + 70, INK_MUTED))
    add('<text x="32" y="%.1f" font-size="11.5" fill="%s">'
        '注：界面里只有半径 ≥ 12 的节点常驻显示标签（其余靠 hover）；本图为了静态可读全部标注，并已把标签移到节点外侧避让</text>'
        % (ly2 + 90, INK_MUTED))

    add('</svg>')
    return "\n".join(out)


def main():
    nodes = build_nodes()
    edges = build_edges(nodes)
    pos = layout(nodes)

    ring_angles = {}
    placed = []
    for g in RING_ORDER:
        ang = pick_ring_label_angle(pos, g, placed)
        ring_angles[g] = ang
        placed.append((CX + math.cos(ang) * RING_RADIUS[g],
                       CY + math.sin(ang) * RING_RADIUS[g]))

    dist = {}
    for n in nodes:
        dist[n["gen"]] = dist.get(n["gen"], 0) + 1
    hier_n = sum(1 for e in edges if e[3] == "HIERARCHY")
    sim_n = sum(1 for e in edges if e[3] == "SIMILARITY")
    sys.stderr.write("分代分布: %s\n" % dist)
    sys.stderr.write("边: HIERARCHY %d / SIMILARITY %d\n" % (hier_n, sim_n))

    # 文件名保持 ASCII：markdown 里的中文路径在部分渲染器/URL 编码下会取不到
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "memory-graph.svg")
    with open(out, "w", encoding="utf-8") as f:
        f.write(svg(nodes, edges, pos, ring_angles))
    sys.stderr.write("已写出 %s\n" % out)


if __name__ == "__main__":
    main()
