"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Badge } from "@/components/Badge";
import { Button } from "@/components/Button";
import { api, ApiError, type ReviewQueueItem } from "@/lib/api";
import {
  Search,
  Filter,
  ArrowUpDown,
  ArrowUp,
  ArrowDown,
  ChevronRight,
  ListTodo,
  AlertTriangle,
  RefreshCw,
  Loader2,
} from "lucide-react";

type SortKey = "ruleName" | "filePath" | "riskTier" | "riskScore" | "projectName";
type SortDir = "asc" | "desc";

const riskOrder: Record<string, number> = {
  COSMETIC: 0,
  LOW: 1,
  MEDIUM: 2,
  HIGH: 3,
  CRITICAL: 4,
};

function toUiRisk(tier: string): "low" | "medium" | "high" | "critical" {
  const t = tier.toLowerCase();
  if (t === "cosmetic" || t === "low") return "low";
  if (t === "medium") return "medium";
  if (t === "high") return "high";
  return "critical";
}

export default function QueuePage() {
  const [items, setItems] = useState<ReviewQueueItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [riskFilter, setRiskFilter] = useState("all");
  const [sortKey, setSortKey] = useState<SortKey>("riskScore");
  const [sortDir, setSortDir] = useState<SortDir>("desc");

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setItems(await api.reviews.queue());
    } catch (err) {
      setError(
        err instanceof ApiError
          ? `Backend ${err.status}. Start the API with --spring.profiles.active=demo`
          : err instanceof Error
            ? err.message
            : "Failed to load review queue"
      );
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    else {
      setSortKey(key);
      setSortDir("asc");
    }
  };

  const filtered = useMemo(() => {
    let rows = [...items];
    if (search) {
      const q = search.toLowerCase();
      rows = rows.filter(
        (i) =>
          i.filePath.toLowerCase().includes(q) ||
          i.ruleName.toLowerCase().includes(q) ||
          i.projectName.toLowerCase().includes(q)
      );
    }
    if (riskFilter !== "all") {
      rows = rows.filter((i) => i.riskTier.toLowerCase() === riskFilter);
    }
    rows.sort((a, b) => {
      let cmp = 0;
      if (sortKey === "riskTier") cmp = (riskOrder[a.riskTier] ?? 0) - (riskOrder[b.riskTier] ?? 0);
      else if (sortKey === "riskScore") cmp = a.riskScore - b.riskScore;
      else cmp = String(a[sortKey]).localeCompare(String(b[sortKey]));
      return sortDir === "desc" ? -cmp : cmp;
    });
    return rows;
  }, [items, search, riskFilter, sortKey, sortDir]);

  const SortIcon = ({ column }: { column: SortKey }) => {
    if (sortKey !== column) return <ArrowUpDown className="h-3 w-3 text-shadow-text-muted/50" />;
    return sortDir === "asc" ? (
      <ArrowUp className="h-3 w-3 text-shadow-accent" />
    ) : (
      <ArrowDown className="h-3 w-3 text-shadow-accent" />
    );
  };

  return (
    <div className="space-y-6 animate-in">
      <div className="flex items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-shadow-text">Review Queue</h1>
          <p className="text-sm text-shadow-text-muted mt-1">
            Live patches from the API — {filtered.length} awaiting review
          </p>
        </div>
        <Button variant="ghost" size="sm" onClick={() => void load()} disabled={loading}>
          {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <RefreshCw className="h-4 w-4" />}
          Refresh
        </Button>
      </div>

      {error && (
        <div className="glass-panel p-4 border border-amber-500/30 bg-amber-500/5 flex gap-3 items-start">
          <AlertTriangle className="h-5 w-5 text-amber-400 mt-0.5 shrink-0" />
          <div>
            <p className="text-sm font-medium text-shadow-text">Cannot reach live API</p>
            <p className="text-xs text-shadow-text-muted mt-1">{error}</p>
          </div>
        </div>
      )}

      <div className="glass-panel p-4">
        <div className="flex flex-wrap items-center gap-3">
          <div className="relative flex-1 min-w-[240px]">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-shadow-text-muted" />
            <input
              type="text"
              placeholder="Search by file, rule, or project..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="w-full pl-10 pr-4 py-2 rounded-lg bg-shadow-bg border border-shadow-border text-sm text-shadow-text placeholder:text-shadow-text-muted focus:outline-none focus:border-shadow-accent/50"
            />
          </div>
          <div className="flex items-center gap-2">
            <Filter className="h-3.5 w-3.5 text-shadow-text-muted" />
            <select
              value={riskFilter}
              onChange={(e) => setRiskFilter(e.target.value)}
              className="px-3 py-2 rounded-lg bg-shadow-bg border border-shadow-border text-xs text-shadow-text"
            >
              <option value="all">All Risk Tiers</option>
              <option value="low">Low</option>
              <option value="medium">Medium</option>
              <option value="high">High</option>
              <option value="critical">Critical</option>
            </select>
          </div>
        </div>
      </div>

      <div className="glass-panel overflow-hidden">
        {loading && items.length === 0 ? (
          <div className="flex items-center justify-center py-16 text-shadow-text-muted gap-2">
            <Loader2 className="h-5 w-5 animate-spin" />
            <span className="text-sm">Loading live queue…</span>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full">
              <thead>
                <tr className="border-b border-shadow-border bg-shadow-surface/30">
                  {(
                    [
                      ["ruleName", "Rule"],
                      ["filePath", "File"],
                      ["riskTier", "Risk"],
                      ["riskScore", "Residual %"],
                      ["projectName", "Project"],
                    ] as [SortKey, string][]
                  ).map(([key, label]) => (
                    <th
                      key={key}
                      onClick={() => toggleSort(key)}
                      className="px-4 py-3 text-left text-2xs font-semibold text-shadow-text-muted uppercase tracking-wider cursor-pointer"
                    >
                      <div className="flex items-center gap-1.5">
                        {label}
                        <SortIcon column={key} />
                      </div>
                    </th>
                  ))}
                  <th className="px-4 py-3 w-10" />
                </tr>
              </thead>
              <tbody className="divide-y divide-shadow-border/50">
                {filtered.map((item) => (
                  <tr key={item.patchId} className="group hover:bg-shadow-surface-hover/50">
                    <td className="px-4 py-3.5">
                      <span className="text-xs font-mono text-shadow-accent-bright">{item.ruleName}</span>
                    </td>
                    <td className="px-4 py-3.5">
                      <div>
                        <span className="text-xs text-shadow-text truncate block max-w-[320px]">
                          {item.filePath.split("/").pop()}
                        </span>
                        <span className="text-2xs text-shadow-text-muted">
                          L{item.startLine}–{item.endLine}
                        </span>
                      </div>
                    </td>
                    <td className="px-4 py-3.5">
                      <Badge variant="risk" riskTier={toUiRisk(item.riskTier)} size="sm">
                        {item.riskTier.toLowerCase()}
                      </Badge>
                    </td>
                    <td className="px-4 py-3.5">
                      <span
                        className="text-xs font-mono text-shadow-text-secondary"
                        title={
                          item.evidenceStrength != null
                            ? `Residual risk after evidence-weighted blend · evidence ${(item.evidenceStrength * 100).toFixed(0)}%`
                            : "Residual risk after evidence-weighted blend of rule prior, context, verify, and blast radius"
                        }
                      >
                        {(item.riskScore * 100).toFixed(0)}%
                        {item.evidenceStrength != null && item.evidenceStrength > 0 && (
                          <span className="text-shadow-text-muted ml-1">
                            · e{(item.evidenceStrength * 100).toFixed(0)}
                          </span>
                        )}
                      </span>
                    </td>
                    <td className="px-4 py-3.5 text-xs text-shadow-text-muted">{item.projectName}</td>
                    <td className="px-4 py-3.5">
                      <Link href={`/review?patch=${item.patchId}`}>
                        <Button
                          variant="ghost"
                          size="sm"
                          className="opacity-0 group-hover:opacity-100 transition-opacity"
                        >
                          Review
                          <ChevronRight className="h-3 w-3" />
                        </Button>
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {filtered.length === 0 && !error && (
              <div className="flex flex-col items-center justify-center py-16 text-shadow-text-muted">
                <ListTodo className="h-10 w-10 mb-3 opacity-40" />
                <p className="text-sm font-medium">No patches awaiting review</p>
                <p className="text-xs mt-1">
                  Boot the API with the demo profile to seed examples/legacy-sample.
                </p>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
