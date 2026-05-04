-- Phase 5.1 — pgvector + embeddings
-- Run after pgvector extension is installed (see docs/MANUAL_SETUP.md §7).
-- If pgvector is NOT installed, this migration will fail at line 1 — that's
-- the signal to follow the manual setup instructions first.

CREATE EXTENSION IF NOT EXISTS vector;

-- Drop the old DOUBLE PRECISION[] column (it was unused — no embeddings stored yet)
ALTER TABLE memory_items DROP COLUMN IF EXISTS embedding_vector;

-- Add a vector column. Default dimension is 384 (matches fastembed BAAI/bge-small-en-v1.5).
-- If you switch to ollama (768) or openai (1536), update both this column and EMBEDDING_DIM.
ALTER TABLE memory_items ADD COLUMN embedding_vector vector(384);

-- HNSW index for fast cosine similarity search.
-- HNSW > IVFFlat for our scale (hundreds of memories): no pre-training, more accurate.
CREATE INDEX IF NOT EXISTS idx_memory_items_embedding
    ON memory_items
    USING hnsw (embedding_vector vector_cosine_ops);
