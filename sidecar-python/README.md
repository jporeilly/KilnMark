# AI sidecar (optional)

AI is built into the Java server from v0.1 ([ADR 0012](../docs/adr/0012-ai-runtime.md)). The
server calls models over HTTP: llama.cpp, Ollama, vLLM or any OpenAI-compatible endpoint, and
hosted providers with an API key.

This folder is reserved for an optional Python service, used only if a model Kilnmark needs has no
HTTP-served route. It would be called by the server over gRPC with Arrow batches, and follow the
same model settings and egress rules as the server. Nothing here is planned for v0.1.
