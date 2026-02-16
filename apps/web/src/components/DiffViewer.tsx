"use client";

import { cn } from "@/lib/utils";

interface DiffLine {
  type: "added" | "removed" | "context" | "header";
  content: string;
  oldLineNumber?: number;
  newLineNumber?: number;
}

interface DiffViewerProps {
  diff: string;
  className?: string;
}

/** Parse a unified diff string into structured line objects */
function parseDiff(diff: string): DiffLine[] {
  const lines = diff.split("\n");
  const result: DiffLine[] = [];
  let oldLine = 0;
  let newLine = 0;

  for (const line of lines) {
    if (line.startsWith("@@")) {
      const match = line.match(/@@ -(\d+),?\d* \+(\d+),?\d* @@/);
      if (match) {
        oldLine = parseInt(match[1], 10);
        newLine = parseInt(match[2], 10);
      }
      result.push({ type: "header", content: line });
    } else if (line.startsWith("+")) {
      result.push({
        type: "added",
        content: line.slice(1),
        newLineNumber: newLine++,
      });
    } else if (line.startsWith("-")) {
      result.push({
        type: "removed",
        content: line.slice(1),
        oldLineNumber: oldLine++,
      });
    } else if (line.startsWith("\\")) {
      // "No newline at end of file" markers — skip
    } else {
      result.push({
        type: "context",
        content: line.startsWith(" ") ? line.slice(1) : line,
        oldLineNumber: oldLine++,
        newLineNumber: newLine++,
      });
    }
  }

  return result;
}

export function DiffViewer({ diff, className }: DiffViewerProps) {
  const lines = parseDiff(diff);

  return (
    <div
      className={cn(
        "rounded-xl border border-shadow-border overflow-hidden bg-shadow-bg-alt font-mono text-xs",
        className
      )}
    >
      {/* Diff header */}
      <div className="px-4 py-2.5 bg-shadow-surface/50 border-b border-shadow-border flex items-center gap-2">
        <div className="flex gap-1.5">
          <div className="w-3 h-3 rounded-full bg-red-500/60" />
          <div className="w-3 h-3 rounded-full bg-amber-500/60" />
          <div className="w-3 h-3 rounded-full bg-emerald-500/60" />
        </div>
        <span className="text-shadow-text-muted text-2xs ml-2">Unified Diff</span>
      </div>

      {/* Diff content */}
      <div className="overflow-x-auto">
        <table className="w-full border-collapse">
          <tbody>
            {lines.map((line, i) => (
              <tr
                key={i}
                className={cn(
                  "group border-b border-shadow-border/30 last:border-0",
                  line.type === "added" && "bg-emerald-500/[0.07]",
                  line.type === "removed" && "bg-red-500/[0.07]",
                  line.type === "header" && "bg-shadow-accent/[0.06]"
                )}
              >
                {/* Old line number */}
                <td className="w-12 text-right pr-2 pl-3 py-0 select-none text-shadow-text-muted/50 text-2xs">
                  {line.type === "removed" || line.type === "context"
                    ? line.oldLineNumber
                    : ""}
                </td>
                {/* New line number */}
                <td className="w-12 text-right pr-3 py-0 select-none text-shadow-text-muted/50 text-2xs border-r border-shadow-border/20">
                  {line.type === "added" || line.type === "context"
                    ? line.newLineNumber
                    : ""}
                </td>
                {/* Marker */}
                <td className="w-5 text-center py-0 select-none">
                  {line.type === "added" && (
                    <span className="text-emerald-400 font-bold">+</span>
                  )}
                  {line.type === "removed" && (
                    <span className="text-red-400 font-bold">−</span>
                  )}
                </td>
                {/* Content */}
                <td className="py-0.5 pr-4">
                  <pre
                    className={cn(
                      "whitespace-pre",
                      line.type === "added" && "text-emerald-300",
                      line.type === "removed" && "text-red-300",
                      line.type === "context" && "text-shadow-text-secondary",
                      line.type === "header" && "text-shadow-accent-bright font-semibold"
                    )}
                  >
                    {line.content}
                  </pre>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
