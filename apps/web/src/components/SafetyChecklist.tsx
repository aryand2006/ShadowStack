"use client";

import { cn } from "@/lib/utils";
import type { SafetyInvariant } from "@/types";
import {
  CheckCircle2,
  XCircle,
  AlertTriangle,
  CircleDashed,
  Shield,
} from "lucide-react";

interface SafetyChecklistProps {
  invariants: SafetyInvariant[];
  className?: string;
}

const statusIcons = {
  passed: CheckCircle2,
  failed: XCircle,
  warning: AlertTriangle,
  unchecked: CircleDashed,
};

const statusColors = {
  passed: "text-emerald-400",
  failed: "text-red-400",
  warning: "text-amber-400",
  unchecked: "text-shadow-text-muted",
};

const statusBg = {
  passed: "bg-emerald-500/5 border-emerald-500/20",
  failed: "bg-red-500/5 border-red-500/20",
  warning: "bg-amber-500/5 border-amber-500/20",
  unchecked: "bg-shadow-surface border-shadow-border",
};

const categoryLabels: Record<string, string> = {
  behavioral: "Behavioral",
  structural: "Structural",
  performance: "Performance",
  security: "Security",
};

export function SafetyChecklist({ invariants, className }: SafetyChecklistProps) {
  const passedCount = invariants.filter((i) => i.status === "passed").length;
  const totalCount = invariants.length;

  return (
    <div className={cn("space-y-3", className)}>
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <Shield className="h-4 w-4 text-shadow-accent" />
          <span className="text-sm font-semibold text-shadow-text">
            Safety Invariants
          </span>
        </div>
        <span
          className={cn(
            "text-xs font-medium px-2 py-0.5 rounded-full",
            passedCount === totalCount
              ? "bg-emerald-500/15 text-emerald-400"
              : "bg-amber-500/15 text-amber-400"
          )}
        >
          {passedCount}/{totalCount} passing
        </span>
      </div>

      {/* Checklist items */}
      <div className="space-y-1.5">
        {invariants.map((invariant) => {
          const Icon = statusIcons[invariant.status];
          return (
            <div
              key={invariant.id}
              className={cn(
                "flex items-start gap-3 px-3 py-2.5 rounded-lg border transition-colors",
                statusBg[invariant.status]
              )}
            >
              <Icon
                className={cn(
                  "h-4 w-4 mt-0.5 flex-shrink-0",
                  statusColors[invariant.status]
                )}
              />
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-xs font-medium text-shadow-text">
                    {invariant.name}
                  </span>
                  <span className="text-2xs text-shadow-text-muted px-1.5 py-0.5 rounded bg-shadow-surface/50">
                    {categoryLabels[invariant.category]}
                  </span>
                </div>
                <p className="text-2xs text-shadow-text-muted mt-0.5 leading-relaxed">
                  {invariant.description}
                </p>
                {invariant.evidence && (
                  <p className="text-2xs text-shadow-text-muted/80 mt-1 font-mono">
                    ↳ {invariant.evidence}
                  </p>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
