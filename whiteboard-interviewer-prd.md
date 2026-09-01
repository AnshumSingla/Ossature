# Product Requirements Document
## Live Collaborative Whiteboard System Design Interviewer

**Status:** Draft v2.0
**Owner:** Anshum
**Last updated:** September 1, 2026

---

## 1. Overview

A web application that simulates a live system design interview, conducted fully by **voice** on both sides. The candidate explains a system design out loud while drawing it on a shared whiteboard; their speech is converted to text **locally, on-device** and streamed to an AI interviewer. The AI itself only ever generates text — that text is converted back to speech **locally** for playback, so neither STT nor TTS touches a paid API. The interviewer asks clarifying and follow-up questions during the session and, once the candidate submits their diagram, targets specific flaws found by comparing it against an AI-generated reference solution.

The AI interviewer's reasoning is implemented as a **LangGraph** state graph (multiple specialized nodes running the conversation, a background "reference solution" generator, a post-submit review pass, and rolling debrief summarization). Real-time communication (voice transcript streaming, chat turns, diagram snapshots) happens over **WebSockets**. Session orchestration, persistence, and the API layer are built on **Spring Boot**.

---

## 2. Problem Statement

Existing system design interview prep tools fall into two buckets:
- **Static content** (books, YouTube walkthroughs) — no interactivity, no feedback loop.
- **Generic AI chat tools** — candidate types a design in prose; there's no whiteboard, no spoken back-and-forth, and no reference solution grounding the AI's follow-ups.

Neither replicates the real interview experience: a candidate talking through their reasoning out loud while drawing, an interviewer who can answer clarifying questions on the spot, and a debrief that's grounded in a concrete reference design rather than vague impressions. Candidates need a tool that behaves like an actual interviewer sitting across from them.

---

## 3. Goals (MVP)

- **Voice-first interaction.** The candidate speaks; speech-to-text (STT) converts this to a transcript that drives the conversation. This is core to the MVP, not a later add-on.
- Whiteboard for drawing the design (structured shapes: boxes, arrows, labels).
- An Interview AI that can answer the candidate's clarifying questions about the prompt in real time, and later probes their diagram once a reference solution exists.
- A background-generated **reference solution** ("Council" node) that the interviewer uses as ground truth, informed by whatever clarifying questions the candidate actually asked.
- Diagram evaluation is **submit-triggered**: the candidate locks their diagram when done, and a Review node finds concrete gaps versus the reference solution.
- A debrief built from rolling interim summaries across the session, including a comparison to the reference solution.
- A question bank sourced from existing public system-design-question repositories, plus a path for candidates to submit their own questions for community review.
- Keep infrastructure and model costs as low as possible — this is an unfunded personal project.

### Non-Goals (v1)
- Multi-candidate / panel interviews.
- Freehand/hand-drawn sketch input — structured shapes only (no diagram-vision parsing needed).
- Numeric rubric scoring (debrief is qualitative: strengths, gaps, unresolved points, comparison to reference).
- Editing the diagram after submission — once submitted, the candidate can only answer questions, not modify the design.

---

## 4. Primary User

**Persona: Job-seeking engineer preparing for system design interviews.**
Has some system design knowledge, wants realistic practice reacting to follow-up pressure and explaining their reasoning out loud — rather than passively consuming content or typing into a generic chatbot.

---

## 5. Core User Flow

1. Candidate starts a session and either picks a prompt from the question bank, requests a newly generated prompt, or (later) selects a community-submitted one.
2. **Interview AI node** opens the session and is ready to answer clarifying questions about the prompt (e.g. "what's the expected scale?").
3. In parallel, the **Solution/Council node** begins generating a reference solution + diagram for the same prompt in the background.
4. Candidate speaks while drawing; the browser/client streams audio or partial transcripts over WebSocket, STT output is sent to the Interview AI node as it becomes available.
5. Any clarifying Q&A between the candidate and the Interview AI node is relayed to the Solution/Council node, so the reference solution reflects the constraints the candidate actually established (not generic assumptions).
6. Once the Council node's reference solution is ready, it's handed to the Interview AI node, which now has ground truth available. It doesn't switch modes abruptly — it keeps going with the natural flow of the conversation, but questions increasingly draw on the reference (without revealing it) as it becomes relevant.
7. Candidate hits **Submit** when done. The diagram is now locked — no further edits, only continued Q&A.
8. On submit, the diagram is sent to the **Review node**, which compares it against the reference solution and returns a prioritized list of concrete flaws/gaps.
9. While the Review node works (a few seconds), the Interview AI node fills the gap with generic wrap-up questions so there's no dead air.
10. Once flaws come back, the Interview AI node asks the candidate specifically about each flagged point.
11. Throughout the session, a **Summary node** fires on a timer — for a 30-minute session, every ~6 minutes — producing 5 interim summaries stored temporarily.
12. At session end, a **Debrief node** combines the interim summaries (not the raw transcript) into a final debrief: strengths, gaps, unresolved questions, and a comparison against the reference solution.

---

## 6. System Architecture

### 6.1 High-Level Components

```
┌───────────────────────┐        WebSocket          ┌───────────────────────────┐
│   Frontend (Web)        │ <────────────────────────>│   Spring Boot Backend      │
│  - Whiteboard canvas     │  transcript chunks,        │  - WebSocket session mgr  │
│  - Mic capture / STT     │  diagram snapshots,        │  - REST API (auth, CRUD)  │
│  - Chat/transcript view  │  chat turns, AI state       │  - Session state store   │
└───────────────────────┘                             └────────────┬───────────────┘
                                                                     │
                                                                     ▼
                                                        ┌────────────────────────────┐
                                                        │  LangGraph Interview Agent   │
                                                        │  (Python service)            │
                                                        │  - Interview AI node          │
                                                        │  - Solution/Council node      │
                                                        │  - Review node (post-submit)  │
                                                        │  - Summary nodes (timed)      │
                                                        │  - Debrief node               │
                                                        └────────────────────────────┘
```

LangGraph is a Python framework, so the AI orchestration runs as a **separate Python service** the Spring Boot backend calls internally. Spring Boot owns sessions, WebSocket fan-out, persistence, and auth; the Python service owns the LangGraph graph and all LLM calls.

### 6.2 Spring Boot Backend Responsibilities

- **WebSocket session management** — one connection per active session, carrying several event types (see 6.4): streamed transcript chunks, periodic diagram snapshots, chat/AI turns, and lock/submit signals.
- **Session lifecycle REST API** — create session, fetch history, list past sessions, end session, fetch debrief.
- **Auth** — user accounts, session ownership.
- **Persistence** — Postgres for session/user metadata and question bank entries; diagram snapshots and transcript stored as an event log / JSON blobs for replay.
- **Diagram snapshotting** — rather than forwarding every stroke, the backend persists a diagram snapshot on a fixed ~90s cadence (plus one final snapshot on submit). This is purely for persistence/replay — it does **not** trigger AI reasoning, since evaluation is submit-triggered.
- **Bridge to the LangGraph service** — forwards transcript chunks, relayed clarifying Q&A, and the submitted diagram to the appropriate LangGraph node, and pushes the resulting AI turn back to the client.

### 6.3 LangGraph Agent Design

Rather than one monolithic prompt, the interviewer's behavior is split across nodes with distinct jobs:

**1. Interview AI node (the only node that talks to the candidate)**
Handles: opening the prompt, answering clarifying questions live, asking probing questions once the reference solution is ready, filling dead air with generic questions while the Review node runs, and asking targeted flaw-based questions after submit. Every candidate-facing message — regardless of which other node produced the underlying content — is phrased and sent by this node, so the conversation has one consistent voice.

**2. Solution/Council node**
Starts as soon as the session begins, running **concurrently** with the live conversation (a fan-out branch in the graph) — but only when it actually needs to generate something. Behavior branches on where the prompt came from:
- **Prompt pulled from the seeded question bank with an existing vetted reference solution/diagram already attached:** the Council node is skipped entirely. The stored reference is loaded directly and handed to the Interview AI node once ready (near-instant, zero extra LLM cost).
- **Dynamically generated prompt, or a community-submitted question without a vetted reference yet:** the Council node runs for real, as a two-agent generator/critic pair — one model generates a candidate solution, a second model critiques it (gaps, weak trade-offs, missed requirements), and the generator reconciles that critique into the final reference answer. This is still "multiple distinct models," but a generator-critic pair rather than several independently-framed passes voted together. Ingests the clarifying-Q&A transcript relayed from the Interview AI node so the reference reflects constraints the candidate actually set.

Either way, this fires **at most once per session**, so even the multi-model path is a bounded, one-time cost rather than a per-turn one — and bank questions with a pre-baked reference avoid that cost altogether, which is a meaningful lever given the cost constraint in Non-Functional Requirements.

**3. Review node**
Fires exactly once, on submit. Takes the candidate's locked diagram plus the reference diagram, diffs them for gaps (missing components, unaddressed failure modes, scale mismatches), and returns a prioritized list of flaw-based question prompts to the Interview AI node.

**4. Summary node**
Fires on a timer (~every 6 minutes for a 30-minute session), summarizing the preceding chunk of transcript + diagram state into an interim artifact. Five of these accumulate across a full session.

**5. Debrief node**
Fires at session end. Takes the interim summaries (cheaper than re-processing the full transcript) plus the reference solution and produces the final debrief, including a comparison against the reference design.

**Concurrency note:** the Solution/Council node and the live Interview AI conversation need to run in parallel and merge state once the Council node finishes — a fan-out/fan-in pattern in LangGraph terms, not a simple linear chain. This is the one piece of the graph that needs deliberate design (vs. the Review/Summary/Debrief nodes, which are straightforward sequential/timed triggers).

**Resolved:** the Interview AI node doesn't hard-gate on whether the Council node has finished. It goes with the natural flow of the interview — asking whatever's relevant (clarifying or probing) as the conversation calls for it — rather than rigidly switching from "clarifying-only" to "probing" the instant the reference solution lands. In practice the reference will still shape what it asks once available, but that's a gradual shift in what's relevant to ask, not a hard mode switch gated on the Council node's completion.

### 6.4 WebSocket Protocol

One JSON message per WebSocket frame, each with a `type` discriminator so the client/server can route it without inspecting the whole payload:

```jsonc
// Streamed STT output, candidate → server
{ "type": "transcript.partial", "sessionId": "...", "text": "...", "ts": 1735689600000 }
{ "type": "transcript.final",   "sessionId": "...", "text": "...", "ts": 1735689600000 }

// Diagram state, candidate → server, ~90s cadence (see §6.2)
{ "type": "diagram.snapshot", "sessionId": "...", "nodes": [...], "edges": [...], "ts": 1735689600000 }

// Candidate → server, on hitting Submit
{ "type": "diagram.locked", "sessionId": "...", "finalDiagram": { "nodes": [...], "edges": [...] }, "ts": 1735689600000 }

// Chat turn, either direction
{
  "type": "chat.message",
  "sessionId": "...",
  "sender": "candidate" | "ai",
  "text": "...",
  "kind": "clarifying" | "probing" | "filler" | "flaw",   // present on "ai" messages only
  "ts": 1735689600000
}

// Server → candidate, presence while a LangGraph node is working
{ "type": "ai.thinking", "sessionId": "...", "node": "council" | "review", "ts": 1735689600000 }

// Candidate → server, ends the session
{ "type": "session.end", "sessionId": "...", "ts": 1735689600000 }

// Server → candidate, final debrief payload
{
  "type": "debrief.ready",
  "sessionId": "...",
  "strengths": [...],
  "gaps": [...],
  "unresolved": [...],
  "referenceComparison": "...",
  "ts": 1735689600000
}
```

`sessionId` and `ts` (epoch ms) are present on every message so either side can order/dedupe events and the backend can route by session without a separate lookup. Everything else is per-`type`.

**Where does WebSocket actually earn its keep here?** Diagram evaluation is submit-triggered, so it's tempting to think WebSocket is overkill. It isn't — the justification is the *voice channel* and *AI-initiated pushes*, not the diagram: the candidate's speech is a continuous stream (`transcript.partial` events, potentially several per second), the AI's "thinking" state and questions are server-initiated and need to reach the client without polling, and all of this — transcript chunks, diagram snapshots, chat turns — is multiplexed over one persistent, low-latency connection instead of many discrete HTTP calls. If you're asked to defend this in an interview: the diagram side alone might not need it, but a voice-driven conversational interface almost always does.

### 6.5 Speech-to-Text and Text-to-Speech

Both directions of the voice interaction run **locally, on-device**, keeping the marginal cost of voice I/O at zero:
- **Candidate → AI:** local STT (e.g. the browser's built-in speech recognition, or an on-device model) converts the candidate's speech to the transcript the Interview AI node consumes.
- **AI → candidate:** the AI model only ever generates **text**. That text is handed to a local TTS engine on the client for playback — the LangGraph/LLM layer never generates audio itself, so this adds no model cost regardless of how many turns a session has.

This resolves the two open questions from the earlier draft (where STT runs, and whether AI output is voiced): both legs of the voice pipeline are local, and the LLM's job stays scoped to text in and text out. Accuracy of local STT/TTS across browsers/devices is worth validating early, since it's now load-bearing for the whole interaction rather than a nice-to-have.

---

## 7. Functional Requirements

| # | Requirement |
|---|---|
| FR1 | Candidate can create system-design nodes/edges on a whiteboard canvas with typed labels |
| FR2 | Candidate's speech is captured and converted to text via **local, on-device STT**, then streamed to the Interview AI node in near real time |
| FR2a | AI responses are generated as text only; a **local, on-device TTS** engine converts that text to speech for playback to the candidate |
| FR3 | Interview AI node answers candidate clarifying questions about the prompt directly and in real time |
| FR4 | A reference solution + diagram is available before the candidate submits — generated in the background (informed by clarifying questions) for dynamic/community prompts, or loaded directly from the question bank when a vetted reference already exists |
| FR5 | Diagram evaluation (comparison against the reference) is triggered only on Submit, not continuously during drawing |
| FR6 | Once submitted, the diagram is locked; the candidate can no longer edit it, only answer AI questions |
| FR7 | Review node identifies concrete flaws/gaps in the submitted diagram vs. the reference solution and hands them to the Interview AI node as targeted questions |
| FR8 | Interview AI node asks generic filler questions while the Review node is processing, avoiding dead air |
| FR9 | System generates rolling interim summaries on a fixed cadence (~every 6 minutes) across the session |
| FR10 | Final debrief is built from the interim summaries and includes a comparison to the reference solution |
| FR11 | Question bank is seeded from existing public system-design-question sources; candidates can request a newly generated prompt or submit their own for review |
| FR12 | Session state (diagram + transcript + summaries) persists and can be resumed if the connection drops |

---

## 8. Non-Functional Requirements

- **Cost:** this is an unfunded personal project — minimize LLM API and infra spend wherever possible. Voice I/O (STT + TTS) is already free at the margin since both run locally on-device — the LLM layer only ever handles text, so cost scales with model calls, not with how much the candidate talks. Within the model calls: prefer cheaper/smaller models for high-frequency work (Interview AI turns, Summary node), and reserve the costlier multi-model Council calls for work that happens once per session at most (Review node's diff always runs once on submit regardless). Bank questions with a pre-vetted reference solution skip the Council node entirely, which — as the bank grows — should shrink the *average* per-session Council cost over time, since only dynamic/community prompts pay for it. I'd recommend getting real per-session cost estimates from whichever model provider you pick before committing to a 2–3-model council — I don't have reliable current pricing figures to give you here and would rather flag that than guess.
- **Latency:** transcript chunks should reach the backend and be forwarded with minimal delay so the conversation doesn't feel laggy; diagram snapshots on a 90s cadence are for persistence only and aren't latency-sensitive.
- **Reliability:** WebSocket reconnect must restore session state (last transcript position, current diagram, whether it's locked) without data loss.
- **Observability:** log each LangGraph node transition per turn — especially Council-node and Review-node outputs — to debug why a given question was asked.

---

## 9. Question Bank Sourcing

Several existing public GitHub repositories curate system-design interview questions (and in some cases reference diagrams/discussions) that could seed the initial bank — for example, `donnemartin/system-design-primer` is a well-known, actively-referenced one with sample discussions and diagrams. I'd treat this as a starting point to verify yourself (check its license/terms before using its content as "ground truth" material, since reusing text or diagrams verbatim from someone else's repo has its own copyright considerations even for a personal project) rather than something to build on without a look.

Bank questions ideally carry a **pre-vetted reference solution/diagram** alongside the question text itself, so the Solution/Council node can be skipped for those sessions entirely (see §6.3) — this is both a cost saver and a quality lever, since a hand-vetted reference is likely more reliable than one generated on the fly.

Alongside the seeded bank:
- Candidates can request a dynamically generated new prompt (Council node runs).
- Candidates can submit their own question, which goes through a review step before being added — the beginning of a community-contribution loop. Once vetted and added with a reference solution, it behaves like any other bank question (Council node skipped for future candidates who pick it).

**Resolved:** community-submitted questions go through **manual review** by the project owner before being added to the bank (not an automated quality check).

---

## 10. Suggested Phasing

**Phase 1 — Core loop (MVP):** whiteboard (structured shapes) + voice input via STT + WebSocket transcript streaming + Interview AI node answering clarifying questions + Council node generating a reference solution in the background.

**Phase 2 — Submit & review:** diagram lock on submit, Review node diffing against the reference, targeted flaw-based questions, generic filler questions while waiting.

**Phase 3 — Debrief:** timed Summary nodes, final Debrief node with reference-solution comparison.

**Phase 4 — Community & polish:** candidate-submitted questions with review workflow, session history/replay UI, possible TTS for AI responses if cost allows.
