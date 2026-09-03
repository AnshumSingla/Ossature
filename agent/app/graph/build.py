"""Assemble the interview graph.

The one piece that needs deliberate design (PRD 6.3, "Concurrency note") is the Council
node running in parallel with the live Interview AI conversation and merging state back in -
a fan-out / fan-in, not a linear chain. The Review / Summary / Debrief nodes are
straightforward triggers and are exposed as plain callables in `app.turns` rather than
wired as timed edges here.
"""

from __future__ import annotations

from langgraph.graph import END, START, StateGraph

from app.graph.nodes.council import council_node
from app.graph.nodes.interview import interview_node
from app.graph.state import InterviewState


def _join(state: InterviewState) -> InterviewState:
    """Fan-in point. Council result (if any) is already merged via state reducers."""
    return {"council_running": False}


def build_interview_graph():
    builder = StateGraph(InterviewState)

    builder.add_node("interview", interview_node)
    builder.add_node("council", council_node)
    builder.add_node("join", _join)

    # Fan-out: both branches run from START on every invocation.
    builder.add_edge(START, "interview")
    builder.add_edge(START, "council")

    # Fan-in.
    builder.add_edge("interview", "join")
    builder.add_edge("council", "join")
    builder.add_edge("join", END)

    return builder.compile()


interview_graph = build_interview_graph()
