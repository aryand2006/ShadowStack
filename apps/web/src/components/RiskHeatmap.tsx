"use client";

import { cn } from "@/lib/utils";
import { useState } from "react";

interface HeatmapCell {
  module: string;
  risk: number; // 0.0 - 1.0
  patchCount: number;
  status: "clean" | "pending" | "reviewing" | "modernized";
}

interface RiskHeatmapProps {
  cells: HeatmapCell[];
  className?: string;
}

function getRiskGradient(risk: number): string {
  if (risk < 0.2) return "bg-emerald-500/60 hover:bg-emerald-500/80";
  if (risk < 0.4) return "bg-emerald-600/50 hover:bg-emerald-600/70";
  if (risk < 0.6) return "bg-amber-500/50 hover:bg-amber-500/70";
  if (risk < 0.8) return "bg-orange-500/50 hover:bg-orange-500/70";
  return "bg-red-500/60 hover:bg-red-500/80";
}

function getRiskBorder(risk: number): string {
  if (risk < 0.2) return "border-emerald-500/30";
  if (risk < 0.4) return "border-emerald-600/30";
  if (risk < 0.6) return "border-amber-500/30";
  if (risk < 0.8) return "border-orange-500/30";
  return "border-red-500/30";
}

export function RiskHeatmap({ cells, className }: RiskHeatmapProps) {
  const [hoveredCell, setHoveredCell] = useState<HeatmapCell | null>(null);

  return (
    <div className={cn("relative", className)}>
      {/* Legend */}
      <div className="flex items-center justify-between mb-4">
        <span className="section-header">Module Risk Heatmap</span>
        <div className="flex items-center gap-1">
          <span className="text-2xs text-shadow-text-muted mr-2">Risk:</span>
          <div className="flex gap-0.5">
            {[
              "bg-emerald-500/60",
              "bg-emerald-600/50",
              "bg-amber-500/50",
              "bg-orange-500/50",
              "bg-red-500/60",
            ].map((color, i) => (
              <div key={i} className={cn("w-5 h-2.5 rounded-sm", color)} />
            ))}
          </div>
          <span className="text-2xs text-shadow-text-muted ml-1">Low → Critical</span>
        </div>
      </div>

      {/* Grid */}
      <div className="grid grid-cols-8 gap-1.5">
        {cells.map((cell, i) => (
          <div
            key={i}
            className={cn(
              "relative aspect-square rounded-md border cursor-pointer transition-all duration-200",
              "flex items-center justify-center",
              getRiskGradient(cell.risk),
              getRiskBorder(cell.risk),
              hoveredCell === cell && "ring-1 ring-white/30 scale-105 z-10"
            )}
            onMouseEnter={() => setHoveredCell(cell)}
            onMouseLeave={() => setHoveredCell(null)}
          >
            <span className="text-2xs font-mono text-white/80 truncate px-1">
              {cell.module.slice(0, 6)}
            </span>

            {/* Status indicator dot */}
            {cell.status === "pending" && (
              <div className="absolute top-0.5 right-0.5 w-1.5 h-1.5 rounded-full bg-amber-400" />
            )}
            {cell.status === "reviewing" && (
              <div className="absolute top-0.5 right-0.5 w-1.5 h-1.5 rounded-full bg-blue-400 animate-pulse" />
            )}
            {cell.status === "modernized" && (
              <div className="absolute top-0.5 right-0.5 w-1.5 h-1.5 rounded-full bg-emerald-400" />
            )}
          </div>
        ))}
      </div>

      {/* Tooltip */}
      {hoveredCell && (
        <div className="absolute bottom-full left-1/2 -translate-x-1/2 mb-2 px-3 py-2 rounded-lg bg-shadow-surface border border-shadow-border-bright shadow-xl z-20 whitespace-nowrap animate-fade-in">
          <div className="text-xs font-medium text-shadow-text">
            {hoveredCell.module}
          </div>
          <div className="flex items-center gap-3 mt-1">
            <span className="text-2xs text-shadow-text-muted">
              Risk: <span className="text-shadow-text font-medium">{(hoveredCell.risk * 100).toFixed(0)}%</span>
            </span>
            <span className="text-2xs text-shadow-text-muted">
              Patches: <span className="text-shadow-text font-medium">{hoveredCell.patchCount}</span>
            </span>
            <span className="text-2xs text-shadow-text-muted capitalize">
              {hoveredCell.status}
            </span>
          </div>
        </div>
      )}
    </div>
  );
}
