"""
Custom transformer encoder for code migration pair embeddings.

Architecture:
    - Pre-trained CodeBERT (microsoft/codebert-base) as the backbone encoder
    - Custom projection head that maps pooled representations to 768-dim space
    - Dual-encoder design: encodes before/after code independently, then fuses
    - InfoNCE contrastive loss for training on (legacy, modern) pairs
    - Hard negative mining utility for effective contrastive learning
"""

from __future__ import annotations

import logging
from typing import Optional

import torch
import torch.nn as nn
import torch.nn.functional as F
from transformers import AutoModel, AutoTokenizer

logger = logging.getLogger(__name__)

DEFAULT_BACKBONE = "microsoft/codebert-base"
EMBEDDING_DIM = 768
MAX_SEQ_LENGTH = 512


class ProjectionHead(nn.Module):
    """
    MLP projection head that maps backbone representations into the contrastive
    embedding space. Uses GELU activation and layer normalization for stable training.
    """

    def __init__(self, input_dim: int, hidden_dim: int, output_dim: int, dropout: float = 0.1):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(input_dim, hidden_dim),
            nn.GELU(),
            nn.LayerNorm(hidden_dim),
            nn.Dropout(dropout),
            nn.Linear(hidden_dim, output_dim),
            nn.LayerNorm(output_dim),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.net(x)


class CodeMigrationEncoder(nn.Module):
    """
    Dual-encoder model for code migration pairs.

    Takes (before_code, after_code) pairs and produces a single 768-dim embedding
    that captures the semantic relationship of the migration transformation.

    The encoder operates in three modes:
        1. encode_single — embed a single code snippet
        2. encode_pair   — embed a (before, after) migration pair
        3. forward       — full forward pass with loss computation for training

    Architecture:
        CodeBERT backbone → mean pooling → projection head → L2-normalized embedding
        For pairs: concat(before_emb, after_emb) → fusion layer → final embedding
    """

    def __init__(
        self,
        backbone_name: str = DEFAULT_BACKBONE,
        embedding_dim: int = EMBEDDING_DIM,
        projection_hidden_dim: int = 1024,
        dropout: float = 0.1,
        temperature: float = 0.07,
    ):
        super().__init__()
        self.embedding_dim = embedding_dim
        self.temperature = temperature

        logger.info("Loading backbone model: %s", backbone_name)
        self.backbone = AutoModel.from_pretrained(backbone_name)
        self.tokenizer = AutoTokenizer.from_pretrained(backbone_name)
        backbone_dim = self.backbone.config.hidden_size

        self.single_projection = ProjectionHead(
            input_dim=backbone_dim,
            hidden_dim=projection_hidden_dim,
            output_dim=embedding_dim,
            dropout=dropout,
        )

        self.pair_fusion = nn.Sequential(
            nn.Linear(embedding_dim * 2, projection_hidden_dim),
            nn.GELU(),
            nn.LayerNorm(projection_hidden_dim),
            nn.Dropout(dropout),
            nn.Linear(projection_hidden_dim, embedding_dim),
            nn.LayerNorm(embedding_dim),
        )

        logger.info(
            "CodeMigrationEncoder initialized: backbone=%s, embedding_dim=%d, temperature=%.4f",
            backbone_name, embedding_dim, temperature,
        )

    def _mean_pool(self, last_hidden_state: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        """Mean pool over non-padding tokens."""
        mask_expanded = attention_mask.unsqueeze(-1).expand(last_hidden_state.size()).float()
        sum_embeddings = torch.sum(last_hidden_state * mask_expanded, dim=1)
        sum_mask = mask_expanded.sum(dim=1).clamp(min=1e-9)
        return sum_embeddings / sum_mask

    def _encode_tokens(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        """Run backbone + mean pooling + projection."""
        outputs = self.backbone(input_ids=input_ids, attention_mask=attention_mask)
        pooled = self._mean_pool(outputs.last_hidden_state, attention_mask)
        projected = self.single_projection(pooled)
        return F.normalize(projected, p=2, dim=-1)

    def tokenize(self, texts: list[str], max_length: int = MAX_SEQ_LENGTH) -> dict[str, torch.Tensor]:
        """Tokenize a batch of code strings."""
        return self.tokenizer(
            texts,
            padding=True,
            truncation=True,
            max_length=max_length,
            return_tensors="pt",
        )

    def encode_single(self, code_texts: list[str], max_length: int = MAX_SEQ_LENGTH) -> torch.Tensor:
        """
        Encode a batch of single code snippets into 768-dim embeddings.

        Args:
            code_texts: list of code strings
            max_length: max token length

        Returns:
            Tensor of shape (batch_size, embedding_dim), L2-normalized
        """
        tokens = self.tokenize(code_texts, max_length)
        device = next(self.parameters()).device
        tokens = {k: v.to(device) for k, v in tokens.items()}
        return self._encode_tokens(tokens["input_ids"], tokens["attention_mask"])

    def encode_pair(
        self,
        before_texts: list[str],
        after_texts: list[str],
        max_length: int = MAX_SEQ_LENGTH,
    ) -> torch.Tensor:
        """
        Encode (before_code, after_code) migration pairs into fused 768-dim embeddings.

        Args:
            before_texts: list of legacy code strings
            after_texts: list of modern code strings
            max_length: max token length per snippet

        Returns:
            Tensor of shape (batch_size, embedding_dim), L2-normalized
        """
        before_emb = self.encode_single(before_texts, max_length)
        after_emb = self.encode_single(after_texts, max_length)

        fused = self.pair_fusion(torch.cat([before_emb, after_emb], dim=-1))
        return F.normalize(fused, p=2, dim=-1)

    def forward(
        self,
        before_ids: torch.Tensor,
        before_mask: torch.Tensor,
        after_ids: torch.Tensor,
        after_mask: torch.Tensor,
        labels: Optional[torch.Tensor] = None,
    ) -> dict[str, torch.Tensor]:
        """
        Full forward pass for training with contrastive loss.

        Args:
            before_ids: tokenized legacy code input IDs (batch, seq_len)
            before_mask: attention mask for legacy code
            after_ids: tokenized modern code input IDs (batch, seq_len)
            after_mask: attention mask for modern code
            labels: optional integer labels for supervised contrastive loss.
                    If None, uses in-batch InfoNCE (each pair is its own class).

        Returns:
            dict with keys:
                - 'loss': scalar contrastive loss
                - 'before_embeddings': (batch, embedding_dim)
                - 'after_embeddings': (batch, embedding_dim)
                - 'pair_embeddings': (batch, embedding_dim) fused
        """
        before_emb = self._encode_tokens(before_ids, before_mask)
        after_emb = self._encode_tokens(after_ids, after_mask)

        fused = self.pair_fusion(torch.cat([before_emb, after_emb], dim=-1))
        pair_emb = F.normalize(fused, p=2, dim=-1)

        loss = self.info_nce_loss(before_emb, after_emb, labels)

        return {
            "loss": loss,
            "before_embeddings": before_emb,
            "after_embeddings": after_emb,
            "pair_embeddings": pair_emb,
        }

    def info_nce_loss(
        self,
        anchors: torch.Tensor,
        positives: torch.Tensor,
        labels: Optional[torch.Tensor] = None,
    ) -> torch.Tensor:
        """
        InfoNCE contrastive loss.

        For each anchor i, the positive is positives[i]. All other positives in the
        batch serve as negatives. When labels are provided, entries sharing the same
        label are treated as positives (supervised contrastive).

        Args:
            anchors: (batch, dim) L2-normalized
            positives: (batch, dim) L2-normalized
            labels: optional (batch,) integer labels

        Returns:
            Scalar loss value
        """
        batch_size = anchors.size(0)
        sim_matrix = torch.mm(anchors, positives.t()) / self.temperature

        if labels is not None:
            mask = labels.unsqueeze(0) == labels.unsqueeze(1)
            mask.fill_diagonal_(False)
            pos_mask = mask.float()

            exp_sim = torch.exp(sim_matrix)
            log_prob = sim_matrix - torch.log(exp_sim.sum(dim=1, keepdim=True))

            pos_count = pos_mask.sum(dim=1).clamp(min=1)
            loss = -(log_prob * pos_mask).sum(dim=1) / pos_count

            target_sim = torch.diagonal(sim_matrix)
            target_loss = -target_sim + torch.log(exp_sim.sum(dim=1))

            loss = (loss + target_loss) / 2
            return loss.mean()
        else:
            targets = torch.arange(batch_size, device=anchors.device)
            return F.cross_entropy(sim_matrix, targets)


def mine_hard_negatives(
    embeddings: torch.Tensor,
    labels: torch.Tensor,
    num_negatives: int = 5,
) -> torch.Tensor:
    """
    Mine hard negatives: for each sample, find the closest embeddings
    that have a different label.

    Args:
        embeddings: (N, dim) L2-normalized embeddings
        labels: (N,) integer labels
        num_negatives: number of hard negatives to mine per sample

    Returns:
        Tensor of shape (N, num_negatives) with indices of hard negatives
    """
    sim_matrix = torch.mm(embeddings, embeddings.t())

    same_label_mask = labels.unsqueeze(0) == labels.unsqueeze(1)
    sim_matrix[same_label_mask] = -float("inf")

    _, hard_neg_indices = sim_matrix.topk(num_negatives, dim=1, largest=True)
    return hard_neg_indices
