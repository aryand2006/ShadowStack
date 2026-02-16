"use client";

import { useState, useMemo } from "react";
import Link from "next/link";
import { Badge } from "@/components/Badge";
import { Button } from "@/components/Button";
import { cn } from "@/lib/utils";
import {
  Search,
  Filter,
  ArrowUpDown,
  ArrowUp,
  ArrowDown,
  ChevronRight,
  ListTodo,
  Clock,
  FileCode2,
} from "lucide-react";

/* ─── Mock Queue Data ────────────────────────────────────────────────────────
 *  In production, this comes from GET /api/v1/queue with optional filters.
 *  Each item represents a patch awaiting human review.
 * ──────────────────────────────────────────────────────────────────────────── */

interface QueueRow {
  id: string;
  patchId: string;
  projectName: string;
  filePath: string;
  rule: string;
  riskTier: "low" | "medium" | "high" | "critical";
  confidence: number;
  status: "pending" | "reviewing" | "accepted" | "rejected" | "deferred";
  priority: number;
  createdAt: string;
  estimatedReviewTime: string;
}

const mockQueue: QueueRow[] = [
  {
    id: "q-001",
    patchId: "p-1001",
    projectName: "payment-service",
    filePath: "src/main/java/com/acme/payment/PaymentProcessor.java",
    rule: "java-stream-migration",
    riskTier: "high",
    confidence: 0.87,
    status: "pending",
    priority: 1,
    createdAt: "2026-02-16T08:12:00Z",
    estimatedReviewTime: "5 min",
  },
  {
    id: "q-002",
    patchId: "p-1002",
    projectName: "user-management",
    filePath: "src/main/java/com/acme/user/UserDAO.java",
    rule: "optional-null-safety",
    riskTier: "medium",
    confidence: 0.93,
    status: "pending",
    priority: 2,
    createdAt: "2026-02-16T08:05:00Z",
    estimatedReviewTime: "3 min",
  },
  {
    id: "q-003",
    patchId: "p-1003",
    projectName: "notification-engine",
    filePath: "src/main/java/com/acme/notify/NotificationDispatcher.java",
    rule: "reactive-conversion",
    riskTier: "critical",
    confidence: 0.71,
    status: "reviewing",
    priority: 0,
    createdAt: "2026-02-16T07:55:00Z",
    estimatedReviewTime: "12 min",
  },
  {
    id: "q-004",
    patchId: "p-1004",
    projectName: "inventory-service",
    filePath: "src/main/java/com/acme/inventory/StockManager.java",
    rule: "java-stream-migration",
    riskTier: "low",
    confidence: 0.96,
    status: "pending",
    priority: 3,
    createdAt: "2026-02-16T07:48:00Z",
    estimatedReviewTime: "2 min",
  },
  {
    id: "q-005",
    patchId: "p-1005",
    projectName: "payment-service",
    filePath: "src/main/java/com/acme/payment/TransactionLogger.java",
    rule: "slf4j-migration",
    riskTier: "low",
    confidence: 0.98,
    status: "pending",
    priority: 5,
    createdAt: "2026-02-16T07:40:00Z",
    estimatedReviewTime: "1 min",
  },
  {
    id: "q-006",
    patchId: "p-1006",
    projectName: "search-indexer",
    filePath: "src/main/java/com/acme/search/IndexBuilder.java",
    rule: "concurrent-collections",
    riskTier: "high",
    confidence: 0.82,
    status: "pending",
    priority: 1,
    createdAt: "2026-02-16T07:32:00Z",
    estimatedReviewTime: "8 min",
  },
  {
    id: "q-007",
    patchId: "p-1007",
    projectName: "user-management",
    filePath: "src/main/java/com/acme/user/SessionManager.java",
    rule: "optional-null-safety",
    riskTier: "medium",
    confidence: 0.89,
    status: "deferred",
    priority: 4,
    createdAt: "2026-02-16T07:20:00Z",
    estimatedReviewTime: "4 min",
  },
  {
    id: "q-008",
    patchId: "p-1008",
    projectName: "api-gateway",
    filePath: "src/main/java/com/acme/gateway/RateLimiter.java",
    rule: "java-time-migration",
    riskTier: "medium",
    confidence: 0.91,
    status: "pending",
    priority: 3,
    createdAt: "2026-02-16T07:15:00Z",
    estimatedReviewTime: "3 min",
  },
  {
    id: "q-009",
    patchId: "p-1009",
    projectName: "notification-engine",
    filePath: "src/main/java/com/acme/notify/EmailQueue.java",
    rule: "reactive-conversion",
    riskTier: "high",
    confidence: 0.76,
    status: "pending",
    priority: 1,
    createdAt: "2026-02-16T07:08:00Z",
    estimatedReviewTime: "10 min",
  },
  {
    id: "q-010",
    patchId: "p-1010",
    projectName: "report-generator",
    filePath: "src/main/java/com/acme/reports/ChartBuilder.java",
    rule: "java-stream-migration",
    riskTier: "low",
    confidence: 0.95,
    status: "pending",
    priority: 4,
    createdAt: "2026-02-16T07:00:00Z",
    estimatedReviewTime: "2 min",
  },
];

type SortKey = "rule" | "filePath" | "riskTier" | "confidence" | "status" | "priority";
type SortDir = "asc" | "desc";

const riskOrder = { low: 0, medium: 1, high: 2, critical: 3 };

export default function QueuePage() {
  const [search, setSearch] = useState("");
  const [riskFilter, setRiskFilter] = useState<string>("all");
  const [statusFilter, setStatusFilter] = useState<string>("all");
  const [sortKey, setSortKey] = useState<SortKey>("priority");
  const [sortDir, setSortDir] = useState<SortDir>("asc");

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) {
      setSortDir(sortDir === "asc" ? "desc" : "asc");
    } else {
      setSortKey(key);
      setSortDir("asc");
    }
  };

  const filtered = useMemo(() => {
    let items = [...mockQueue];

    if (search) {
      const q = search.toLowerCase();
      items = items.filter(
        (i) =>
          i.filePath.toLowerCase().includes(q) ||
          i.rule.toLowerCase().includes(q) ||
          i.projectName.toLowerCase().includes(q)
      );
    }

    if (riskFilter !== "all") {
      items = items.filter((i) => i.riskTier === riskFilter);
    }

    if (statusFilter !== "all") {
      items = items.filter((i) => i.status === statusFilter);
    }

    items.sort((a, b) => {
      let cmp = 0;
      switch (sortKey) {
        case "riskTier":
          cmp = riskOrder[a.riskTier] - riskOrder[b.riskTier];
          break;
        case "confidence":
          cmp = a.confidence - b.confidence;
          break;
        case "priority":
          cmp = a.priority - b.priority;
          break;
        default:
          cmp = String(a[sortKey]).localeCompare(String(b[sortKey]));
      }
      return sortDir === "desc" ? -cmp : cmp;
    });

    return items;
  }, [search, riskFilter, statusFilter, sortKey, sortDir]);

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
      {/* Page Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-shadow-text">
            Refactor Queue
          </h1>
          <p className="text-sm text-shadow-text-muted mt-1">
            {filtered.length} patches awaiting review
          </p>
        </div>
        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 px-2.5 py-1.5 rounded-lg bg-shadow-surface/60 border border-shadow-border text-xs text-shadow-text-muted">
            <Clock className="h-3.5 w-3.5" />
            <span>Est. total review time: ~48 min</span>
          </div>
        </div>
      </div>

      {/* Filters & Search Bar */}
      <div className="glass-panel p-4">
        <div className="flex flex-wrap items-center gap-3">
          {/* Search */}
          <div className="relative flex-1 min-w-[240px]">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-shadow-text-muted" />
            <input
              type="text"
              placeholder="Search by file, rule, or project..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="w-full pl-10 pr-4 py-2 rounded-lg bg-shadow-bg border border-shadow-border text-sm text-shadow-text placeholder:text-shadow-text-muted focus:outline-none focus:border-shadow-accent/50 focus:ring-1 focus:ring-shadow-accent/30 transition-colors"
            />
          </div>

          {/* Risk filter */}
          <div className="flex items-center gap-2">
            <Filter className="h-3.5 w-3.5 text-shadow-text-muted" />
            <select
              value={riskFilter}
              onChange={(e) => setRiskFilter(e.target.value)}
              className="px-3 py-2 rounded-lg bg-shadow-bg border border-shadow-border text-xs text-shadow-text focus:outline-none focus:border-shadow-accent/50 cursor-pointer"
            >
              <option value="all">All Risk Tiers</option>
              <option value="low">Low</option>
              <option value="medium">Medium</option>
              <option value="high">High</option>
              <option value="critical">Critical</option>
            </select>
          </div>

          {/* Status filter */}
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="px-3 py-2 rounded-lg bg-shadow-bg border border-shadow-border text-xs text-shadow-text focus:outline-none focus:border-shadow-accent/50 cursor-pointer"
          >
            <option value="all">All Statuses</option>
            <option value="pending">Pending</option>
            <option value="reviewing">Reviewing</option>
            <option value="deferred">Deferred</option>
          </select>
        </div>
      </div>

      {/* Queue Table */}
      <div className="glass-panel overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full">
            <thead>
              <tr className="border-b border-shadow-border bg-shadow-surface/30">
                {[
                  { key: "priority" as SortKey, label: "#" },
                  { key: "rule" as SortKey, label: "Rule" },
                  { key: "filePath" as SortKey, label: "File" },
                  { key: "riskTier" as SortKey, label: "Risk Tier" },
                  { key: "confidence" as SortKey, label: "Confidence" },
                  { key: "status" as SortKey, label: "Status" },
                ].map(({ key, label }) => (
                  <th
                    key={key}
                    onClick={() => toggleSort(key)}
                    className="px-4 py-3 text-left text-2xs font-semibold text-shadow-text-muted uppercase tracking-wider cursor-pointer hover:text-shadow-text transition-colors select-none"
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
                <tr
                  key={item.id}
                  className="group hover:bg-shadow-surface-hover/50 transition-colors"
                >
                  <td className="px-4 py-3.5">
                    <span className="text-xs font-mono text-shadow-text-muted">
                      {item.priority}
                    </span>
                  </td>
                  <td className="px-4 py-3.5">
                    <div className="flex items-center gap-2">
                      <FileCode2 className="h-3.5 w-3.5 text-shadow-accent flex-shrink-0" />
                      <span className="text-xs font-mono text-shadow-accent-bright">
                        {item.rule}
                      </span>
                    </div>
                  </td>
                  <td className="px-4 py-3.5">
                    <div>
                      <span className="text-xs text-shadow-text truncate block max-w-[320px]">
                        {item.filePath.split("/").pop()}
                      </span>
                      <span className="text-2xs text-shadow-text-muted">
                        {item.projectName}
                      </span>
                    </div>
                  </td>
                  <td className="px-4 py-3.5">
                    <Badge variant="risk" riskTier={item.riskTier} size="sm">
                      {item.riskTier}
                    </Badge>
                  </td>
                  <td className="px-4 py-3.5">
                    <div className="flex items-center gap-2">
                      <div className="w-16 h-1.5 rounded-full bg-shadow-border overflow-hidden">
                        <div
                          className={cn(
                            "h-full rounded-full transition-all",
                            item.confidence >= 0.9
                              ? "bg-emerald-500"
                              : item.confidence >= 0.8
                              ? "bg-shadow-accent"
                              : item.confidence >= 0.7
                              ? "bg-amber-500"
                              : "bg-red-500"
                          )}
                          style={{ width: `${item.confidence * 100}%` }}
                        />
                      </div>
                      <span className="text-xs font-mono text-shadow-text-secondary">
                        {(item.confidence * 100).toFixed(0)}%
                      </span>
                    </div>
                  </td>
                  <td className="px-4 py-3.5">
                    <Badge
                      color={
                        item.status === "pending"
                          ? "amber"
                          : item.status === "reviewing"
                          ? "blue"
                          : item.status === "deferred"
                          ? "gray"
                          : "green"
                      }
                      pulse={item.status === "reviewing"}
                    >
                      {item.status}
                    </Badge>
                  </td>
                  <td className="px-4 py-3.5">
                    <Link href={`/review?patch=${item.patchId}`}>
                      <Button variant="ghost" size="sm" className="opacity-0 group-hover:opacity-100 transition-opacity">
                        <span className="text-2xs">Review</span>
                        <ChevronRight className="h-3 w-3" />
                      </Button>
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* Empty state */}
        {filtered.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16 text-shadow-text-muted">
            <ListTodo className="h-10 w-10 mb-3 opacity-40" />
            <p className="text-sm font-medium">No patches match your filters</p>
            <p className="text-xs mt-1">Try adjusting your search or filter criteria</p>
          </div>
        )}
      </div>
    </div>
  );
}
