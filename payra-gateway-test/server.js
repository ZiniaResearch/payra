import { createInterface } from "node:readline";

const testPhoneNumber = "+8801309247909";
const testMessage = "Payra test message";
const jobs = new Map();
const gatewayTopic = "gateway";
const workNotice = JSON.stringify({ type: "work_available" });

function queueJob() {
  const id = crypto.randomUUID();
  jobs.set(id, {
    id,
    to: testPhoneNumber,
    text: testMessage,
    state: "queued",
  });
  console.log(`Queued job ${id}.`);
  server.publish(gatewayTopic, workNotice);
}

function claimJob() {
  for (const job of jobs.values()) {
    if (job.state === "completed") continue;

    job.state = "claimed";
    console.log(`Claimed job ${job.id}.`);
    return Response.json({ id: job.id, to: job.to, text: job.text });
  }

  return new Response(null, { status: 204 });
}

async function recordResult(request) {
  const job = jobs.get(request.params.id);
  if (!job) {
    return Response.json({ error: "Unknown job." }, { status: 404 });
  }

  if (job.state === "queued") {
    return Response.json({ error: "Claim the job first." }, { status: 409 });
  }

  let result;
  try {
    result = await request.json();
  } catch {
    return Response.json({ error: "Expected a JSON body." }, { status: 400 });
  }

  if (result?.status !== "simulated_success") {
    return Response.json({ error: "Expected simulated_success." }, { status: 400 });
  }

  if (job.state === "claimed") {
    job.state = "completed";
    console.log(`Job ${job.id} finished with ${result.status}.`);
  }

  return Response.json({ id: job.id, state: job.state });
}

const server = Bun.serve({
  hostname: "0.0.0.0",
  port: 8765,

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

console.log(`Listening on port ${server.port}. Type queue and press Enter.`);

const input = createInterface({ input: process.stdin });
input.on("line", (line) => {
  if (line.trim() === "queue") queueJob();
  else console.log("Type queue to add a fake job.");
});
