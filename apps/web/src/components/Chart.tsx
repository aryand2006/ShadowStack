"use client";

import {
  BarChart,
  Bar,
  LineChart,
  Line,
  AreaChart,
  Area,
  PieChart,
  Pie,
  Cell,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from "recharts";
import { cn } from "@/lib/utils";

/* ─── Shared tooltip styling ──────────────────────────────────────────────── */
const tooltipStyle = {
  contentStyle: {
    backgroundColor: "#111827",
    border: "1px solid #1e293b",
    borderRadius: "8px",
    fontSize: "12px",
    color: "#f1f5f9",
    boxShadow: "0 10px 30px rgba(0,0,0,0.4)",
  },
  itemStyle: { color: "#94a3b8" },
  labelStyle: { color: "#f1f5f9", fontWeight: 600, marginBottom: 4 },
};

const axisStyle = {
  fontSize: 11,
  fill: "#64748b",
  fontFamily: "Inter, sans-serif",
};

const gridStyle = {
  stroke: "#1e293b",
  strokeDasharray: "3 3",
};

/* ─── Acceptance by Rule Bar Chart ────────────────────────────────────────── */
interface AcceptanceBarChartProps {
  data: { rule: string; accepted: number; rejected: number; total: number }[];
  className?: string;
}

export function AcceptanceBarChart({ data, className }: AcceptanceBarChartProps) {
  return (
    <div className={cn("w-full h-[300px]", className)}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={data} margin={{ top: 10, right: 10, left: -10, bottom: 0 }}>
          <CartesianGrid {...gridStyle} vertical={false} />
          <XAxis
            dataKey="rule"
            tick={axisStyle}
            axisLine={{ stroke: "#1e293b" }}
            tickLine={false}
          />
          <YAxis
            tick={axisStyle}
            axisLine={false}
            tickLine={false}
          />
          <Tooltip {...tooltipStyle} />
          <Legend
            wrapperStyle={{ fontSize: "11px", color: "#94a3b8" }}
          />
          <Bar
            dataKey="accepted"
            fill="#10b981"
            radius={[4, 4, 0, 0]}
            name="Accepted"
          />
          <Bar
            dataKey="rejected"
            fill="#ef4444"
            radius={[4, 4, 0, 0]}
            name="Rejected"
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}

/* ─── Risk Distribution Donut Chart ───────────────────────────────────────── */
interface RiskDonutChartProps {
  data: { low: number; medium: number; high: number; critical: number };
  className?: string;
}

const riskColors = ["#10b981", "#f59e0b", "#f97316", "#ef4444"];
const riskLabels = ["Low", "Medium", "High", "Critical"];

export function RiskDonutChart({ data, className }: RiskDonutChartProps) {
  const chartData = [
    { name: "Low", value: data.low },
    { name: "Medium", value: data.medium },
    { name: "High", value: data.high },
    { name: "Critical", value: data.critical },
  ];
  const total = chartData.reduce((sum, d) => sum + d.value, 0);

  return (
    <div className={cn("w-full h-[300px] relative", className)}>
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie
            data={chartData}
            cx="50%"
            cy="50%"
            innerRadius={70}
            outerRadius={100}
            paddingAngle={3}
            dataKey="value"
            stroke="none"
          >
            {chartData.map((_, index) => (
              <Cell key={index} fill={riskColors[index]} />
            ))}
          </Pie>
          <Tooltip {...tooltipStyle} />
        </PieChart>
      </ResponsiveContainer>
      {/* Center label */}
      <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
        <span className="text-2xl font-bold text-shadow-text">{total}</span>
        <span className="text-xs text-shadow-text-muted">Total Patches</span>
      </div>
    </div>
  );
}

/* ─── Confidence Calibration Line Chart ───────────────────────────────────── */
interface CalibrationChartProps {
  data: { predicted: number; actual: number }[];
  className?: string;
}

export function CalibrationChart({ data, className }: CalibrationChartProps) {
  const perfectLine = data.map((d) => ({
    ...d,
    perfect: d.predicted,
  }));

  return (
    <div className={cn("w-full h-[300px]", className)}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={perfectLine} margin={{ top: 10, right: 10, left: -10, bottom: 0 }}>
          <CartesianGrid {...gridStyle} />
          <XAxis
            dataKey="predicted"
            tick={axisStyle}
            axisLine={{ stroke: "#1e293b" }}
            tickLine={false}
            label={{
              value: "Predicted Confidence",
              position: "bottom",
              style: { ...axisStyle, fontSize: 10 },
              offset: -5,
            }}
          />
          <YAxis
            tick={axisStyle}
            axisLine={false}
            tickLine={false}
            label={{
              value: "Actual Success Rate",
              angle: -90,
              position: "insideLeft",
              style: { ...axisStyle, fontSize: 10 },
              offset: 15,
            }}
          />
          <Tooltip {...tooltipStyle} />
          <Line
            type="monotone"
            dataKey="perfect"
            stroke="#334155"
            strokeWidth={1}
            strokeDasharray="5 5"
            dot={false}
            name="Perfect Calibration"
          />
          <Line
            type="monotone"
            dataKey="actual"
            stroke="#3b82f6"
            strokeWidth={2}
            dot={{ fill: "#3b82f6", r: 3 }}
            name="Actual"
            activeDot={{ r: 5, fill: "#60a5fa" }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

/* ─── Migration Volume Area Chart ─────────────────────────────────────────── */
interface VolumeChartProps {
  data: { date: string; generated: number; accepted: number; rejected: number }[];
  className?: string;
}

export function VolumeChart({ data, className }: VolumeChartProps) {
  return (
    <div className={cn("w-full h-[300px]", className)}>
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 10, right: 10, left: -10, bottom: 0 }}>
          <defs>
            <linearGradient id="gradGenerated" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#3b82f6" stopOpacity={0.3} />
              <stop offset="100%" stopColor="#3b82f6" stopOpacity={0} />
            </linearGradient>
            <linearGradient id="gradAccepted" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor="#10b981" stopOpacity={0.3} />
              <stop offset="100%" stopColor="#10b981" stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid {...gridStyle} vertical={false} />
          <XAxis
            dataKey="date"
            tick={axisStyle}
            axisLine={{ stroke: "#1e293b" }}
            tickLine={false}
          />
          <YAxis
            tick={axisStyle}
            axisLine={false}
            tickLine={false}
          />
          <Tooltip {...tooltipStyle} />
          <Legend wrapperStyle={{ fontSize: "11px", color: "#94a3b8" }} />
          <Area
            type="monotone"
            dataKey="generated"
            stroke="#3b82f6"
            strokeWidth={2}
            fill="url(#gradGenerated)"
            name="Generated"
          />
          <Area
            type="monotone"
            dataKey="accepted"
            stroke="#10b981"
            strokeWidth={2}
            fill="url(#gradAccepted)"
            name="Accepted"
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
