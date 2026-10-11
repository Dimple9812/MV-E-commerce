#!/usr/bin/env node
// Inspect and replay dead-lettered Kafka messages.
//
//   node scripts/dlt-replay.mjs list   order.events.dlt                  # show what is there
//   node scripts/dlt-replay.mjs replay order.events.dlt --offset 12       # dry run: what would be sent
//   node scripts/dlt-replay.mjs replay order.events.dlt --offset 12 --execute
//   node scripts/dlt-replay.mjs replay order.events.dlt --consumer order-service --execute   # all for one consumer
//
// Replay re-publishes the ORIGINAL key and value to the ORIGINAL topic (from the kafka_dlt-original-topic
// header). Every consumer group on that topic receives it again; groups that already processed the event skip
// it because consumers deduplicate on eventId (processed_event table). Fix the cause first, otherwise the
// message simply dead-letters again. See docs/runbooks/dlt-replay.md.
//
// KAFKA_BROKERS defaults to localhost:9094 (the Compose EXTERNAL listener).
import { Kafka, Partitioners, logLevel } from 'kafkajs';

const [cmd, topic, ...rest] = process.argv.slice(2);
const opt = (name) => { const i = rest.indexOf(`--${name}`); return i >= 0 ? rest[i + 1] : undefined; };
const execute = rest.includes('--execute');
if (!['list', 'replay'].includes(cmd) || !topic?.endsWith('.dlt')) {
  console.error('usage: dlt-replay.mjs list|replay <topic>.dlt [--offset N] [--consumer NAME] [--execute]');
  process.exit(2);
}

const kafka = new Kafka({ clientId: 'dlt-replay', brokers: (process.env.KAFKA_BROKERS ?? 'localhost:9094').split(','),
  logLevel: logLevel.WARN });
const admin = kafka.admin();
await admin.connect();
const offsets = await admin.fetchTopicOffsets(topic);
await admin.disconnect();
const total = offsets.reduce((n, p) => n + Number(p.high) - Number(p.low), 0);
if (total === 0) { console.log(`${topic} is empty`); process.exit(0); }

// read everything currently in the DLT without committing offsets (a throwaway group, no side effects)
const consumer = kafka.consumer({ groupId: `dlt-replay-${process.pid}-${Date.now()}` });
await consumer.connect();
await consumer.subscribe({ topic, fromBeginning: true });
const records = [];
const h = (m, k) => m.headers?.[k]?.toString();
await new Promise((resolve) => {
  consumer.run({ autoCommit: false, eachMessage: async ({ partition, message }) => {
    records.push({ partition, offset: Number(message.offset), key: message.key, value: message.value,
      originalTopic: h(message, 'kafka_dlt-original-topic'),
      originalPartition: message.headers?.['kafka_dlt-original-partition']?.length === 4
        ? message.headers['kafka_dlt-original-partition'].readInt32BE(0) : undefined, consumer: h(message, 'x-failed-consumer') ?? h(message, 'kafka_dlt-original-consumer-group'),
      cause: h(message, 'kafka_dlt-exception-cause-fqcn') ?? h(message, 'kafka_dlt-exception-fqcn') });
    if (records.length >= total) resolve();
  } });
  setTimeout(resolve, 15000);
});
await consumer.disconnect();

const summary = (r) => {
  let type = '?', id = '?';
  try { const e = JSON.parse(r.value.toString()); type = e.eventType; id = e.eventId; } catch { type = '(not JSON)'; }
  return `p${r.partition}@${r.offset}  ${type}  eventId=${id}  consumer=${r.consumer}  cause=${r.cause}`;
};

if (cmd === 'list') {
  records.sort((a, b) => a.partition - b.partition || a.offset - b.offset).forEach((r) => console.log(summary(r)));
  process.exit(0);
}

const offset = opt('offset'); const who = opt('consumer');
const selected = records.filter((r) => (offset === undefined || r.offset === Number(offset)) && (who === undefined || r.consumer === who));
if (!selected.length) { console.error('nothing matches'); process.exit(1); }
// one DLT record per failed consumer group: replay each distinct original message once
const unique = [...new Map(selected.map((r) => [`${r.originalTopic}|${r.key}|${r.value}`, r])).values()];
for (const r of unique) console.log(`${execute ? "REPLAY" : "would replay"} -> ${r.originalTopic}[${r.originalPartition ?? "by key"}]  ${summary(r)}`);
if (!execute) { console.log('dry run; add --execute to publish'); process.exit(0); }

// Java-compatible murmur2 partitioner; the original partition is used when the DLT header carries it, so the
// replayed event keeps its place in the per-aggregate order.
const producer = kafka.producer({ idempotent: true, maxInFlightRequests: 1, createPartitioner: Partitioners.DefaultPartitioner });
await producer.connect();
for (const r of unique) {
  if (!r.originalTopic) { console.error(`skip p${r.partition}@${r.offset}: no kafka_dlt-original-topic header`); continue; }
  await producer.send({ topic: r.originalTopic, acks: -1,
    messages: [{ key: r.key, value: r.value, partition: r.originalPartition, headers: { 'x-replayed-from': `${topic}:${r.partition}:${r.offset}` } }] });
}
await producer.disconnect();
console.log(`replayed ${unique.length} message(s)`);
