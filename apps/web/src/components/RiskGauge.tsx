"use client";

import { cn } from "@/lib/utils";

interface RiskGaugeProps {
  value: number; // 0.0 to 1.0
  label?: string;
  size?: "sm" | "md" | "lg";
  className?: string;
}

/** Thresholds aligned with shadowstack.risk.* (0.3 / 0.6 / 0.85). */
function getGaugeColor(value: number): string {
  if (value <= 0.3) return "#10b981";
  if (value <= 0.6) return "#f59e0b";
  if (value <= 0.85) return "#f97316";
  return "#ef4444";
}

function getRiskLabel(value: number): string {
  if (value <= 0.3) return "Low residual";
  if (value <= 0.6) return "Medium residual";
  if (value <= 0.85) return "High residual";
  return "Critical residual";
}

export function RiskGauge({
  value,
  label,
  size = "md",
  className,
}: RiskGaugeProps) {
  const clampedValue = Math.max(0, Math.min(1, value));
  const color = getGaugeColor(clampedValue);
  const riskLabel = label || getRiskLabel(clampedValue);

  const sizeMap = {
    sm: { width: 120, height: 70, strokeWidth: 8, fontSize: "text-lg", subFontSize: "text-2xs" },
    md: { width: 180, height: 100, strokeWidth: 10, fontSize: "text-2xl", subFontSize: "text-xs" },
    lg: { width: 240, height: 130, strokeWidth: 12, fontSize: "text-3xl", subFontSize: "text-sm" },
  };

  const s = sizeMap[size];
  const centerX = s.width / 2;
  const centerY = s.height - 10;
  const radius = centerX - s.strokeWidth;

  const startAngle = Math.PI;
  const endAngle = 0;
  const sweepAngle = startAngle - (startAngle - endAngle) * clampedValue;

  const bgArcStartX = centerX + radius * Math.cos(startAngle);
  const bgArcStartY = centerY - radius * Math.sin(startAngle);
  const bgArcEndX = centerX + radius * Math.cos(endAngle);
  const bgArcEndY = centerY - radius * Math.sin(endAngle);

  const valueArcEndX = centerX + radius * Math.cos(sweepAngle);
  const valueArcEndY = centerY - radius * Math.sin(sweepAngle);
  const largeArc = clampedValue > 0.5 ? 1 : 0;

  return (
    <div className={cn("flex flex-col items-center", className)}>
      <svg
        width={s.width}
        height={s.height}
        viewBox={`0 0 ${s.width} ${s.height}`}
        className="overflow-visible"
      >
        <path
          d={`M ${bgArcStartX} ${bgArcStartY} A ${radius} ${radius} 0 0 1 ${bgArcEndX} ${bgArcEndY}`}
          fill="none"
          stroke="rgba(100,116,139,0.2)"
          strokeWidth={s.strokeWidth}
          strokeLinecap="round"
        />

        {clampedValue > 0.01 && (
          <path
            d={`M ${bgArcStartX} ${bgArcStartY} A ${radius} ${radius} 0 ${largeArc} 1 ${valueArcEndX} ${valueArcEndY}`}
            fill="none"
            stroke={color}
            strokeWidth={s.strokeWidth}
            strokeLinecap="round"
            style={{
              transition: "all 0.8s cubic-bezier(0.4, 0, 0.2, 1)",
            }}
          />
        )}

        {clampedValue > 0.01 && (
          <circle
            cx={valueArcEndX}
            cy={valueArcEndY}
            r={s.strokeWidth / 2 + 2}
            fill={color}
            opacity={0.4}
            style={{
              transition: "all 0.8s cubic-bezier(0.4, 0, 0.2, 1)",
            }}
          />
        )}
      </svg>

      <div className="flex flex-col items-center -mt-2">
        <span className={cn(s.fontSize, "font-bold tracking-tight")} style={{ color }}>
          {(clampedValue * 100).toFixed(0)}
          <span className="text-xs font-normal text-shadow-text-muted ml-0.5">%</span>
        </span>
        <span className={cn(s.subFontSize, "text-shadow-text-muted font-medium mt-0.5")}>
          {riskLabel}
        </span>
      </div>
    </div>
  );
}
