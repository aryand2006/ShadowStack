"""
Hybrid retrieval engine for the ShadowStack migration corpus.

Combines multiple retrieval signals:
    1. Vector similarity search via pgvector (semantic matching)
    2. Call graph expansion (find related methods that should migrate together)
    3. Re-ranking based on context similarity (AST structure, library overlap)

The HybridRetriever orchestrates these stages into a single retrieval pipeline
that returns the most relevant historical migrations for a given query.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Optional

import numpy as np
import psycopg2
import psycopg2.extras
from pgvector.psycopg2 import register_vector

logger = logging.getLogger(__name__)


@dataclass
class RetrievalResult:
    """A single result from the hybrid retrieval pipeline."""

    entry_id: str
    rule_id: str
    rule_name: str
    before_snippet: str
    after_snippet: str
    risk_tier: str
    confidence_score: float
    developer_accepted: Optional[bool]
    vector_distance: float
    context_score: float
    final_score: float
    related_method_ids: list[str] = field(default_factory=list)


@dataclass
class RetrievalConfig:
    """Configuration for the hybrid retriever."""

    vector_weight: float = 0.6
    context_weight: float = 0.25
    graph_weight: float = 0.15

    vector_candidates: int = 100
    final_top_k: int = 20

    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "shadowstack"
    db_user: str = "shadowstack"
    db_password: str = ""


class CallGraphExpander:
    """
    Expands a set of migration entries by following call graph relationships.

    Given a method that is being migrated, finds other methods in the same project
    that call or are called by it — these are likely candidates for co-migration.
    """

    def __init__(self, conn: psycopg2.extensions.connection):
        self._conn = conn

    def expand(self, entry_ids: list[str], project_id: Optional[str] = None) -> dict[str, list[str]]:
        """
        For each entry, find related entries in the same project that share
        AST context (callers/callees).

        Args:
            entry_ids: list of migration entry UUIDs to expand from
            project_id: optional project scope

        Returns:
            dict mapping entry_id → list of related entry_ids
        """
        if not entry_ids:
            return {}

        related: dict[str, list[str]] = {eid: [] for eid in entry_ids}

        try:
            with self._conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
                cur.execute(
                    """
                    WITH source_entries AS (
                        SELECT id, ast_context, project_id
                        FROM migration_entries
                        WHERE id = ANY(%s::uuid[])
                    ),
                    related_entries AS (
                        SELECT
                            se.id AS source_id,
                            me.id AS related_id,
                            me.ast_context
                        FROM source_entries se
                        JOIN migration_entries me ON me.project_id = se.project_id
                            AND me.id != se.id
                        WHERE me.ast_context IS NOT NULL
                          AND se.ast_context IS NOT NULL
                          AND (
                              me.ast_context->>'callers' IS NOT NULL
                              OR me.ast_context->>'callees' IS NOT NULL
                          )
                          AND (%s IS NULL OR me.project_id = %s)
                    )
                    SELECT source_id::text, related_id::text
                    FROM related_entries
                    """,
                    (entry_ids, project_id, project_id),
                )
                for row in cur.fetchall():
                    src = row["source_id"]
                    rel = row["related_id"]
                    if src in related:
                        related[src].append(rel)

        except psycopg2.Error as e:
            logger.error("Call graph expansion failed: %s", e)

        return related


class ContextReranker:
    """
    Re-ranks retrieval candidates based on contextual similarity signals
    beyond pure vector distance.

    Signals used:
        - AST structure overlap (shared node types, depth similarity)
        - Library overlap (shared dependencies between before/after)
        - Risk tier match (same risk level as query context)
        - Language version proximity
    """

    @staticmethod
    def compute_context_score(
        candidate: dict,
        query_ast_context: Optional[dict] = None,
        query_libraries: Optional[list[str]] = None,
        query_risk_tier: Optional[str] = None,
    ) -> float:
        """
        Compute a context similarity score in [0, 1] for a candidate entry.

        Args:
            candidate: dict with candidate entry fields
            query_ast_context: AST context of the query snippet
            query_libraries: libraries involved in the query
            query_risk_tier: risk tier of the query transformation

        Returns:
            Aggregated context score
        """
        scores: list[float] = []

        if query_ast_context and candidate.get("ast_context"):
            ast_score = ContextReranker._ast_overlap(query_ast_context, candidate["ast_context"])
            scores.append(ast_score)

        if query_libraries and candidate.get("libraries_involved"):
            lib_score = ContextReranker._library_overlap(query_libraries, candidate["libraries_involved"])
            scores.append(lib_score)

        if query_risk_tier and candidate.get("risk_tier"):
            risk_score = 1.0 if query_risk_tier == candidate["risk_tier"] else 0.3
            scores.append(risk_score)

        return float(np.mean(scores)) if scores else 0.5

    @staticmethod
    def _ast_overlap(query_ast: dict, candidate_ast: dict) -> float:
        """Compute Jaccard similarity of AST node types."""
        query_nodes = set(query_ast.get("node_types", []))
        candidate_nodes = set(candidate_ast.get("node_types", []))

        if not query_nodes and not candidate_nodes:
            return 0.5

        intersection = query_nodes & candidate_nodes
        union = query_nodes | candidate_nodes

        return len(intersection) / len(union) if union else 0.0

    @staticmethod
    def _library_overlap(query_libs: list[str], candidate_libs: list | dict) -> float:
        """Compute Jaccard similarity of library sets."""
        query_set = set(query_libs)

        if isinstance(candidate_libs, dict):
            candidate_set = set(candidate_libs.get("libraries", []))
        elif isinstance(candidate_libs, list):
            candidate_set = set(candidate_libs)
        else:
            return 0.0

        if not query_set and not candidate_set:
            return 0.5

        intersection = query_set & candidate_set
        union = query_set | candidate_set

        return len(intersection) / len(union) if union else 0.0


class HybridRetriever:
    """
    Orchestrates the full hybrid retrieval pipeline:

        1. Vector search: retrieve top-N candidates by embedding similarity
        2. Graph expansion: find related entries via call graph
        3. Context re-ranking: re-score candidates using AST/library/risk signals
        4. Final scoring: weighted combination → return top-K
    """

    def __init__(self, config: Optional[RetrievalConfig] = None):
        self.config = config or RetrievalConfig()
        self._conn: Optional[psycopg2.extensions.connection] = None
        self._graph_expander: Optional[CallGraphExpander] = None
        self._reranker = ContextReranker()

    def _ensure_connection(self) -> psycopg2.extensions.connection:
        """Lazily connect to the database."""
        if self._conn is None or self._conn.closed:
            self._conn = psycopg2.connect(
                host=self.config.db_host,
                port=self.config.db_port,
                dbname=self.config.db_name,
                user=self.config.db_user,
                password=self.config.db_password,
            )
            register_vector(self._conn)
            self._graph_expander = CallGraphExpander(self._conn)
            logger.info("HybridRetriever connected to database")
        return self._conn

    def retrieve(
        self,
        query_embedding: np.ndarray,
        ast_context: Optional[dict] = None,
        libraries: Optional[list[str]] = None,
        risk_tier: Optional[str] = None,
        project_id: Optional[str] = None,
    ) -> list[RetrievalResult]:
        """
        Execute the full hybrid retrieval pipeline.

        Args:
            query_embedding: 768-dim embedding vector for the query
            ast_context: optional AST context of the query snippet
            libraries: optional list of libraries involved
            risk_tier: optional risk tier context
            project_id: optional project scope for graph expansion

        Returns:
            Ranked list of RetrievalResult, up to config.final_top_k
        """
        conn = self._ensure_connection()

        # Stage 1: Vector similarity search
        candidates = self._vector_search(conn, query_embedding)
        if not candidates:
            logger.info("No vector search candidates found")
            return []

        # Stage 2: Call graph expansion
        entry_ids = [c["id"] for c in candidates]
        graph_relations = {}
        if self._graph_expander:
            graph_relations = self._graph_expander.expand(entry_ids, project_id)

        # Stage 3: Context re-ranking
        results: list[RetrievalResult] = []
        for candidate in candidates:
            context_score = self._reranker.compute_context_score(
                candidate,
                query_ast_context=ast_context,
                query_libraries=libraries,
                query_risk_tier=risk_tier,
            )

            vector_score = 1.0 - candidate["distance"]
            graph_score = self._compute_graph_score(candidate["id"], graph_relations)

            final_score = (
                self.config.vector_weight * vector_score
                + self.config.context_weight * context_score
                + self.config.graph_weight * graph_score
            )

            related_ids = graph_relations.get(candidate["id"], [])

            results.append(RetrievalResult(
                entry_id=candidate["id"],
                rule_id=candidate["rule_id"],
                rule_name=candidate["rule_name"],
                before_snippet=candidate["before_snippet"],
                after_snippet=candidate["after_snippet"],
                risk_tier=candidate["risk_tier"],
                confidence_score=candidate["confidence_score"],
                developer_accepted=candidate["developer_accepted"],
                vector_distance=candidate["distance"],
                context_score=context_score,
                final_score=final_score,
                related_method_ids=related_ids,
            ))

        # Stage 4: Sort by final score and return top-K
        results.sort(key=lambda r: r.final_score, reverse=True)
        top_results = results[: self.config.final_top_k]

        logger.info(
            "Hybrid retrieval: %d candidates → %d results (top score: %.4f)",
            len(candidates), len(top_results),
            top_results[0].final_score if top_results else 0.0,
        )

        return top_results

    def _vector_search(
        self,
        conn: psycopg2.extensions.connection,
        query_embedding: np.ndarray,
    ) -> list[dict]:
        """Stage 1: Retrieve candidates by vector cosine similarity."""
        candidates = []
        try:
            with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
                cur.execute(
                    """
                    SELECT
                        id::text,
                        rule_id,
                        rule_name,
                        before_snippet,
                        after_snippet,
                        risk_tier,
                        confidence_score,
                        developer_accepted,
                        ast_context,
                        libraries_involved,
                        embedding <=> %s::vector AS distance
                    FROM migration_entries
                    WHERE embedding IS NOT NULL
                    ORDER BY embedding <=> %s::vector
                    LIMIT %s
                    """,
                    (query_embedding.tolist(), query_embedding.tolist(), self.config.vector_candidates),
                )
                candidates = [dict(row) for row in cur.fetchall()]
        except psycopg2.Error as e:
            logger.error("Vector search failed: %s", e)
            conn.rollback()

        return candidates

    @staticmethod
    def _compute_graph_score(entry_id: str, graph_relations: dict[str, list[str]]) -> float:
        """
        Score based on call graph connectivity.
        More related entries = higher score (normalized).
        """
        related = graph_relations.get(entry_id, [])
        if not related:
            return 0.0
        return min(len(related) / 10.0, 1.0)

    def close(self) -> None:
        """Close the database connection."""
        if self._conn and not self._conn.closed:
            self._conn.close()
            logger.info("HybridRetriever database connection closed")
