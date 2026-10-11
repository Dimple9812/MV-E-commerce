#!/usr/bin/env python3
"""Validates recorded Kafka events against contracts/events.

    python3 scripts/validate-contracts.py                      # the committed examples
    python3 scripts/validate-contracts.py dump/*.jsonl         # events captured from a running stack, one per line

Fails on any event whose type has no contract or whose envelope/payload does not match it.
Capture events with:  kafka-console-consumer.sh --bootstrap-server localhost:9094 --topic order.events --from-beginning
"""
import json
import pathlib
import sys

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource

ROOT = pathlib.Path(__file__).resolve().parent.parent / "contracts" / "events"
BASE = "https://marketflow.dev/contracts/events/"


def load():
    registry = Registry()
    validators = {}
    files = [ROOT / "envelope.schema.json", *sorted((ROOT / "schemas").glob("*.json"))]
    for f in files:
        schema = json.loads(f.read_text())
        registry = registry.with_resource(schema["$id"], Resource.from_contents(schema))
    for f in files[1:]:
        schema = json.loads(f.read_text())
        validators[(schema["x-topic"], schema["properties"]["eventType"]["const"])] = Draft202012Validator(
            schema, registry=registry, format_checker=FormatChecker())
    return validators


def events(paths):
    for p in paths:
        p = pathlib.Path(p)
        if p.suffix == ".jsonl":
            topic = p.stem
            for line in p.read_text().splitlines():
                if line.strip():
                    yield topic, json.loads(line), f"{p.name}"
        else:
            doc = json.loads(p.read_text())
            yield doc["topic"], doc["value"], p.name


def main():
    validators = load()
    paths = sys.argv[1:] or sorted((ROOT / "examples").glob("*.json"))
    checked, failures, seen = 0, 0, set()
    for topic, event, origin in events(paths):
        key = (topic, event.get("eventType"))
        v = validators.get(key)
        checked += 1
        if v is None:
            failures += 1
            print(f"FAIL {origin}: no contract for {key[1]} on {topic}")
            continue
        errs = sorted(v.iter_errors(event), key=lambda e: list(e.path))
        if errs:
            failures += 1
            for e in errs[:3]:
                print(f"FAIL {origin} {key[1]}: {'/'.join(map(str, e.path)) or '<root>'}: {e.message}")
        seen.add(key)
    print(f"{checked} events checked, {len(seen)} distinct event types, {failures} failures, "
          f"{len(validators)} contracts defined")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
