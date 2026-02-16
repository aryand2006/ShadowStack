"""
FastAPI inference service for the ShadowStack Migration Embedder.

Endpoints:
    POST /embed           — embed a single code snippet
    POST /embed-pair      — embed a (before, after) migration pair
    POST /similar         — find similar migrations via pgvector
    POST /predict-acceptance — predict developer acceptance probability
    GET  /health          — liveness check
    GET  /metrics         — model performance metrics
"""

from __future__ import annotations

import logging
import os
import time
from contextlib import asynccontextmanager
from typing import Optional

import numpy as np
import psycopg2
import psycopg2.extras
import torch
from fastapi import FastAPI, HTTPException
from pgvector.psycopg2 import register_vector
from pydantic import BaseModel, Field

from .model import CodeMigrationEncoder

logger = logging.getLogger(__name__)

# ── Configuration ──────────────────────────────────────────────────────────────

MODEL_CHECKPOINT = os.getenv("MODEL_CHECKPOINT", "./checkpoints/best_model.pt")
BACKBONE_NAME = os.getenv("BACKBONE_NAME", "microsoft/codebert-base")
EMBEDDING_DIM = int(os.getenv("EMBEDDING_DIM", "768"))

DB_HOST = os.getenv("DB_HOST", "localhost")
DB_PORT = int(os.getenv("DB_PORT", "5432"))
DB_NAME = os.getenv("DB_NAME", "shadowstack")
DB_USER = os.getenv("DB_USER", "shadowstack")
DB_PASSWORD = os.getenv("DB_PASSWORD", "")

# ── Global State ───────────────────────────────────────────────────────────────

_model: Optional[CodeMigrationEncoder] = None
_device: Optional[torch.device] = None
_db_conn: Optional[psycopg2.extensions.connection] = None

_request_count: int = 0
_total_latency_ms: float = 0.0
_error_count: int = 0


def _get_device() -> torch.device:
    if torch.cuda.is_available():
        return torch.device("cuda")
    if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
        return torch.device("mps")
    return torch.device("cpu")


def _load_model() -> CodeMigrationEncoder:
    """Load model from checkpoint or initialize fresh."""
    global _model, _device
    _device = _get_device()
    logger.info("Using device: %s", _device)

    model = CodeMigrationEncoder(
        backbone_name=BACKBONE_NAME,
        embedding_dim=EMBEDDING_DIM,
    )

    if os.path.exists(MODEL_CHECKPOINT):
        logger.info("Loading checkpoint from %s", MODEL_CHECKPOINT)
        checkpoint = torch.load(MODEL_CHECKPOINT, map_location=_device, weights_only=False)
        model.load_state_dict(checkpoint["model_state_dict"])
    else:
        logger.warning("No checkpoint found at %s — using untrained model", MODEL_CHECKPOINT)

    model.to(_device)
    model.eval()
    _model = model
    return model


def _get_db_connection() -> psycopg2.extensions.connection:
    """Get or create a database connection with pgvector registered."""
    global _db_conn
    if _db_conn is None or _db_conn.closed:
        _db_conn = psycopg2.connect(
            host=DB_HOST,
            port=DB_PORT,
            dbname=DB_NAME,
            user=DB_USER,
            password=DB_PASSWORD,
        )
        register_vector(_db_conn)
        logger.info("Database connection established: %s:%d/%s", DB_HOST, DB_PORT, DB_NAME)
    return _db_conn


# ── Lifespan ───────────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load model on startup, cleanup on shutdown."""
    logger.info("Starting ShadowStack Embedder Service")
    _load_model()
    yield
    logger.info("Shutting down ShadowStack Embedder Service")
    if _db_conn and not _db_conn.closed:
        _db_conn.close()


# ── FastAPI App ────────────────────────────────────────────────────────────────

app = FastAPI(
    title="ShadowStack Migration Embedder",
    description="Code migration pair embedding and similarity search service",
    version="1.0.0",
    lifespan=lifespan,
)


# ── Request/Response Models ────────────────────────────────────────────────────

class EmbedRequest(BaseModel):
    code: str = Field(..., description="Code snippet to embed", min_length=1)

class EmbedPairRequest(BaseModel):
    before_code: str = Field(..., description="Legacy code snippet", min_length=1)
    after_code: str = Field(..., description="Modernized code snippet", min_length=1)

class SimilarRequest(BaseModel):
    code: Optional[str] = Field(None, description="Code snippet to search with")
    embedding: Optional[list[float]] = Field(None, description="Pre-computed 768-dim embedding")
    limit: int = Field(10, ge=1, le=100, description="Max results to return")

class PredictAcceptanceRequest(BaseModel):
    before_code: str = Field(..., description="Legacy code snippet", min_length=1)
    after_code: str = Field(..., description="Modernized code snippet", min_length=1)

class EmbeddingResponse(BaseModel):
    embedding: list[float]
    dim: int

class SimilarResult(BaseModel):
    id: str
    rule_id: str
    rule_name: str
    before_snippet: str
    after_snippet: str
    risk_tier: str
    confidence_score: float
    developer_accepted: Optional[bool]
    distance: float

class SimilarResponse(BaseModel):
    results: list[SimilarResult]
    query_embedding_dim: int

class AcceptancePrediction(BaseModel):
    probability: float
    confidence: str
    neighbor_count: int
    pair_embedding: list[float]

class HealthResponse(BaseModel):
    status: str
    model_loaded: bool
    device: str
    db_connected: bool

class MetricsResponse(BaseModel):
    total_requests: int
    avg_latency_ms: float
    error_count: int
    model_parameters: int
    embedding_dim: int
    device: str


# ── Endpoints ──────────────────────────────────────────────────────────────────

@app.post("/embed", response_model=EmbeddingResponse)
async def embed_code(request: EmbedRequest):
    """Embed a single code snippet into a 768-dim vector."""
    global _request_count, _total_latency_ms, _error_count
    start = time.monotonic()
    _request_count += 1

    try:
        if _model is None:
            raise HTTPException(status_code=503, detail="Model not loaded")

        with torch.no_grad():
            embedding = _model.encode_single([request.code])

        emb_list = embedding[0].cpu().tolist()
        elapsed = (time.monotonic() - start) * 1000
        _total_latency_ms += elapsed

        return EmbeddingResponse(embedding=emb_list, dim=len(emb_list))

    except HTTPException:
        raise
    except Exception as e:
        _error_count += 1
        logger.exception("Error in /embed")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/embed-pair", response_model=EmbeddingResponse)
async def embed_pair(request: EmbedPairRequest):
    """Embed a (before, after) migration pair into a fused 768-dim vector."""
    global _request_count, _total_latency_ms, _error_count
    start = time.monotonic()
    _request_count += 1

    try:
        if _model is None:
            raise HTTPException(status_code=503, detail="Model not loaded")

        with torch.no_grad():
            embedding = _model.encode_pair([request.before_code], [request.after_code])

        emb_list = embedding[0].cpu().tolist()
        elapsed = (time.monotonic() - start) * 1000
        _total_latency_ms += elapsed

        return EmbeddingResponse(embedding=emb_list, dim=len(emb_list))

    except HTTPException:
        raise
    except Exception as e:
        _error_count += 1
        logger.exception("Error in /embed-pair")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/similar", response_model=SimilarResponse)
async def find_similar(request: SimilarRequest):
    """Find similar migrations by vector similarity in pgvector."""
    global _request_count, _total_latency_ms, _error_count
    start = time.monotonic()
    _request_count += 1

    try:
        if request.embedding is None and request.code is None:
            raise HTTPException(
                status_code=400, detail="Either 'code' or 'embedding' must be provided"
            )

        if request.embedding is not None:
            query_embedding = np.array(request.embedding, dtype=np.float32)
        else:
            if _model is None:
                raise HTTPException(status_code=503, detail="Model not loaded")
            with torch.no_grad():
                emb_tensor = _model.encode_single([request.code])
            query_embedding = emb_tensor[0].cpu().numpy()

        if len(query_embedding) != EMBEDDING_DIM:
            raise HTTPException(
                status_code=400,
                detail=f"Embedding must be {EMBEDDING_DIM}-dimensional, got {len(query_embedding)}",
            )

        conn = _get_db_connection()
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(
                """
                SELECT id, rule_id, rule_name, before_snippet, after_snippet,
                       risk_tier, confidence_score, developer_accepted,
                       embedding <=> %s::vector AS distance
                FROM migration_entries
                WHERE embedding IS NOT NULL
                ORDER BY embedding <=> %s::vector
                LIMIT %s
                """,
                (query_embedding.tolist(), query_embedding.tolist(), request.limit),
            )
            rows = cur.fetchall()

        results = [
            SimilarResult(
                id=str(row["id"]),
                rule_id=row["rule_id"],
                rule_name=row["rule_name"],
                before_snippet=row["before_snippet"][:500],
                after_snippet=row["after_snippet"][:500],
                risk_tier=row["risk_tier"],
                confidence_score=row["confidence_score"],
                developer_accepted=row["developer_accepted"],
                distance=float(row["distance"]),
            )
            for row in rows
        ]

        elapsed = (time.monotonic() - start) * 1000
        _total_latency_ms += elapsed

        return SimilarResponse(results=results, query_embedding_dim=EMBEDDING_DIM)

    except HTTPException:
        raise
    except psycopg2.Error as e:
        _error_count += 1
        logger.exception("Database error in /similar")
        raise HTTPException(status_code=502, detail=f"Database error: {e}")
    except Exception as e:
        _error_count += 1
        logger.exception("Error in /similar")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/predict-acceptance", response_model=AcceptancePrediction)
async def predict_acceptance(request: PredictAcceptanceRequest):
    """
    Predict the probability a migration will be accepted by a developer,
    based on historical acceptance rates of similar transformations.
    """
    global _request_count, _total_latency_ms, _error_count
    start = time.monotonic()
    _request_count += 1

    try:
        if _model is None:
            raise HTTPException(status_code=503, detail="Model not loaded")

        with torch.no_grad():
            pair_emb = _model.encode_pair([request.before_code], [request.after_code])
        query_embedding = pair_emb[0].cpu().numpy()

        conn = _get_db_connection()
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute(
                """
                SELECT developer_accepted,
                       embedding <=> %s::vector AS distance
                FROM migration_entries
                WHERE embedding IS NOT NULL AND developer_accepted IS NOT NULL
                ORDER BY embedding <=> %s::vector
                LIMIT 50
                """,
                (query_embedding.tolist(), query_embedding.tolist()),
            )
            neighbors = cur.fetchall()

        if not neighbors:
            probability = 0.5
            neighbor_count = 0
            confidence = "low"
        else:
            weighted_accepted = 0.0
            total_weight = 0.0
            for n in neighbors:
                distance = float(n["distance"])
                weight = 1.0 / (distance + 1e-6)
                total_weight += weight
                if n["developer_accepted"]:
                    weighted_accepted += weight

            probability = weighted_accepted / total_weight if total_weight > 0 else 0.5
            neighbor_count = len(neighbors)

            avg_distance = sum(float(n["distance"]) for n in neighbors) / len(neighbors)
            if neighbor_count >= 20 and avg_distance < 0.3:
                confidence = "high"
            elif neighbor_count >= 10 and avg_distance < 0.5:
                confidence = "medium"
            else:
                confidence = "low"

        elapsed = (time.monotonic() - start) * 1000
        _total_latency_ms += elapsed

        return AcceptancePrediction(
            probability=round(probability, 4),
            confidence=confidence,
            neighbor_count=neighbor_count,
            pair_embedding=query_embedding.tolist(),
        )

    except HTTPException:
        raise
    except psycopg2.Error as e:
        _error_count += 1
        logger.exception("Database error in /predict-acceptance")
        raise HTTPException(status_code=502, detail=f"Database error: {e}")
    except Exception as e:
        _error_count += 1
        logger.exception("Error in /predict-acceptance")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health", response_model=HealthResponse)
async def health():
    """Liveness and readiness check."""
    db_connected = False
    try:
        conn = _get_db_connection()
        with conn.cursor() as cur:
            cur.execute("SELECT 1")
        db_connected = True
    except Exception:
        pass

    return HealthResponse(
        status="healthy" if _model is not None else "degraded",
        model_loaded=_model is not None,
        device=str(_device) if _device else "unknown",
        db_connected=db_connected,
    )


@app.get("/metrics", response_model=MetricsResponse)
async def metrics():
    """Model and service performance metrics."""
    param_count = sum(p.numel() for p in _model.parameters()) if _model else 0
    avg_latency = _total_latency_ms / _request_count if _request_count > 0 else 0.0

    return MetricsResponse(
        total_requests=_request_count,
        avg_latency_ms=round(avg_latency, 2),
        error_count=_error_count,
        model_parameters=param_count,
        embedding_dim=EMBEDDING_DIM,
        device=str(_device) if _device else "unknown",
    )
