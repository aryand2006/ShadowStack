import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/** Merge Tailwind classes with clsx for conditional class composition */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Format a number as a percentage string */
export function formatPercent(value: number, decimals = 1): string {
  return `${(value * 100).toFixed(decimals)}%`;
}

/** Format a number with compact notation (1.2k, 3.4M, etc.) */
export function formatCompact(value: number): string {
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}k`;
  return value.toString();
}

/** Get a human-readable relative time string */
export function timeAgo(dateStr: string): string {
  const date = new Date(dateStr);
  const now = new Date();
  const seconds = Math.floor((now.getTime() - date.getTime()) / 1000);

  if (seconds < 60) return "just now";
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  if (seconds < 604800) return `${Math.floor(seconds / 86400)}d ago`;
  return date.toLocaleDateString();
}

/** Map risk tier to a Tailwind color class */
export function riskColor(tier: string): string {
  switch (tier) {
    case "low":
      return "text-shadow-success";
    case "medium":
      return "text-shadow-warning";
    case "high":
      return "text-orange-400";
    case "critical":
      return "text-shadow-danger";
    default:
      return "text-shadow-text-muted";
  }
}

/** Map risk tier to a Tailwind bg color class */
export function riskBgColor(tier: string): string {
  switch (tier) {
    case "low":
      return "bg-emerald-500/15 text-emerald-400 border-emerald-500/30";
    case "medium":
      return "bg-amber-500/15 text-amber-400 border-amber-500/30";
    case "high":
      return "bg-orange-500/15 text-orange-400 border-orange-500/30";
    case "critical":
      return "bg-red-500/15 text-red-400 border-red-500/30";
    default:
      return "bg-shadow-surface text-shadow-text-muted border-shadow-border";
  }
}

/** Map status to styling */
export function statusColor(status: string): string {
  switch (status) {
    case "passed":
    case "pass":
    case "accepted":
      return "text-shadow-success";
    case "failed":
    case "fail":
    case "rejected":
      return "text-shadow-danger";
    case "warning":
    case "partial":
    case "deferred":
      return "text-shadow-warning";
    case "pending":
    case "unchecked":
    case "skip":
      return "text-shadow-text-muted";
    default:
      return "text-shadow-text-secondary";
  }
}
