"use client";

import { StatCard } from "@/components/StatCard";
import { RiskHeatmap } from "@/components/RiskHeatmap";
import { Badge } from "@/components/Badge";
import {
  Shield,
  GitPullRequest,
  CheckCircle2,
  AlertTriangle,
  Clock,
  TrendingUp,
  FileCode2,
  Activity,
} from "lucide-react";

/* ─── Mock Data ──────────────────────────────────────────────────────────────
 *  In production, this would come from the /api/v1/analytics/dashboard and
 *  /api/v1/projects endpoints. The shapes match the TypeScript types defined
 *  in src/types/index.ts.
 * ──────────────────────────────────────────────────────────────────────────── */

const stats = [
  {
    label: "Total Patches",
    value: "1,247",
    icon: <GitPullRequest className="h-4 w-4" />,
    trend: { value: 12.3, direction: "up" as const, label: "vs last week" },
    accentColor: "#3b82f6",
  },
  {
    label: "Acceptance Rate",
    value: "94.2%",
    icon: <CheckCircle2 className="h-4 w-4" />,
    trend: { value: 2.1, direction: "up" as const, label: "vs last week" },
    accentColor: "#10b981",
  },
  {
    label: "Verified Patches",
    value: "1,089",
    icon: <Shield className="h-4 w-4" />,
    trend: { value: 8.7, direction: "up" as const, label: "vs last week" },
    accentColor: "#8b5cf6",
  },
  {
    label: "Pending Review",
    value: "23",
    icon: <Clock className="h-4 w-4" />,
    trend: { value: 4.2, direction: "down" as const, label: "vs last week" },
    accentColor: "#f59e0b",
  },
  {
    label: "Avg Confidence",
    value: "0.91",
    icon: <TrendingUp className="h-4 w-4" />,
    trend: { value: 0.3, direction: "up" as const, label: "calibrated" },
    accentColor: "#06b6d4",
  },
  {
    label: "Active Projects",
    value: "7",
    icon: <FileCode2 className="h-4 w-4" />,
    trend: { value: 0, direction: "flat" as const },
    accentColor: "#ec4899",
  },
];

const heatmapCells = [
  { module: "AuthSvc", risk: 0.12, patchCount: 3, status: "modernized" as const },
  { module: "PayAPI", risk: 0.85, patchCount: 12, status: "reviewing" as const },
  { module: "UserDAO", risk: 0.45, patchCount: 7, status: "pending" as const },
  { module: "NotifQ", risk: 0.23, patchCount: 2, status: "modernized" as const },
  { module: "InvSvc", risk: 0.67, patchCount: 9, status: "pending" as const },
  { module: "RptGen", risk: 0.34, patchCount: 4, status: "clean" as const },
  { module: "SrchIdx", risk: 0.78, patchCount: 11, status: "reviewing" as const },
  { module: "CchMgr", risk: 0.15, patchCount: 1, status: "modernized" as const },
  { module: "EmailQ", risk: 0.56, patchCount: 6, status: "pending" as const },
  { module: "LogAgg", risk: 0.09, patchCount: 1, status: "modernized" as const },
  { module: "MetSvc", risk: 0.42, patchCount: 5, status: "pending" as const },
  { module: "AudLog", risk: 0.31, patchCount: 3, status: "clean" as const },
  { module: "CfgMgr", risk: 0.18, patchCount: 2, status: "modernized" as const },
  { module: "FeatFl", risk: 0.06, patchCount: 0, status: "clean" as const },
  { module: "SchJob", risk: 0.72, patchCount: 8, status: "reviewing" as const },
  { module: "MsgBus", risk: 0.91, patchCount: 14, status: "pending" as const },
  { module: "GtwAPI", risk: 0.38, patchCount: 4, status: "pending" as const },
  { module: "SessMg", risk: 0.53, patchCount: 5, status: "reviewing" as const },
  { module: "TokSvc", risk: 0.27, patchCount: 3, status: "modernized" as const },
  { module: "RtLim", risk: 0.62, patchCount: 7, status: "pending" as const },
  { module: "BulkIm", risk: 0.81, patchCount: 10, status: "reviewing" as const },
  { module: "WebHk", risk: 0.44, patchCount: 5, status: "pending" as const },
  { module: "DtaPip", risk: 0.71, patchCount: 8, status: "pending" as const },
  { module: "FileSt", risk: 0.19, patchCount: 2, status: "modernized" as const },
];

const activeRefactors = [
  {
    project: "payment-service",
    rule: "java-stream-migration",
    progress: 72,
    patchesReady: 18,
    patchesTotal: 25,
  },
  {
    project: "user-management",
    rule: "optional-null-safety",
    progress: 45,
    patchesReady: 9,
    patchesTotal: 20,
  },
  {
    project: "notification-engine",
    rule: "reactive-conversion",
    progress: 91,
    patchesReady: 30,
    patchesTotal: 33,
  },
];

const recentActivity = [
  {
    action: "Patch Accepted",
    detail: "java-stream-migration on PaymentProcessor.java",
    time: "2 min ago",
    type: "success",
  },
  {
    action: "Verification Failed",
    detail: "Bytecode mismatch in UserDAO.findByEmail()",
    time: "8 min ago",
    type: "danger",
  },
  {
    action: "New Candidate",
    detail: "optional-null-safety detected in SessionManager.java",
    time: "15 min ago",
    type: "info",
  },
  {
    action: "Patch Accepted",
    detail: "reactive-conversion on NotificationDispatcher.java",
    time: "22 min ago",
    type: "success",
  },
  {
    action: "Review Deferred",
    detail: "Complex refactor in InventoryService — needs team review",
    time: "34 min ago",
    type: "warning",
  },
  {
    action: "Patch Accepted",
    detail: "java-stream-migration on ReportGenerator.java",
    time: "1 hour ago",
    type: "success",
  },
];

const activityIcons: Record<string, string> = {
  success: "text-emerald-400",
  danger: "text-red-400",
  warning: "text-amber-400",
  info: "text-blue-400",
};

export default function DashboardPage() {
  return (
    <div className="space-y-8 animate-in">
      {/* Page Header */}
      <div>
        <h1 className="text-2xl font-bold tracking-tight text-shadow-text">
          Migration Dashboard
        </h1>
        <p className="text-sm text-shadow-text-muted mt-1">
          Overview of verified language modernization across all projects
        </p>
      </div>

      {/* Quick Stats Grid */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6 gap-4">
        {stats.map((stat) => (
          <StatCard key={stat.label} {...stat} />
        ))}
      </div>

      {/* Main Content Grid */}
      <div className="grid grid-cols-1 xl:grid-cols-3 gap-6">
        {/* Risk Heatmap — takes 2 columns */}
        <div className="xl:col-span-2 glass-panel p-6">
          <RiskHeatmap cells={heatmapCells} />
        </div>

        {/* Active Refactoring Summary */}
        <div className="glass-panel p-6">
          <div className="flex items-center justify-between mb-5">
            <span className="section-header">Active Refactors</span>
            <Badge color="blue" pulse>
              {activeRefactors.length} running
            </Badge>
          </div>

          <div className="space-y-4">
            {activeRefactors.map((refactor, i) => (
              <div
                key={i}
                className="p-3 rounded-lg bg-shadow-surface/40 border border-shadow-border/50 space-y-2.5"
              >
                <div className="flex items-center justify-between">
                  <span className="text-sm font-medium text-shadow-text">
                    {refactor.project}
                  </span>
                  <span className="text-xs text-shadow-text-muted font-mono">
                    {refactor.patchesReady}/{refactor.patchesTotal}
                  </span>
                </div>
                <div className="text-2xs text-shadow-text-muted">
                  Rule: <span className="text-shadow-accent-bright font-mono">{refactor.rule}</span>
                </div>
                {/* Progress bar */}
                <div className="relative h-1.5 rounded-full bg-shadow-border overflow-hidden">
                  <div
                    className="absolute inset-y-0 left-0 rounded-full bg-gradient-to-r from-shadow-accent to-shadow-accent-bright transition-all duration-500"
                    style={{ width: `${refactor.progress}%` }}
                  />
                </div>
                <div className="text-right text-2xs text-shadow-text-muted">
                  {refactor.progress}% complete
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Recent Activity Feed */}
      <div className="glass-panel p-6">
        <div className="flex items-center gap-2 mb-5">
          <Activity className="h-4 w-4 text-shadow-accent" />
          <span className="section-header">Recent Activity</span>
        </div>

        <div className="space-y-1">
          {recentActivity.map((item, i) => (
            <div
              key={i}
              className="flex items-center gap-4 px-3 py-2.5 rounded-lg hover:bg-shadow-surface-hover transition-colors"
            >
              <div className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${
                item.type === "success"
                  ? "bg-emerald-400"
                  : item.type === "danger"
                  ? "bg-red-400"
                  : item.type === "warning"
                  ? "bg-amber-400"
                  : "bg-blue-400"
              }`} />
              <span
                className={`text-xs font-medium w-36 flex-shrink-0 ${activityIcons[item.type]}`}
              >
                {item.action}
              </span>
              <span className="text-xs text-shadow-text-secondary flex-1 truncate">
                {item.detail}
              </span>
              <span className="text-2xs text-shadow-text-muted flex-shrink-0">
                {item.time}
              </span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
