"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { StatCard } from "@/components/StatCard";
import { Badge } from "@/components/Badge";
import { api, ApiError, type Project, type ReviewQueueItem } from "@/lib/api";
import {
  Shield,
  GitPullRequest,
  CheckCircle2,
  Clock,
  FileCode2,
  AlertTriangle,
  Loader2,
  ArrowRight,
} from "lucide-react";

export default function DashboardPage() {
  const [projects, setProjects] = useState<Project[]>([]);
  const [queue, setQueue] = useState<ReviewQueueItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      setLoading(true);
      setError(null);
      try {
        const [p, q] = await Promise.all([api.projects.list(), api.reviews.queue()]);
        if (!cancelled) {
          setProjects(p);
          setQueue(q);
        }
      } catch (err) {
        if (!cancelled) {
          setError(
            err instanceof ApiError
              ? `Backend ${err.status}. Start API with --spring.profiles.active=demo`
              : err instanceof Error
                ? err.message
                : "Failed to load dashboard"
          );
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const verified = queue.filter((q) => q.verificationPassed).length;

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24 text-shadow-text-muted gap-2">
        <Loader2 className="h-5 w-5 animate-spin" />
        <span className="text-sm">Loading live dashboard…</span>
      </div>
    );
  }

  return (
    <div className="space-y-6 animate-in">
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-shadow-text">Dashboard</h1>
        <p className="text-sm text-shadow-text-muted mt-1">
          Live counts from the running API — no fabricated analytics.
        </p>
      </div>

      {error && (
        <div className="glass-panel p-4 border border-amber-500/30 bg-amber-500/5 flex gap-3">
          <AlertTriangle className="h-5 w-5 text-amber-400 shrink-0" />
          <div>
            <p className="text-sm font-medium text-shadow-text">API unreachable</p>
            <p className="text-xs text-shadow-text-muted mt-1">{error}</p>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-2 xl:grid-cols-4 gap-4">
        <StatCard
          label="Projects"
          value={projects.length}
          icon={<FileCode2 className="h-4 w-4" />}
          accentColor="#3b82f6"
        />
        <StatCard
          label="Pending Review"
          value={queue.length}
          icon={<Clock className="h-4 w-4" />}
          accentColor="#f59e0b"
        />
        <StatCard
          label="Verified in Queue"
          value={verified}
          icon={<Shield className="h-4 w-4" />}
          accentColor="#10b981"
        />
        <StatCard
          label="Open Patches"
          value={queue.length}
          icon={<GitPullRequest className="h-4 w-4" />}
          accentColor="#06b6d4"
        />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="glass-panel p-5 space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-shadow-text">Projects</h2>
          </div>
          {projects.length === 0 && (
            <p className="text-xs text-shadow-text-muted">No projects yet.</p>
          )}
          {projects.map((p) => (
            <div
              key={p.id}
              className="flex items-center justify-between py-2 border-b border-shadow-border/40 last:border-0"
            >
              <div>
                <p className="text-sm text-shadow-text">{p.name}</p>
                <p className="text-2xs text-shadow-text-muted font-mono">{p.sourceLanguage}</p>
              </div>
              <Badge color="blue">{p.status}</Badge>
            </div>
          ))}
        </div>

        <div className="glass-panel p-5 space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-shadow-text">Review Queue</h2>
            <Link
              href="/queue"
              className="text-xs text-shadow-accent hover:text-shadow-accent-bright inline-flex items-center gap-1"
            >
              Open queue <ArrowRight className="h-3 w-3" />
            </Link>
          </div>
          {queue.length === 0 && (
            <p className="text-xs text-shadow-text-muted">Queue is empty.</p>
          )}
          {queue.slice(0, 8).map((item) => (
            <Link
              key={item.patchId}
              href={`/review?patch=${item.patchId}`}
              className="flex items-center justify-between py-2 border-b border-shadow-border/40 last:border-0 hover:bg-shadow-surface/40 rounded px-1"
            >
              <div className="min-w-0">
                <p className="text-xs font-mono text-shadow-accent-bright truncate">
                  {item.ruleName}
                </p>
                <p className="text-2xs text-shadow-text-muted truncate">
                  {item.filePath.split("/").pop()}
                </p>
              </div>
              {item.verificationPassed ? (
                <CheckCircle2 className="h-4 w-4 text-emerald-400 shrink-0" />
              ) : (
                <AlertTriangle className="h-4 w-4 text-amber-400 shrink-0" />
              )}
            </Link>
          ))}
        </div>
      </div>
    </div>
  );
}
