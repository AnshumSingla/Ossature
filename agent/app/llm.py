"""Model factory. Returns a LangChain chat model, or a stub when no API key is set."""

from __future__ import annotations

from langchain_core.language_models.fake_chat_models import FakeListChatModel

from app.config import get_settings


def chat_model(model: str, temperature: float = 0.3):
    """A chat model for `model`, or a deterministic fake in stub mode.

    Keeping this behind one function means nodes never branch on stub_mode themselves.
    """
    settings = get_settings()
    if settings.stub_mode:
        return FakeListChatModel(responses=[f"[stub:{model}] response"])

    from langchain_openai import ChatOpenAI

    return ChatOpenAI(model=model, temperature=temperature, api_key=settings.openai_api_key)


def fast_model():
    return chat_model(get_settings().llm_model_fast, temperature=0.4)


def council_generator_model():
    return chat_model(get_settings().llm_model_council_generator, temperature=0.5)


def council_critic_model():
    return chat_model(get_settings().llm_model_council_critic, temperature=0.2)


def review_model():
    return chat_model(get_settings().llm_model_review, temperature=0.1)
