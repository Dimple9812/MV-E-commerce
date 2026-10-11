#!/usr/bin/env python3
"""Generates docs/architecture/data-model.md (Mermaid ER diagrams) from each service's Flyway migrations.

    python3 docs/architecture/generate_er.py
"""
import glob
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "docs" / "architecture" / "data-model.md"
SKIP = {"outbox_event", "processed_event"}   # identical in every service; described once


def tables(sql):
    sql = re.sub(r"--[^\n]*", "", sql)
    for m in re.finditer(r"CREATE TABLE (\w+)\s*\((.*?)\n\);", sql, re.S):
        name, body = m.group(1), m.group(2)
        cols, fks, pk = [], [], set()
        for line in body.split("\n"):
            line = line.strip().rstrip(",")
            mpk = re.match(r"PRIMARY KEY \(([^)]*)\)", line)
            if mpk:
                pk |= {c.strip() for c in mpk.group(1).split(",")}
                continue
            mm = re.match(r"(\w+)\s+([A-Za-z]+(?:\(\d+(?:,\s*\d+)?\))?)", line)
            if not mm or mm.group(1).upper() in ("PRIMARY", "UNIQUE", "CONSTRAINT", "CHECK", "FOREIGN", "EXCLUDE"):
                continue
            col, typ = mm.group(1), mm.group(2).lower().replace(",", "_").replace(" ", "")
            typ = re.sub(r"[()]", "_", typ).strip("_")
            key = []
            if "PRIMARY KEY" in line:
                pk.add(col)
            ref = re.search(r"REFERENCES (\w+)", line)
            if ref:
                fks.append((col, ref.group(1)))
                key.append("FK")
            if re.search(r"\bUNIQUE\b", line):
                key.append("UK")
            cols.append([typ, col, key])
        for c in cols:
            if c[1] in pk:
                c[2].insert(0, "PK")
        yield name, cols, fks


def main():
    parts = ["# Data model", "",
             "Generated from the Flyway migrations by `docs/architecture/generate_er.py`. Each service owns its",
             "database (ADR 0006); there are no foreign keys between services, only ids carried in events.", "",
             "Every service also has the same two infrastructure tables:", "",
             "- `outbox_event` (id, event_id, aggregate_type, aggregate_id, event_type, topic, msg_key, payload jsonb,",
             "  status PENDING|PUBLISHED|FAILED, attempts, next_attempt_at, last_error, created_at, published_at):",
             "  events written in the business transaction and published by `OutboxPublisher` (ADR 0003).",
             "- `processed_event` (consumer, event_id, processed_at; primary key (consumer, event_id)): consumer",
             "  deduplication written in the same transaction as the consumer's update (ADR 0005).", ""]
    for f in sorted(glob.glob(str(ROOT / "services/*/src/main/resources/db/migration/V*.sql"))):
        svc = pathlib.Path(f).parts[-7]
        lines = ["erDiagram"]
        rels = []
        for name, cols, fks in tables(pathlib.Path(f).read_text()):
            if name in SKIP:
                continue
            lines.append(f"    {name} {{")
            for typ, col, key in cols:
                lines.append(f"        {typ} {col}{(' ' + ','.join(key)) if key else ''}")
            lines.append("    }")
            for col, target in fks:
                rels.append(f'    {target} ||--o{{ {name} : "{col}"')
        parts += [f"## {svc}", "", "```mermaid", *lines, *rels, "```", ""]
    OUT.write_text("\n".join(parts))
    print("wrote", OUT.relative_to(ROOT))


if __name__ == "__main__":
    main()
