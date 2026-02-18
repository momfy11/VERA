# VERA  
### Voice-enabled Evolving Reasoning Assistant

VERA is a proactive, voice-enabled AI assistant designed to help users manage everyday tasks more efficiently. Unlike traditional chatbots, VERA takes initiative, adapts over time through memory, and supports real-time voice interaction with interruption handling (barge-in).

---

## 🚀 Features

- 🎙 Real-time voice interaction (always-on while the app is active)
- ⛔ Barge-in support (interrupt the assistant when you start speaking)
- 📅 Calendar analysis and proactive suggestions
- 📧 Email summarization and draft creation
- ✅ Task management and prioritization
- 🧠 Short-term and long-term memory for personalization
- 🔒 Security controls and approval workflows
- 📊 Full audit logging for traceability

---

## 🏗 Architecture

VERA consists of three main components:

### 1️⃣ PWA Client (React)
- Voice engine (VAD + streaming STT)
- Browser-based TTS
- Chat interface and suggestion feed
- WebSocket communication with backend

### 2️⃣ Backend (FastAPI)
- Agent Orchestrator (planning and reasoning)
- Scheduler for proactive behavior
- Tool Layer (Gmail, Calendar, Todo integrations)
- Policy Engine (security rules and approval gates)
- Audit logging

### 3️⃣ Database
- PostgreSQL
- pgvector (for embeddings and semantic memory retrieval)

---

## 🎙 Voice Engine

VERA operates using a state-based interaction model:

- Listening  
- User Speaking  
- Thinking  
- Assistant Speaking  
- Barge-in  

Voice Activity Detection (VAD) runs on the client for low-latency interruption and immediate control over speech playback.

---

## 🔐 Security

- OAuth 2.0 integration
- Encrypted token storage
- Approval gates for sensitive actions
- Prompt injection mitigation
- Scoped API permissions

---

## 📦 Tech Stack

- React (PWA)
- FastAPI
- PostgreSQL + pgvector
- WebSocket
- Whisper (STT)
- Web Speech API (TTS)

---

## 🎯 Vision

VERA is designed as a context-aware, voice-first, evolving assistant that combines intelligent reasoning, secure automation, and user-centric design.
