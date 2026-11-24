import { Database } from "bun:sqlite";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";

const testPhoneNumber = "+8801307108634";
const testMessage = "Payra test message";
const gatewayTopic = "gateway";
const workNotice = JSON.stringify({ type: "work_available" });
const commandHelp = "Commands: queue, simulate-failure, drop-next-ack.";
const statuses = new Set(["sent", "failed", "partial", "unknown", "too_long"]);
const database = new Database(
  process.env.PAYRA_TEST_DB ?? fileURLToPath(new URL("gateway-test.sqlite", import.meta.url)),
  { strict: true },
);
database.exec(`
  PRAGMA journal_mode = WAL;
  CREATE TABLE IF NOT EXISTS attempts (
    position INTEGER PRIMARY KEY AUTOINCREMENT,
    id TEXT NOT NULL,
    attempt INTEGER NOT NULL,
    recipient TEXT NOT NULL,
    message TEXT NOT NULL,
    state TEXT NOT NULL DEFAULT 'queued',
    report TEXT,
    UNIQUE (id, attempt)
  );
  CREATE INDEX IF NOT EXISTS attempts_queue ON attempts (state, position);
  CREATE UNIQUE INDEX IF NOT EXISTS attempts_claimed ON attempts (state) WHERE state = 'claimed';
`);

const insertAttempt = database.query(
  "INSERT INTO attempts (id, attempt, recipient, message) VALUES (?, ?, ?, ?)",
);
const findAttempt = database.query("SELECT * FROM attempts WHERE id = ? AND attempt = ?");
const findClaim = database.query("SELECT * FROM attempts WHERE state = 'claimed'");
const findQueued = database.query("SELECT * FROM attempts WHERE state = 'queued' ORDER BY position LIMIT 1");
const markClaimed = database.query("UPDATE attempts SET state = 'claimed' WHERE id = ? AND attempt = ?");
const saveOutcome = database.query("UPDATE attempts SET state = 'completed', report = ? WHERE id = ? AND attempt = ?");
let dropNextAcknowledgment = false;

function queueJob() {
  const id = crypto.randomUUID();
  insertAttempt.run(id, 1, testPhoneNumber, testMessage);
  console.log(`Queued job ${id} attempt 1.`);
  server.publish(gatewayTopic, workNotice);
}

const takeJob = database.transaction(() => {
  const claimed = findClaim.get();
  if (claimed) return claimed;
  const queued = findQueued.get();
  if (queued) markClaimed.run(queued.id, queued.attempt);
  return queued;
});

function claimJob() {
  const job = takeJob.immediate();
  if (!job) return new Response(null, { status: 204 });
  const verb = job.state === "claimed" ? "Returned claim for" : "Claimed";
  console.log(`${verb} job ${job.id} attempt ${job.attempt}.`);
  return Response.json({ id: job.id, attempt: job.attempt, to: job.recipient, text: job.message });
}

function parseReport(value) {
  if (value === null || typeof value !== "object") return null;
  const { attempt, status, partCount, errorCode, retryable } = value;
  if (!Number.isSafeInteger(attempt) || attempt < 1 || !statuses.has(status) ||
      (partCount !== null && (!Number.isSafeInteger(partCount) || partCount < 0)) ||
      (errorCode !== null && !Number.isSafeInteger(errorCode)) ||
      typeof retryable !== "boolean" || (retryable && status !== "failed")) {
    return null;
  }
  return { attempt, status, partCount, errorCode, retryable };
}

function completeAttempt(id, report) {
  const job = findAttempt.get(id, report.attempt);
  if (!job) return { error: "Unknown job attempt.", status: 404 };

  const serialized = JSON.stringify(report);
  if (job.report !== null) {
    return job.report === serialized
      ? { replayed: true }
      : { error: "This attempt already has a different result.", status: 409 };
  }
  if (job.state !== "claimed") return { error: "Claim the attempt first.", status: 409 };

  saveOutcome.run(serialized, id, report.attempt);
  const queuedRetry = report.retryable && report.attempt === 1;
  if (queuedRetry) insertAttempt.run(id, 2, job.recipient, job.message);
  return { queuedRetry };
}

const saveReport = database.transaction(completeAttempt);

function printOutcome(id, report, outcome) {
  if (outcome.replayed) {
    console.log(`Acknowledged replay for job ${id} attempt ${report.attempt}.`);
    return;
  }
  console.log(`Saved result for job ${id} attempt ${report.attempt}: ${report.status}.`);
  if (outcome.queuedRetry) console.log(`Queued job ${id} attempt 2 at the back of the queue.`);
}

async function recordResult(request) {
  let result;
  try {
    result = await request.json();
  } catch {
    return Response.json({ error: "Expected a JSON body." }, { status: 400 });
  }

  const report = parseReport(result);
  if (!report) return Response.json({ error: "Invalid attempt result." }, { status: 400 });

  const id = request.params.id;
  const outcome = saveReport.immediate(id, report);
  if (outcome.error) return Response.json({ error: outcome.error }, { status: outcome.status });
  printOutcome(id, report, outcome);

  if (dropNextAcknowledgment && !outcome.replayed) {
    dropNextAcknowledgment = false;
    console.log(`Dropping acknowledgment for job ${id} attempt ${report.attempt} after saving it.`);
    // Outlast the gateway's HTTP deadline, even if the client leaves the connection open.
    await Bun.sleep(20_000);
    return new Response(null, { status: 504 });
  }
  return Response.json({ id, attempt: report.attempt });
}

const simulateFailure = database.transaction(() => {
  if (findClaim.get()) return null;
  const id = crypto.randomUUID();
  insertAttempt.run(id, 1, testPhoneNumber, testMessage);
  markClaimed.run(id, 1);
  const report = { attempt: 1, status: "failed", partCount: 1, errorCode: null, retryable: true };
  const outcome = completeAttempt(id, report);
  if (!outcome.queuedRetry) throw new Error("Failure simulation did not queue a retry.");
  return id;
});

const server = Bun.serve({
  hostname: process.env.PAYRA_TEST_HOST ?? "0.0.0.0",
  port: Number(process.env.PAYRA_TEST_PORT ?? 8765),

  routes: {
    "/": { GET: () => new Response("Payra gateway test server is running.") },
    "/jobs/claim": { POST: claimJob },
    "/jobs/:id/result": { POST: recordResult },
  },

  fetch(request, server) {
    if (new URL(request.url).pathname !== "/gateway") {
      return new Response("Route not found.", { status: 404 });
    }

    if (server.upgrade(request)) return;
    return new Response("Expected a WebSocket upgrade.", { status: 400 });
  },

  websocket: {
    open(socket) {
      socket.subscribe(gatewayTopic);
      console.log("Gateway connected.");
    },
    close() {
      console.log("Gateway disconnected.");
    },
  },
});

console.log(`Listening on port ${server.port}. ${commandHelp}`);

const input = createInterface({ input: process.stdin });
input.on("line", (line) => {
  switch (line.trim()) {
    case "queue":
      queueJob();
      break;
    case "simulate-failure": {
      const id = simulateFailure.immediate();
      if (id === null) {
        console.log("Wait for the active attempt before simulating a failure.");
        break;
      }
      console.log(`Simulated temporary failure for job ${id}; queued attempt 2.`);
      server.publish(gatewayTopic, workNotice);
      break;
    }
    case "drop-next-ack":
      dropNextAcknowledgment = true;
      console.log("The next new result will be saved without an acknowledgment.");
      break;
    default:
      console.log(commandHelp);
  }
});
