"""Solution/Council node - background reference-solution generator (PRD 6.3 node 2).

Runs at most once per session. Skipped entirely when the prompt came from the bank with a
vetted reference already attached. Otherwise a generator/critic pair: one model drafts a
solution, a second critiques it, the generator reconciles the critique into the final answer.
"""

from __future__ import annotations

from langchain_core.messages import HumanMessage, SystemMessage

from app.graph.state import InterviewState
from app.llm import council_critic_model, council_generator_model
from app.prompting import untrusted, with_rules

GENERATOR_SYSTEM = """You are a staff engineer. Produce a reference system design for the prompt.
Return: a short summary, the key components, the main data flows, and the top trade-offs.
Account for any constraints the candidate established in the clarifying Q&A."""

CRITIC_SYSTEM = """You are a demanding reviewer. Given a prompt and a proposed reference design,
list its gaps: missing components, weak or unjustified trade-offs, unaddressed failure modes,
and requirements it skipped. Be specific and terse."""

RECONCILE_SYSTEM = """Revise your reference design to address the critique. Keep it tight.
Return the same structure: summary, components, data flows, trade-offs."""


def council_node(state: InterviewState) -> InterviewState:
    # Fires at most once per session (PRD 6.3). Later turns short-circuit here.
    if state.get("council_done"):
        return {}

    if state.get("has_vetted_reference") and state.get("reference_solution"):
        # Bank prompt with a pre-baked reference: nothing to generate (PRD 6.3).
        return {"council_running": False, "council_done": True}

    # The prompt may be a community submission and the Q&A contains the candidate's own words, so
    # both are untrusted. The draft and critique are model output derived from them, so they are
    # tagged as data too when fed back in.
    prompt = untrusted("question_prompt", state["prompt"], 4000)
    clarifying = untrusted("conversation", _format_clarifying(state.get("clarifying_qa", [])), 6000)

    generator = council_generator_model()
    critic = council_critic_model()

    draft = generator.invoke(
        [
            SystemMessage(content=with_rules(GENERATOR_SYSTEM)),
            HumanMessage(content=f"{prompt}\n\nClarifying Q&A:\n{clarifying}"),
        ]
    ).content

    critique = critic.invoke(
        [
            SystemMessage(content=with_rules(CRITIC_SYSTEM)),
            HumanMessage(content=f"{prompt}\n\nProposed design:\n{untrusted('draft', draft, 8000)}"),
        ]
    ).content

    final = generator.invoke(
        [
            SystemMessage(content=with_rules(RECONCILE_SYSTEM)),
            HumanMessage(
                content=(
                    f"{prompt}\n\nYour draft:\n{untrusted('draft', draft, 8000)}"
                    f"\n\nCritique:\n{untrusted('critique', critique, 4000)}"
                )
            ),
        ]
    ).content

    return {
        "reference_solution": {"summary": final, "diagram": {"nodes": [], "edges": []}},
        "council_running": False,
        "council_done": True,
    }


def _format_clarifying(pairs: list[dict[str, str]]) -> str:
    if not pairs:
        return "(none yet)"
    return "\n".join(
        f"Q: {p.get('candidateQuestion', '')}\nA: {p.get('aiAnswer', '')}" for p in pairs
    )
