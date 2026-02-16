"""
Training pipeline for the CodeMigrationEncoder.

Implements contrastive learning on (legacy, modern) code pairs with:
    - InfoNCE loss with in-batch negatives
    - Hard negative mining from nearest-neighbor lookup
    - Rejected transformations used as explicit negative examples
    - Evaluation via Recall@K and clustering coherence
    - Checkpoint saving and optional WandB/MLflow logging
"""

from __future__ import annotations

import logging
import os
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Optional

import numpy as np
import torch
import torch.nn as nn
from sklearn.metrics import silhouette_score
from torch.optim import AdamW
from torch.optim.lr_scheduler import CosineAnnealingLR
from torch.utils.data import DataLoader, Dataset

from .model import CodeMigrationEncoder, mine_hard_negatives

logger = logging.getLogger(__name__)


@dataclass
class TrainingConfig:
    """Configuration for the contrastive training pipeline."""

    backbone_name: str = "microsoft/codebert-base"
    embedding_dim: int = 768
    projection_hidden_dim: int = 1024
    dropout: float = 0.1
    temperature: float = 0.07

    batch_size: int = 32
    learning_rate: float = 2e-5
    weight_decay: float = 0.01
    num_epochs: int = 10
    warmup_steps: int = 500
    max_seq_length: int = 512
    gradient_accumulation_steps: int = 1
    max_grad_norm: float = 1.0

    hard_negative_mining: bool = True
    hard_negatives_per_sample: int = 5
    mine_every_n_steps: int = 100

    checkpoint_dir: str = "./checkpoints"
    save_every_n_epochs: int = 1
    eval_every_n_steps: int = 500

    use_wandb: bool = False
    use_mlflow: bool = False
    experiment_name: str = "shadowstack-embedder"

    device: str = "auto"

    def resolve_device(self) -> torch.device:
        if self.device == "auto":
            if torch.cuda.is_available():
                return torch.device("cuda")
            elif hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
                return torch.device("mps")
            return torch.device("cpu")
        return torch.device(self.device)


class MigrationPairDataset(Dataset):
    """
    PyTorch Dataset for migration code pairs.

    Each sample consists of:
        - before_code: the legacy code snippet
        - after_code: the modernized code snippet
        - label: integer label grouping related transformations
        - is_accepted: whether the developer accepted this transformation
    """

    def __init__(
        self,
        before_codes: list[str],
        after_codes: list[str],
        labels: list[int],
        is_accepted: list[bool],
    ):
        assert len(before_codes) == len(after_codes) == len(labels) == len(is_accepted), (
            "All input lists must have the same length"
        )
        self.before_codes = before_codes
        self.after_codes = after_codes
        self.labels = labels
        self.is_accepted = is_accepted

    def __len__(self) -> int:
        return len(self.before_codes)

    def __getitem__(self, idx: int) -> dict[str, Any]:
        return {
            "before_code": self.before_codes[idx],
            "after_code": self.after_codes[idx],
            "label": self.labels[idx],
            "is_accepted": self.is_accepted[idx],
        }

    @classmethod
    def from_records(cls, records: list[dict[str, Any]]) -> MigrationPairDataset:
        """
        Build dataset from a list of dicts, each containing:
            before_snippet, after_snippet, rule_id (used as label), developer_accepted
        """
        rule_id_to_label: dict[str, int] = {}
        before_codes, after_codes, labels, is_accepted = [], [], [], []

        for rec in records:
            rid = rec["rule_id"]
            if rid not in rule_id_to_label:
                rule_id_to_label[rid] = len(rule_id_to_label)

            before_codes.append(rec["before_snippet"])
            after_codes.append(rec["after_snippet"])
            labels.append(rule_id_to_label[rid])
            is_accepted.append(bool(rec.get("developer_accepted", True)))

        return cls(before_codes, after_codes, labels, is_accepted)


def collate_fn(batch: list[dict], tokenizer, max_length: int = 512) -> dict[str, Any]:
    """Collate function that tokenizes code pairs for the encoder."""
    before_codes = [item["before_code"] for item in batch]
    after_codes = [item["after_code"] for item in batch]
    labels = torch.tensor([item["label"] for item in batch], dtype=torch.long)
    is_accepted = torch.tensor([item["is_accepted"] for item in batch], dtype=torch.bool)

    before_tokens = tokenizer(
        before_codes, padding=True, truncation=True, max_length=max_length, return_tensors="pt"
    )
    after_tokens = tokenizer(
        after_codes, padding=True, truncation=True, max_length=max_length, return_tensors="pt"
    )

    return {
        "before_ids": before_tokens["input_ids"],
        "before_mask": before_tokens["attention_mask"],
        "after_ids": after_tokens["input_ids"],
        "after_mask": after_tokens["attention_mask"],
        "labels": labels,
        "is_accepted": is_accepted,
    }


class ContrastiveTrainer:
    """
    Trainer for the CodeMigrationEncoder using contrastive learning.

    Training strategy:
        1. Positive pairs: (legacy_code, modern_code) from accepted transformations
        2. Negative pairs: in-batch negatives (different transformation rules)
        3. Hard negatives: mined from nearest neighbors with different labels
        4. Rejected transformations: explicitly treated as negatives
    """

    def __init__(self, config: TrainingConfig):
        self.config = config
        self.device = config.resolve_device()
        logger.info("Using device: %s", self.device)

        self.model = CodeMigrationEncoder(
            backbone_name=config.backbone_name,
            embedding_dim=config.embedding_dim,
            projection_hidden_dim=config.projection_hidden_dim,
            dropout=config.dropout,
            temperature=config.temperature,
        ).to(self.device)

        self.optimizer = AdamW(
            self.model.parameters(),
            lr=config.learning_rate,
            weight_decay=config.weight_decay,
        )
        self.scheduler: Optional[CosineAnnealingLR] = None

        self.global_step = 0
        self.best_recall_at_k = 0.0
        self._experiment_logger: Optional[Any] = None

        Path(config.checkpoint_dir).mkdir(parents=True, exist_ok=True)

    def _init_logging(self) -> None:
        """Initialize optional experiment tracking."""
        if self.config.use_wandb:
            try:
                import wandb
                wandb.init(project=self.config.experiment_name, config=vars(self.config))
                self._experiment_logger = "wandb"
                logger.info("WandB logging initialized")
            except ImportError:
                logger.warning("wandb not installed, skipping WandB logging")

        if self.config.use_mlflow:
            try:
                import mlflow
                mlflow.set_experiment(self.config.experiment_name)
                mlflow.start_run()
                mlflow.log_params(vars(self.config))
                self._experiment_logger = "mlflow"
                logger.info("MLflow logging initialized")
            except ImportError:
                logger.warning("mlflow not installed, skipping MLflow logging")

    def _log_metrics(self, metrics: dict[str, float], step: int) -> None:
        """Log metrics to configured experiment tracker."""
        if self._experiment_logger == "wandb":
            import wandb
            wandb.log(metrics, step=step)
        elif self._experiment_logger == "mlflow":
            import mlflow
            mlflow.log_metrics(metrics, step=step)

    def train(
        self,
        train_dataset: MigrationPairDataset,
        eval_dataset: Optional[MigrationPairDataset] = None,
    ) -> dict[str, list[float]]:
        """
        Run the full training loop.

        Args:
            train_dataset: training data
            eval_dataset: optional evaluation data for Recall@K and clustering

        Returns:
            dict of training history with loss curves and eval metrics
        """
        self._init_logging()

        train_loader = DataLoader(
            train_dataset,
            batch_size=self.config.batch_size,
            shuffle=True,
            collate_fn=lambda batch: collate_fn(batch, self.model.tokenizer, self.config.max_seq_length),
            num_workers=0,
            drop_last=True,
        )

        total_steps = len(train_loader) * self.config.num_epochs // self.config.gradient_accumulation_steps
        self.scheduler = CosineAnnealingLR(self.optimizer, T_max=total_steps, eta_min=1e-7)

        history: dict[str, list[float]] = {
            "train_loss": [],
            "recall_at_1": [],
            "recall_at_5": [],
            "recall_at_10": [],
            "clustering_coherence": [],
        }

        logger.info(
            "Starting training: %d epochs, %d steps/epoch, %d total steps",
            self.config.num_epochs, len(train_loader), total_steps,
        )

        cached_embeddings: Optional[torch.Tensor] = None
        cached_labels: Optional[torch.Tensor] = None

        for epoch in range(self.config.num_epochs):
            epoch_loss = self._train_epoch(
                train_loader, epoch, cached_embeddings, cached_labels
            )
            history["train_loss"].append(epoch_loss)

            if eval_dataset is not None and (epoch + 1) % self.config.save_every_n_epochs == 0:
                eval_metrics = self.evaluate(eval_dataset)
                for key, val in eval_metrics.items():
                    if key in history:
                        history[key].append(val)

                self._log_metrics(
                    {"epoch": epoch, "train_loss": epoch_loss, **eval_metrics},
                    step=self.global_step,
                )

                if eval_metrics.get("recall_at_5", 0) > self.best_recall_at_k:
                    self.best_recall_at_k = eval_metrics["recall_at_5"]
                    self.save_checkpoint(os.path.join(self.config.checkpoint_dir, "best_model.pt"))

            if (epoch + 1) % self.config.save_every_n_epochs == 0:
                self.save_checkpoint(
                    os.path.join(self.config.checkpoint_dir, f"checkpoint_epoch_{epoch + 1}.pt")
                )

            if self.config.hard_negative_mining:
                cached_embeddings, cached_labels = self._compute_all_embeddings(train_loader)

        self.save_checkpoint(os.path.join(self.config.checkpoint_dir, "final_model.pt"))
        logger.info("Training complete. Best Recall@5: %.4f", self.best_recall_at_k)
        return history

    def _train_epoch(
        self,
        loader: DataLoader,
        epoch: int,
        cached_embeddings: Optional[torch.Tensor],
        cached_labels: Optional[torch.Tensor],
    ) -> float:
        """Run a single training epoch."""
        self.model.train()
        total_loss = 0.0
        num_batches = 0

        for batch_idx, batch in enumerate(loader):
            before_ids = batch["before_ids"].to(self.device)
            before_mask = batch["before_mask"].to(self.device)
            after_ids = batch["after_ids"].to(self.device)
            after_mask = batch["after_mask"].to(self.device)
            labels = batch["labels"].to(self.device)
            is_accepted = batch["is_accepted"].to(self.device)

            outputs = self.model(before_ids, before_mask, after_ids, after_mask, labels)
            loss = outputs["loss"]

            # Down-weight rejected transformations in the contrastive objective
            if not is_accepted.all():
                rejection_penalty = (~is_accepted).float().mean() * 0.5
                loss = loss + rejection_penalty

            # Hard negative augmented loss
            if (
                self.config.hard_negative_mining
                and cached_embeddings is not None
                and self.global_step % self.config.mine_every_n_steps == 0
            ):
                with torch.no_grad():
                    batch_emb = outputs["pair_embeddings"]
                    hard_neg_idx = mine_hard_negatives(
                        cached_embeddings.to(self.device),
                        cached_labels.to(self.device),
                        self.config.hard_negatives_per_sample,
                    )

                    current_indices = torch.arange(
                        batch_idx * self.config.batch_size,
                        min((batch_idx + 1) * self.config.batch_size, len(cached_embeddings)),
                    )
                    if current_indices.max() < hard_neg_idx.size(0):
                        relevant_negs = hard_neg_idx[current_indices]
                        neg_embs = cached_embeddings[relevant_negs.flatten()].to(self.device)
                        neg_embs = neg_embs.view(batch_emb.size(0), -1, batch_emb.size(1))

                        hard_neg_sim = torch.bmm(
                            batch_emb.unsqueeze(1), neg_embs.transpose(1, 2)
                        ).squeeze(1) / self.model.temperature

                        hard_neg_loss = torch.logsumexp(hard_neg_sim, dim=1).mean()
                        loss = loss + 0.1 * hard_neg_loss

            loss = loss / self.config.gradient_accumulation_steps
            loss.backward()

            if (batch_idx + 1) % self.config.gradient_accumulation_steps == 0:
                nn.utils.clip_grad_norm_(self.model.parameters(), self.config.max_grad_norm)
                self.optimizer.step()
                self.scheduler.step()
                self.optimizer.zero_grad()
                self.global_step += 1

            total_loss += loss.item() * self.config.gradient_accumulation_steps
            num_batches += 1

            if batch_idx % 50 == 0:
                logger.info(
                    "Epoch %d | Step %d/%d | Loss: %.4f | LR: %.2e",
                    epoch, batch_idx, len(loader), loss.item(),
                    self.scheduler.get_last_lr()[0] if self.scheduler else self.config.learning_rate,
                )

        avg_loss = total_loss / max(num_batches, 1)
        logger.info("Epoch %d complete. Avg loss: %.4f", epoch, avg_loss)
        return avg_loss

    @torch.no_grad()
    def _compute_all_embeddings(self, loader: DataLoader) -> tuple[torch.Tensor, torch.Tensor]:
        """Compute embeddings for the entire dataset (used for hard negative mining)."""
        self.model.eval()
        all_embeddings = []
        all_labels = []

        for batch in loader:
            before_ids = batch["before_ids"].to(self.device)
            before_mask = batch["before_mask"].to(self.device)
            after_ids = batch["after_ids"].to(self.device)
            after_mask = batch["after_mask"].to(self.device)

            outputs = self.model(before_ids, before_mask, after_ids, after_mask)
            all_embeddings.append(outputs["pair_embeddings"].cpu())
            all_labels.append(batch["labels"])

        return torch.cat(all_embeddings), torch.cat(all_labels)

    @torch.no_grad()
    def evaluate(self, dataset: MigrationPairDataset) -> dict[str, float]:
        """
        Evaluate the model on a held-out dataset.

        Metrics:
            - Recall@1, Recall@5, Recall@10: fraction of queries where the correct
              match appears in the top-K nearest neighbors
            - Clustering coherence: silhouette score of embeddings grouped by rule label
        """
        self.model.eval()
        loader = DataLoader(
            dataset,
            batch_size=self.config.batch_size,
            shuffle=False,
            collate_fn=lambda batch: collate_fn(batch, self.model.tokenizer, self.config.max_seq_length),
            num_workers=0,
        )

        all_before_emb, all_after_emb, all_labels = [], [], []

        for batch in loader:
            before_ids = batch["before_ids"].to(self.device)
            before_mask = batch["before_mask"].to(self.device)
            after_ids = batch["after_ids"].to(self.device)
            after_mask = batch["after_mask"].to(self.device)

            outputs = self.model(before_ids, before_mask, after_ids, after_mask)
            all_before_emb.append(outputs["before_embeddings"].cpu())
            all_after_emb.append(outputs["after_embeddings"].cpu())
            all_labels.append(batch["labels"])

        before_emb = torch.cat(all_before_emb)
        after_emb = torch.cat(all_after_emb)
        labels = torch.cat(all_labels)

        # Recall@K: for each before embedding, find nearest after embeddings
        sim_matrix = torch.mm(before_emb, after_emb.t())
        n = sim_matrix.size(0)

        metrics: dict[str, float] = {}
        for k in [1, 5, 10]:
            if k > n:
                metrics[f"recall_at_{k}"] = 0.0
                continue
            _, topk_indices = sim_matrix.topk(k, dim=1)
            correct = torch.arange(n).unsqueeze(1).expand_as(topk_indices)
            hits = (topk_indices == correct).any(dim=1).float()
            metrics[f"recall_at_{k}"] = hits.mean().item()

        # Clustering coherence via silhouette score
        pair_emb = torch.cat([before_emb, after_emb], dim=-1)
        unique_labels = labels.unique()
        if len(unique_labels) >= 2 and len(labels) > len(unique_labels):
            try:
                score = silhouette_score(
                    pair_emb.numpy(), labels.numpy(), metric="cosine", sample_size=min(5000, len(labels))
                )
                metrics["clustering_coherence"] = float(score)
            except Exception as e:
                logger.warning("Silhouette score computation failed: %s", e)
                metrics["clustering_coherence"] = 0.0
        else:
            metrics["clustering_coherence"] = 0.0

        logger.info("Evaluation metrics: %s", metrics)
        return metrics

    def save_checkpoint(self, path: str) -> None:
        """Save model checkpoint with optimizer and scheduler state."""
        checkpoint = {
            "model_state_dict": self.model.state_dict(),
            "optimizer_state_dict": self.optimizer.state_dict(),
            "scheduler_state_dict": self.scheduler.state_dict() if self.scheduler else None,
            "global_step": self.global_step,
            "best_recall_at_k": self.best_recall_at_k,
            "config": vars(self.config),
        }
        torch.save(checkpoint, path)
        logger.info("Checkpoint saved: %s", path)

    def load_checkpoint(self, path: str) -> None:
        """Load model checkpoint."""
        checkpoint = torch.load(path, map_location=self.device, weights_only=False)
        self.model.load_state_dict(checkpoint["model_state_dict"])
        self.optimizer.load_state_dict(checkpoint["optimizer_state_dict"])
        if checkpoint["scheduler_state_dict"] and self.scheduler:
            self.scheduler.load_state_dict(checkpoint["scheduler_state_dict"])
        self.global_step = checkpoint["global_step"]
        self.best_recall_at_k = checkpoint.get("best_recall_at_k", 0.0)
        logger.info("Checkpoint loaded from: %s (step %d)", path, self.global_step)
