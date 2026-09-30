-- V1: the ingest corpus (ADR-0012). The "ingest" schema is created by Flyway
-- (spring.flyway.create-schemas=true). pgvector lives in "public"; the application's connections
-- put it on the search_path (spring.datasource.hikari.connection-init-sql), the DDL below
-- qualifies it explicitly because Flyway's own search_path is the ingest schema alone.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

-- One row per source disclosure. id = 'kap:<disclosureIndex>'.
CREATE TABLE source_document (
    id              text        PRIMARY KEY,
    source          text        NOT NULL,
    external_id     text        NOT NULL,
    ticker          text        NOT NULL,
    title           text        NOT NULL,
    disclosure_class text       NOT NULL,
    disclosure_type text        NOT NULL,
    reason          text        NOT NULL DEFAULT 'NEW',
    related_index   text,
    source_url      text        NOT NULL,
    published_at    timestamptz NOT NULL,
    retrieved_at    timestamptz NOT NULL,
    content_hash    text,
    chunk_count     integer     NOT NULL DEFAULT 0,
    status          text        NOT NULL CHECK (status IN ('PENDING', 'INDEXED', 'FAILED', 'SUPERSEDED', 'BLOCKED')),
    attempts        integer     NOT NULL DEFAULT 0,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source, external_id)
);
CREATE INDEX source_document_ticker_idx ON source_document (ticker);
CREATE INDEX source_document_status_idx ON source_document (status);

-- Spring AI PgVectorStore writes id, content, metadata and embedding (initialize-schema=false, TEXT ids);
-- everything else is generated from the metadata so the store never needs to know about it.
-- The Turkish lexical leg lower-cases with the tr-TR ICU collation first (I/i dotless, İ/i) and
-- then applies the Turkish stemmer.
CREATE TABLE chunk (
    id          text                  PRIMARY KEY,
    content     text                  NOT NULL,
    metadata    jsonb                 NOT NULL DEFAULT '{}'::jsonb,
    embedding   public.vector(1536)   NOT NULL,
    document_id text GENERATED ALWAYS AS (metadata ->> 'documentId') STORED NOT NULL
        REFERENCES source_document (id) ON DELETE CASCADE,
    ticker      text GENERATED ALWAYS AS (metadata ->> 'ticker') STORED NOT NULL,
    content_tsv tsvector GENERATED ALWAYS AS
        (to_tsvector('turkish', lower(content COLLATE "tr-TR-x-icu"))) STORED
);
CREATE INDEX chunk_embedding_hnsw_idx ON chunk USING hnsw (embedding public.vector_cosine_ops);
CREATE INDEX chunk_content_tsv_idx ON chunk USING gin (content_tsv);
CREATE INDEX chunk_ticker_idx ON chunk (ticker);
CREATE INDEX chunk_document_idx ON chunk (document_id);

-- Resumable scan position per (source, ticker).
CREATE TABLE source_cursor (
    source       text        NOT NULL,
    ticker       text        NOT NULL,
    cursor_index bigint      NOT NULL,
    done         boolean     NOT NULL DEFAULT false,
    updated_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source, ticker)
);

-- Parked documents (failed max-attempts times). Never holds document text.
CREATE TABLE dead_letter (
    id            bigserial   PRIMARY KEY,
    source        text        NOT NULL,
    external_id   text        NOT NULL,
    stage         text        NOT NULL,
    error_class   text        NOT NULL,
    error_message text        NOT NULL,
    attempts      integer     NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source, external_id, stage)
);
