"use client";

import { StatCard } from "@/components/StatCard";
import {
  AcceptanceBarChart,
  RiskDonutChart,
  CalibrationChart,
  VolumeChart,
} from "@/components/Chart";
import { Badge } from "@/components/Badge";
import {
  BarChart3,
  Target,
  ShieldCheck,
  Clock,
  TrendingUp,
  AlertTriangle,
  Database,
  FileCode2,
  GitBranch,
  Layers,
} from "lucide-react";

/* ─── Mock Analytics Data ────────────────────────────────────────────────────
 *  In production, comes from GET /api/v1/analytics/dashboard and
 *  GET /api/v1/analytics/corpus/:projectId
 * ──────────────────────────────────────────────────────────────────────────── */

const summaryStats = [
  {
    label: "Total Patches",
    value: "1,247",
    icon: <GitBranch className="h-4 w-4" />,
    trend: { value: 12.3, direction: "up" as const, label: "this month" },
    accentColor: "#3b82f6",
  },
  {
    label: "Acceptance Rate",
    value: "94.2%",
    icon: <Target className="h-4 w-4" />,
    trend: { value: 2.1, direction: "up" as const, label: "vs last month" },
    accentColor: "#10b981",
  },
  {
    label: "Avg Confidence",
    value: "0.91",
    icon: <TrendingUp className="h-4 w-4" />,
    trend: { value: 0.3, direction: "up" as const, label: "calibrated" },
    accentColor: "#8b5cf6",
  },
  {
    label: "Verified",
    value: "1,089",
    icon: <ShieldCheck className="h-4 w-4" />,
    trend: { value: 8.7, direction: "up" as const, label: "this month" },
    accentColor: "#06b6d4",
  },
  {
    label: "Pending Review",
    value: "23",
    icon: <Clock className="h-4 w-4" />,
    trend: { value: 4.2, direction: "down" as const, label: "reducing" },
    accentColor: "#f59e0b",
  },
  {
    label: "Failure Rate",
    value: "3.1%",
    icon: <AlertTriangle className="h-4 w-4" />,
    trend: { value: 0.8, direction: "down" as const, label: "improving" },
    accentColor: "#ef4444",
  },
];

const acceptanceByRule = [
  { rule: "stream-api", accepted: 187, rejected: 8, total: 195 },
  { rule: "optional-null", accepted: 142, rejected: 12, total: 154 },
  { rule: "reactive-conv", accepted: 89, rejected: 21, total: 110 },
  { rule: "slf4j-migrate", accepted: 201, rejected: 3, total: 204 },
  { rule: "java-time", accepted: 156, rejected: 6, total: 162 },
  { rule: "concurrent-col", accepted: 67, rejected: 15, total: 82 },
  { rule: "try-resources", accepted: 178, rejected: 2, total: 180 },
  { rule: "diamond-op", accepted: 160, rejected: 0, total: 160 },
];

const riskDistribution = { low: 487, medium: 412, high: 278, critical: 70 };

const confidenceCalibration = [
  { predicted: 0.5, actual: 0.48 },
  { predicted: 0.55, actual: 0.54 },
  { predicted: 0.6, actual: 0.57 },
  { predicted: 0.65, actual: 0.63 },
  { predicted: 0.7, actual: 0.69 },
  { predicted: 0.75, actual: 0.74 },
  { predicted: 0.8, actual: 0.79 },
  { predicted: 0.85, actual: 0.86 },
  { predicted: 0.9, actual: 0.91 },
  { predicted: 0.95, actual: 0.96 },
];

const volumeOverTime = [
  { date: "Jan 1", generated: 45, accepted: 38, rejected: 4 },
  { date: "Jan 8", generated: 62, accepted: 55, rejected: 5 },
  { date: "Jan 15", generated: 78, accepted: 72, rejected: 3 },
  { date: "Jan 22", generated: 91, accepted: 84, rejected: 4 },
  { date: "Jan 29", generated: 105, accepted: 98, rejected: 5 },
  { date: "Feb 5", generated: 128, accepted: 119, rejected: 6 },
  { date: "Feb 12", generated: 142, accepted: 135, rejected: 4 },
  { date: "Feb 16", generated: 156, accepted: 148, rejected: 3 },
];

const topFailurePatterns = [
  {
    pattern: "Bytecode descriptor mismatch — generic erasure",
    count: 12,
    lastSeen: "2 hours ago",
    severity: "high",
  },
  {
    pattern: "Test assertion order changed — stream vs iterator",
    count: 8,
    lastSeen: "5 hours ago",
    severity: "medium",
  },
  {
    pattern: "Null input behavior divergence",
    count: 6,
    lastSeen: "1 day ago",
    severity: "high",
  },
  {
    pattern: "Golden master timeout — async race condition",
    count: 4,
    lastSeen: "2 days ago",
    severity: "critical",
  },
  {
    pattern: "API surface regression — overloaded method removed",
    count: 3,
    lastSeen: "3 days ago",
    severity: "medium",
  },
];

const corpusStats = [
  { label: "Total Files Analyzed", value: "3,842", icon: <FileCode2 className="h-4 w-4" /> },
  { label: "Total Lines of Code", value: "891K", icon: <Layers className="h-4 w-4" /> },
  { label: "Coverage", value: "78.3%", icon: <ShieldCheck className="h-4 w-4" /> },
  { label: "Tech Debt Score", value: "34/100", icon: <Database className="h-4 w-4" /> },
];

export default function AnalyticsPage() {
  return (
    <div className="space-y-8 animate-in">
      {/* Page Header */}
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-shadow-text">
          Migration Intelligence
        </h1>
        <p className="text-sm text-shadow-text-muted mt-1">
          Analytics and insights across all modernization activity
        </p>
      </div>

      {/* Summary Stats */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4">
        {summaryStats.map((stat) => (
          <StatCard key={stat.label} {...stat} />
        ))}
      </div>

      {/* Charts Row 1: Acceptance + Risk Distribution */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        {/* Acceptance by Rule */}
        <div className="xl:col-span-2 glass-panel p-6">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <BarChart3 className="h-4 w-4 text-shadow-accent" />
              <span className="text-sm font-semibold text-shadow-text">
                Acceptance Rate by Rule
              </span>
            </div>
            <span className="text-2xs text-shadow-text-muted">
              Last 30 days
            </span>
          </div>
          <AcceptanceBarChart data={acceptanceByRule} />
        </div>

        {/* Risk Distribution */}
        <div className="glass-panel p-6">
          <div className="flex items-center gap-2 mb-4">
            <Target className="h-4 w-4 text-shadow-accent" />
            <span className="text-sm font-semibold text-shadow-text">
              Risk Distribution
            </span>
          </div>
          <RiskDonutChart data={riskDistribution} />
          {/* Legend */}
          <div className="grid grid-cols-2 gap-2 mt-4">
            {[
              { label: "Low", value: riskDistribution.low, color: "bg-emerald-500" },
              { label: "Medium", value: riskDistribution.medium, color: "bg-amber-500" },
              { label: "High", value: riskDistribution.high, color: "bg-orange-500" },
              { label: "Critical", value: riskDistribution.critical, color: "bg-red-500" },
            ].map((item) => (
              <div key={item.label} className="flex items-center gap-2 text-xs">
                <div className={`w-2.5 h-2.5 rounded-sm ${item.color}`} />
                <span className="text-shadow-text-muted">{item.label}</span>
                <span className="text-shadow-text font-medium ml-auto">{item.value}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Charts Row 2: Calibration + Volume */}
      <div className="grid grid-cols-1 xl:grid-cols-2 gap-6">
        {/* Confidence Calibration */}
        <div className="glass-panel p-6">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <TrendingUp className="h-4 w-4 text-shadow-accent" />
              <span className="text-sm font-semibold text-shadow-text">
                Confidence Calibration
              </span>
            </div>
            <span className="text-2xs text-shadow-text-muted">
              Predicted vs Actual Success
            </span>
          </div>
          <CalibrationChart data={confidenceCalibration} />
          <p className="text-2xs text-shadow-text-muted mt-3 text-center">
            Dashed line = perfect calibration. Close alignment indicates well-calibrated confidence scores.
          </p>
        </div>

        {/* Migration Volume */}
        <div className="glass-panel p-6">
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <Layers className="h-4 w-4 text-shadow-accent" />
              <span className="text-sm font-semibold text-shadow-text">
                Migration Volume Over Time
              </span>
            </div>
            <span className="text-2xs text-shadow-text-muted">
              Weekly totals
            </span>
          </div>
          <VolumeChart data={volumeOverTime} />
        </div>
      </div>

      {/* Bottom Row: Failure Patterns + Corpus Stats */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        {/* Top Failure Patterns Table */}
        <div className="xl:col-span-2 glass-panel p-6">
          <div className="flex items-center justify-between mb-5">
            <div className="flex items-center gap-2">
              <AlertTriangle className="h-4 w-4 text-amber-400" />
              <span className="text-sm font-semibold text-shadow-text">
                Top Failure Patterns
              </span>
            </div>
            <Badge color="amber">{topFailurePatterns.length} patterns</Badge>
          </div>

          <div className="space-y-1">
            {/* Table header */}
            <div className="flex items-center gap-4 px-3 py-2 text-2xs font-semibold text-shadow-text-muted uppercase tracking-wider">
              <span className="flex-1">Pattern</span>
              <span className="w-16 text-center">Count</span>
              <span className="w-20 text-center">Severity</span>
              <span className="w-24 text-right">Last Seen</span>
            </div>

            {topFailurePatterns.map((failure, i) => (
              <div
                key={i}
                className="flex items-center gap-4 px-3 py-3 rounded-lg hover:bg-shadow-surface-hover/50 transition-colors border-b border-shadow-border/30 last:border-0"
              >
                <span className="flex-1 text-xs text-shadow-text-secondary">
                  {failure.pattern}
                </span>
                <span className="w-16 text-center text-xs font-mono font-medium text-shadow-text">
                  {failure.count}
                </span>
                <div className="w-20 flex justify-center">
                  <Badge
                    variant="risk"
                    riskTier={failure.severity as "low" | "medium" | "high" | "critical"}
                    size="sm"
                  >
                    {failure.severity}
                  </Badge>
                </div>
                <span className="w-24 text-right text-2xs text-shadow-text-muted">
                  {failure.lastSeen}
                </span>
              </div>
            ))}
          </div>
        </div>

        {/* Corpus Statistics */}
        <div className="space-y-4">
          <div className="flex items-center gap-2">
            <Database className="h-4 w-4 text-shadow-accent" />
            <span className="section-header">Corpus Statistics</span>
          </div>
          {corpusStats.map((stat) => (
            <div
              key={stat.label}
              className="glass-panel p-4 flex items-center gap-4"
            >
              <div className="flex items-center justify-center w-10 h-10 rounded-lg bg-shadow-accent/10 text-shadow-accent">
                {stat.icon}
              </div>
              <div>
                <span className="text-2xs text-shadow-text-muted uppercase tracking-wider font-semibold">
                  {stat.label}
                </span>
                <div className="text-lg font-bold text-shadow-text">
                  {stat.value}
                </div>
              </div>
            </div>
          ))}

          {/* Language breakdown */}
          <div className="glass-panel p-4 space-y-3">
            <span className="text-2xs text-shadow-text-muted uppercase tracking-wider font-semibold">
              Language Breakdown
            </span>
            {[
              { lang: "Java", pct: 62.4, color: "bg-orange-500" },
              { lang: "Kotlin", pct: 18.7, color: "bg-purple-500" },
              { lang: "Groovy", pct: 11.2, color: "bg-emerald-500" },
              { lang: "XML/Config", pct: 7.7, color: "bg-sky-500" },
            ].map((item) => (
              <div key={item.lang} className="space-y-1">
                <div className="flex items-center justify-between text-xs">
                  <span className="text-shadow-text-secondary">{item.lang}</span>
                  <span className="text-shadow-text font-mono font-medium">
                    {item.pct}%
                  </span>
                </div>
                <div className="h-1.5 rounded-full bg-shadow-border overflow-hidden">
                  <div
                    className={`h-full rounded-full ${item.color} transition-all duration-500`}
                    style={{ width: `${item.pct}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
