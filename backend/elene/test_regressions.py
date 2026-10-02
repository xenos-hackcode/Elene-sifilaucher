"""Run with python -m unittest discover -s backend/elene -p test_regressions.py."""
import asyncio
import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch


with patch.dict(os.environ, {}, clear=True):
    spec = importlib.util.spec_from_file_location("elene_test_app", Path(__file__).with_name("main.py"))
    backend = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(backend)


class Socket:
    def __init__(self, accept_error=None):
        self.accepted = False
        self.closed = None
        self.accept_error = accept_error
        self.sent = []

    async def accept(self):
        await asyncio.sleep(0)  # Exercise simultaneous handshakes.
        if self.accept_error:
            raise self.accept_error
        self.accepted = True

    async def close(self, code):
        self.closed = code

    async def send_text(self, message):
        self.sent.append(message)

    async def receive(self):
        return {"type": "websocket.disconnect"}


class Providers(unittest.TestCase):
    def test_import_without_provider_keys(self):
        self.assertIsNone(backend.client)
        self.assertIsNone(backend.anthropic_client)

    def test_fallback_without_openai_key(self):
        fallback = object()
        def respond(client, *args):
            if client is None:
                raise RuntimeError("not configured")
            self.assertIs(client, fallback)
            return '{"mode":"chat","text":"hello"}'
        with patch.object(backend, "groq_client", fallback), patch.object(backend, "_openai_compatible_call", respond):
            self.assertIn("hello", backend._llm_reply("test", []))


class Relay(unittest.IsolatedAsyncioTestCase):
    async def test_duplicate_cannot_replace_connected_role(self):
        for factory, peer in [(backend.LaptopSession, "phone"), (backend.PhoneSession, "controller")]:
            sessions = {}
            first, second = Socket(), Socket()
            results = await asyncio.gather(*[
                backend._claim_relay(ws, "secret-token-123", sessions, factory, "agent", peer)
                for ws in [first, second]
            ])
            self.assertIs(results[0].agent, first)
            self.assertIsNone(results[1])
            self.assertEqual(second.closed, 4009)
            self.assertFalse(second.accepted)

    async def test_failed_accept_releases_reservation(self):
        sessions = {}
        with self.assertRaises(RuntimeError):
            await backend._claim_relay(Socket(RuntimeError("offline")), "secret-token-123",
                sessions, backend.LaptopSession, "agent", "phone")
        self.assertEqual(sessions, {})

    async def test_invalid_tokens_do_not_allocate(self):
        sessions = {}
        for token in ["short", "x" * 129]:
            ws = Socket()
            self.assertIsNone(await backend._claim_relay(ws, token, sessions, backend.LaptopSession, "agent", "phone"))
            self.assertEqual(ws.closed, 4001)
        self.assertEqual(sessions, {})

    async def test_capacity_still_allows_existing_peer(self):
        sessions = {"secret-token-123": backend.LaptopSession()}
        with patch.object(backend, "MAX_RELAY_SESSIONS", 1):
            rejected = Socket()
            self.assertIsNone(await backend._claim_relay(rejected, "another-token-123", sessions,
                backend.LaptopSession, "agent", "phone"))
            self.assertEqual(rejected.closed, 4013)
            accepted = Socket()
            self.assertIsNotNone(await backend._claim_relay(accepted, "secret-token-123", sessions,
                backend.LaptopSession, "phone", "agent"))

    async def test_routes_release_empty_sessions(self):
        for route, sessions in [
            (backend.laptop_agent_ws, backend.LAPTOP_SESSIONS),
            (backend.laptop_phone_ws, backend.LAPTOP_SESSIONS),
            (backend.phone_agent_ws, backend.PHONE_SESSIONS),
            (backend.phone_controller_ws, backend.PHONE_SESSIONS),
        ]:
            ws = Socket()
            await route(ws, "secret-token-123")
            self.assertTrue(ws.accepted)
            self.assertEqual(sessions, {})


if __name__ == "__main__":
    unittest.main()
