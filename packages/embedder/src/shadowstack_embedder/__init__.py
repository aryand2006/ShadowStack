"""
ShadowStack Embedder — Custom transformer encoder for migration pair embeddings.

Provides a CodeBERT-based encoder with a contrastive learning head that produces
768-dimensional embeddings for (legacy_code, modern_code) migration pairs.
These embeddings power similarity search and acceptance prediction in the
ShadowStack migration intelligence corpus.
"""

__version__ = "1.0.0"
__author__ = "ShadowStack Engineering"
