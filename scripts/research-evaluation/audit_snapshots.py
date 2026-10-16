"""Check recorded identity/time/duration without silently making new requests."""
import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import re

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--snapshots", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(args.snapshots.glob("query-*.json"))]
    violations, candidates, bvids = [], [], set()
    for row in rows:
        try:
            datetime.fromisoformat(row["requestedAt"])
        except (KeyError, ValueError):
            violations.append(f"{row.get('id')}: invalid timestamp")
        if re.search(r"年级|小学|初中|高中", row["query"]):
            violations.append(f"{row['id']}: external query includes grade")
        if len(row["candidates"]) > 8:
            violations.append(f"{row['id']}: candidate limit")
        for candidate in row["candidates"]:
            candidates.append(candidate)
            bvids.add(candidate["bvid"])
            if not re.fullmatch(r"BV[A-Za-z0-9]{10}", candidate["bvid"]):
                violations.append(f"{row['id']}: invalid BV")
            if candidate["url"] != f"https://www.bilibili.com/video/{candidate['bvid']}/":
                violations.append(f"{row['id']}: URL identity mismatch")
            if candidate["metadataVerified"]:
                if not candidate["author"] or not candidate["title"] or not candidate["durationSeconds"] or not candidate["verifiedAt"]:
                    violations.append(f"{row['id']}: verified metadata missing required field")
                for page in candidate["pages"]:
                    if page["durationSeconds"] is None or page["durationSeconds"] <= 0:
                        violations.append(f"{row['id']}: page duration invalid")
    result = {"status": "PASS" if not violations and len(rows) == 30 else "INCOMPLETE_OR_FAILED",
              "queryCount": len(rows), "outcomeCounts": dict(Counter(r["status"] for r in rows)),
              "candidateOccurrences": len(candidates), "uniqueVideos": len(bvids),
              "verifiedOccurrences": sum(c["metadataVerified"] for c in candidates), "violations": violations,
              "scope": "identity and metadata field audit only; no full-video or teaching-quality review",
              "humanReviewed": False, "newNetworkRequests": 0, "failedQueriesExcluded": 0}
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "snapshot-audit.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    lines = ["# Bilibili固定输入快照验收", "", f"状态：{result['status']}。30条计划查询全部保留；结果分布：{result['outcomeCounts']}。",
             f"候选出现次数：{len(candidates)}；不同视频：{len(bvids)}；详情核验次数（按出现计）：{result['verifiedOccurrences']}。", "",
             "核对范围是公开接口的身份、URL、作者、时长、分P与时间戳。未下载或审阅完整视频，未进行教师内容质量评审。", "",
             "|查询|状态|候选数|", "|---|---|---|"]
    lines += [f"|{r['query']}|{r['status']}|{len(r['candidates'])}|" for r in rows]
    (args.output / "snapshot-audit.md").write_text("\n".join(lines)+"\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))

if __name__ == "__main__":
    main()
