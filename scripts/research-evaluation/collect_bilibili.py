"""Capture public metadata, including unsuccessful queries, without downloading media.

Uses agent-reach references/video.md's CookieJar -> public search fallback. No
authentication, fingerprint spoofing, proxy switching, or anti-bot bypass.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import html
import http.cookiejar
import json
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request

QUERIES = [
    "沁园春长沙 朗诵", "沁园春长沙 意象赏析", "静夜思 朗读", "观沧海 朗诵",
    "英语th 发音示范", "英语家乡介绍 对话", "英语问路 对话", "水循环 实景观察",
    "种子萌发 观察", "植物蒸腾 实验", "浮力 实验演示", "光的折射 实验",
    "校园消防 疏散演示", "过马路 交通安全演示", "七步洗手法 示范", "长方形面积 演示",
    "分数通分 图示", "分数约分 讲解", "平行四边形面积 剪拼", "小数乘法 算理",
    "勾股定理 验证", "地球自转公转 模型", "太阳能发电 原理", "杠杆原理 实验",
    "尺子测量 方法示范", "蔬菜播种 步骤", "折纸 长方体", "JavaScript数组排序 可视化",
    "Python循环 讲解", "Java递归 流程",
]

def now():
    return datetime.now(timezone.utc).isoformat()

def clean(value):
    return html.unescape(re.sub(r"<[^>]*>", "", str(value or ""))).strip()

def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--delay", type=float, default=2.0)
    parser.add_argument("--limit", type=int, default=8)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    cache_path = args.output / "view-cache.json"
    cache = json.loads(cache_path.read_text(encoding="utf-8")) if cache_path.exists() else {}
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    opener.addheaders = [("User-Agent", "Mozilla/5.0"), ("Referer", "https://www.bilibili.com/"), ("Accept-Encoding", "identity")]
    blocked = False
    def get(url, decode=True):
        nonlocal blocked
        time.sleep(max(0.5, args.delay))
        stamp = now()
        try:
            with opener.open(url, timeout=18) as response:
                data = response.read(3_000_001)
                if len(data) > 3_000_000:
                    return {"requestedAt": stamp, "status": "response_too_large", "httpStatus": response.status}
                value = json.loads(data) if decode else None
                code = value.get("code") if isinstance(value, dict) else None
                if code in (-412, -352, -509):
                    blocked = True
                return {"requestedAt": stamp, "status": "ok" if not decode or code == 0 else "api_error",
                        "httpStatus": response.status, "apiCode": code, "payload": value}
        except urllib.error.HTTPError as error:
            if error.code in (412, 429):
                blocked = True
            return {"requestedAt": stamp, "status": "http_error", "httpStatus": error.code}
        except Exception as error:
            return {"requestedAt": stamp, "status": "request_failed", "errorType": type(error).__name__}
    homepage = get("https://www.bilibili.com/", False)
    write(args.output / "homepage.json", homepage)
    for index, query in enumerate(QUERIES, 1):
        path = args.output / f"query-{index:02d}.json"
        if path.exists():
            continue
        record = {"id": f"BILI-{index:02d}", "query": query, "requestedAt": now(),
                  "source": "bilibili-public-api", "verificationScope": "metadata-only",
                  "humanReviewed": False, "candidates": []}
        if blocked:
            record.update(status="not_requested_after_rate_limit", reason="Collector stops network requests after 412/429 or equivalent API code; no bypass.")
        else:
            url = "https://api.bilibili.com/x/web-interface/search/all/v2?" + urllib.parse.urlencode({"keyword": query, "page": 1})
            search = get(url)
            record.update(status=search["status"], requestedAt=search["requestedAt"], searchUrl=url,
                          httpStatus=search.get("httpStatus"), apiCode=search.get("apiCode"))
            rows = []
            if search["status"] == "ok":
                for block in search["payload"].get("data", {}).get("result", []):
                    if block.get("result_type") == "video":
                        rows.extend(block.get("data", []))
            seen = set()
            for item in rows:
                bvid = item.get("bvid", "")
                if not re.fullmatch(r"BV[0-9A-Za-z]{10}", bvid) or bvid in seen:
                    continue
                seen.add(bvid)
                if len(seen) > min(8, args.limit):
                    break
                if bvid not in cache and not blocked:
                    cache[bvid] = get("https://api.bilibili.com/x/web-interface/view?" + urllib.parse.urlencode({"bvid": bvid}))
                    write(cache_path, cache)
                view = cache.get(bvid, {"status": "not_requested_after_rate_limit"})
                detail = (view.get("payload") or {}).get("data") or {}
                pages = [{"page": p.get("page"), "cid": p.get("cid"), "title": clean(p.get("part")),
                          "durationSeconds": p.get("duration"), "url": f"https://www.bilibili.com/video/{bvid}/?p={p.get('page', 1)}"}
                         for p in detail.get("pages", [])]
                record["candidates"].append({"rank": len(record["candidates"]) + 1, "bvid": bvid,
                    "url": f"https://www.bilibili.com/video/{bvid}/", "searchTitle": clean(item.get("title")),
                    "title": clean(detail.get("title", item.get("title"))), "description": clean(detail.get("desc", item.get("description"))),
                    "author": clean((detail.get("owner") or {}).get("name", item.get("author"))),
                    "durationSeconds": detail.get("duration"), "pages": pages,
                    "viewStatus": view["status"], "viewHttpStatus": view.get("httpStatus"), "viewApiCode": view.get("apiCode"),
                    "verifiedAt": view.get("requestedAt"), "publicationUnix": detail.get("pubdate"),
                    "metadataVerified": view["status"] == "ok" and detail.get("bvid") == bvid})
        write(path, record)
        print(f"{index}/30 {record['status']} candidates={len(record['candidates'])}", flush=True)
    all_records = [json.loads(p.read_text(encoding="utf-8")) for p in sorted(args.output.glob("query-*.json"))]
    summary = {"capturedAt": now(), "queryCount": len(all_records), "requestedQueryCount": sum(r["status"] != "not_requested_after_rate_limit" for r in all_records),
               "successfulQueries": sum(r["status"] == "ok" for r in all_records),
               "candidateCount": sum(len(r["candidates"]) for r in all_records),
               "verifiedCandidateCount": sum(c["metadataVerified"] for r in all_records for c in r["candidates"]),
               "allOutcomesRetained": True, "mediaDownloaded": False, "humanReviewed": False, "queries": all_records}
    write(args.output / "snapshot-index.json", summary)
    print(json.dumps({k: v for k, v in summary.items() if k != "queries"}, ensure_ascii=False), flush=True)

if __name__ == "__main__":
    main()
