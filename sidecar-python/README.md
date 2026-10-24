# AI sidecar

Reserved for v0.4: an optional Python service for AI work (descriptions, LLM classification,
embeddings), called by the server over gRPC with Arrow batches. It uses local models (Ollama) by
default; hosted models only when a customer chooses them.
