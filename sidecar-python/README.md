# AI sidecar (optional)

AI is built into the Java server from v0.1 ([ADR 0012](../docs/adr/0012-ai-runtime.md)): it calls
local models over HTTP (Ollama or any OpenAI-compatible endpoint).

This folder is reserved for an optional Python service, used only if a model Kilnmark needs has no
HTTP-served route. It would be called by the server over gRPC with Arrow batches, and use local
models by default. Nothing here is planned for v0.1.
