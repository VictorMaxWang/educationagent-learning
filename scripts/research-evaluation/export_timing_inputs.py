"""Export verified frozen feature inputs for a dependency-free server timing run."""
import argparse
import json
from pathlib import Path
import struct

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--decisions",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args()
    rows=[]
    for path in sorted(args.decisions.glob("BILI-*.json")):
        entry=json.loads(path.read_text(encoding="utf-8"))
        value=entry.get("decision",{}).get("audit",{}).get("optimizerInput")
        if value is not None:
            rows.append(value)
    if not rows:
        raise SystemExit("No verified live feature inputs. Timing export not created.")
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open("wb") as output:
        def integer(value):output.write(struct.pack(">i",value))
        def text(value):
            encoded=value.encode("utf-8");integer(len(encoded));output.write(encoded)
        def strings(values):
            integer(len(values))
            for value in sorted(values):text(value)
        text("xiaotao-optimizer-timing-v1");integer(len(rows))
        for row in rows:
            strings(row["targetGoals"]);integer(row["budgetSeconds"]);output.write(bytes([bool(row["preferenceAvailable"])]))
            integer(len(row["candidates"]))
            for candidate in row["candidates"]:
                text(candidate["id"]);integer(candidate["durationSeconds"]);strings(candidate["goals"]);text(candidate["purpose"])
                for name in ("relevance","complementarity","preference"):output.write(struct.pack(">d",candidate[name]))
                output.write(bytes([bool(candidate["eligible"])]));text(candidate["duplicateGroup"])
            integer(len(row["pairSimilarities"]))
            for key,value in sorted(row["pairSimilarities"].items()):text(key);output.write(struct.pack(">d",value))
    print(f"Exported {len(rows)} real fixed feature inputs; no model credentials or prompts")

if __name__=="__main__":main()
