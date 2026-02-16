"use client";

import { cn, riskBgColor } from "@/lib/utils";

interface BadgeProps {
  children: React.ReactNode;
  variant?: "default" | "risk" | "status" | "outline";
  riskTier?: "low" | "medium" | "high" | "critical";
  color?: "blue" | "green" | "amber" | "red" | "gray";
  size?: "sm" | "md";
  className?: string;
  pulse?: boolean;
}

const colorMap = {
  blue: "bg-blue-500/15 text-blue-400 border-blue-500/30",
  green: "bg-emerald-500/15 text-emerald-400 border-emerald-500/30",
  amber: "bg-amber-500/15 text-amber-400 border-amber-500/30",
  red: "bg-red-500/15 text-red-400 border-red-500/30",
  gray: "bg-slate-500/15 text-slate-400 border-slate-500/30",
};

export function Badge({
  children,
  variant = "default",
  riskTier,
  color = "blue",
  size = "sm",
  className,
  pulse,
}: BadgeProps) {
  const baseClasses =
    "inline-flex items-center gap-1 rounded-full font-medium border";

  const sizeClasses = size === "sm" ? "px-2 py-0.5 text-xs" : "px-3 py-1 text-sm";

  let variantClasses: string;
  if (variant === "risk" && riskTier) {
    variantClasses = riskBgColor(riskTier);
  } else if (variant === "outline") {
    variantClasses = "bg-transparent text-shadow-text-secondary border-shadow-border";
  } else {
    variantClasses = colorMap[color];
  }

  return (
    <span
      className={cn(baseClasses, sizeClasses, variantClasses, className)}
    >
      {pulse && (
        <span className="relative flex h-1.5 w-1.5">
          <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-current opacity-75" />
          <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-current" />
        </span>
      )}
      {children}
    </span>
  );
}
