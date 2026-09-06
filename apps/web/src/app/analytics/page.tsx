"use client";

import { useEffect, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { AlertTriangle, BarChart3 } from "lucide-react";

/**
 * Analytics stays honest: the demo profile does not claim corpus-scale metrics.
 * We surface live queue/project counts only; advanced charts require Postgres + corpus.
 */
export default function AnalyticsPage() {
  const [pending, setPending] = useState<number | null>(null);
  const [projects, setProjects] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      try {
        const [p, q] = await Promise.all([api.projects.list(), api.reviews.queue()]);
        setProjects(p.length);
        setPending(q.length);
      } catch (err) {
        setError(
          err instanceof ApiError
            ? `Backend ${err.status}`
            : err instanceof Error
              ? err.message
              : "Failed to load"
        );
      }
    })();
  }, []);

  return (
    <div className="space-y-6 animate-in">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-shadow-text">Analytics</h1>
        <p className="text-sm text-shadow-text-muted mt-1">
          Honest demo mode — no invented acceptance rates or corpus heatmaps.
        </p>
      </div>

      {error && (
        <div className="glass-panel p-4 border border-amber-500/30 flex gap-3">
          <AlertTriangle className="h-5 w-5 text-amber-400" />
          <p className="text-sm text-shadow-text">{error}</p>
        </div>
      )}

      <div className="glass-panel p-6 space-y-4">
        <div className="flex items-center gap-2">
          <BarChart3 className="h-4 w-4 text-shadow-accent" />
          <h2 className="text-sm font-semibold text-shadow-text">Live snapshot</h2>
        </div>
        <dl className="grid grid-cols-2 gap-4 text-sm">
          <div>
            <dt className="text-shadow-text-muted text-xs">Projects</dt>
            <dd className="text-2xl font-mono text-shadow-text">{projects ?? "—"}</dd>
          </div>
          <div>
            <dt className="text-shadow-text-muted text-xs">Pending review</dt>
            <dd className="text-2xl font-mono text-shadow-text">{pending ?? "—"}</dd>
          </div>
        </dl>
        <p className="text-xs text-shadow-text-muted leading-relaxed">
          Historical acceptance curves, confidence calibration, and migration-corpus
          analytics require the Postgres-backed deployment. The demo profile intentionally
          exposes only live in-memory review state so a company pitch never shows fake KPIs.
        </p>
      </div>
    </div>
  );
}
