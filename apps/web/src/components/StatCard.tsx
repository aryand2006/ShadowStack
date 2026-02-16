"use client";

import { cn } from "@/lib/utils";
import { TrendingUp, TrendingDown, Minus } from "lucide-react";

interface StatCardProps {
  label: string;
  value: string | number;
  icon?: React.ReactNode;
  trend?: {
    value: number;
    direction: "up" | "down" | "flat";
    label?: string;
  };
  accentColor?: string;
  className?: string;
}

export function StatCard({
  label,
  value,
  icon,
  trend,
  accentColor,
  className,
}: StatCardProps) {
  const TrendIcon =
    trend?.direction === "up"
      ? TrendingUp
      : trend?.direction === "down"
      ? TrendingDown
      : Minus;

  const trendColor =
    trend?.direction === "up"
      ? "text-emerald-400"
      : trend?.direction === "down"
      ? "text-red-400"
      : "text-shadow-text-muted";

  return (
    <div
      className={cn(
        "glass-panel p-5 relative overflow-hidden group",
        "hover:border-shadow-border-bright transition-all duration-300",
        className
      )}
    >
      {/* Subtle accent glow in top-left */}
      {accentColor && (
        <div
          className="absolute -top-12 -left-12 w-24 h-24 rounded-full opacity-20 blur-2xl group-hover:opacity-30 transition-opacity"
          style={{ background: accentColor }}
        />
      )}

      <div className="relative z-10">
        <div className="flex items-center justify-between mb-3">
          <span className="section-header">{label}</span>
          {icon && (
            <span className="text-shadow-text-muted">{icon}</span>
          )}
        </div>

        <div className="stat-value">{value}</div>

        {trend && (
          <div className={cn("flex items-center gap-1 mt-2 text-xs", trendColor)}>
            <TrendIcon className="h-3 w-3" />
            <span className="font-medium">
              {trend.direction === "up" ? "+" : trend.direction === "down" ? "-" : ""}
              {trend.value}%
            </span>
            {trend.label && (
              <span className="text-shadow-text-muted">{trend.label}</span>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
