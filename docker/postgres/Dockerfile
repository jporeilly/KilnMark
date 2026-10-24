# PostgreSQL 18 with Apache AGE (graph) and pgvector (embeddings).
# The official image already includes the PostgreSQL apt repository that ships both extensions.
FROM postgres:18

RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-18-age postgresql-18-pgvector \
 && rm -rf /var/lib/apt/lists/*

# AGE must be preloaded.
CMD ["postgres", "-c", "shared_preload_libraries=age"]
