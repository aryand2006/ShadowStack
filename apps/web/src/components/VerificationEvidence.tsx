"use client";

import { useState } from "react";
import { cn } from "@/lib/utils";
import type { VerificationResult } from "@/types";
import {
  CheckCircle2,
  XCircle,
  MinusCircle,
  ChevronDown,
  ChevronRight,
  Code2,
  TestTube,
  GitCompare,
  Binary,
  Globe,
  FileCheck,
} from "lucide-react";

interface VerificationEvidenceProps {
  verification: VerificationResult;
  className?: string;
}

interface EvidenceSection {
  key: string;
  label: string;
  icon: React.ReactNode;
  status: "pass" | "fail" | "skip";
  summary: string;
  details: React.ReactNode;
}

const statusConfig = {
  pass: {
    icon: CheckCircle2,
    color: "text-emerald-400",
    bg: "bg-emerald-500/10",
    border: "border-emerald-500/20",
    label: "PASS",
  },
  fail: {
    icon: XCircle,
    color: "text-red-400",
    bg: "bg-red-500/10",
    border: "border-red-500/20",
    label: "FAIL",
  },
  skip: {
    icon: MinusCircle,
    color: "text-shadow-text-muted",
    bg: "bg-shadow-surface/50",
    border: "border-shadow-border",
    label: "SKIP",
  },
};

export function VerificationEvidence({
  verification,
  className,
}: VerificationEvidenceProps) {
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const toggle = (key: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const sections: EvidenceSection[] = [
    {
      key: "compile",
      label: "Compilation",
      icon: <Code2 className="h-3.5 w-3.5" />,
      status: verification.compile.status,
      summary: verification.compile.message,
      details: (
        <div className="text-xs text-shadow-text-secondary space-y-1">
          <p>{verification.compile.message}</p>
          <p className="text-shadow-text-muted">
            Duration: {verification.compile.duration_ms}ms
          </p>
        </div>
      ),
    },
    {
      key: "tests",
      label: "Test Suite",
      icon: <TestTube className="h-3.5 w-3.5" />,
      status: verification.tests.status,
      summary: `${verification.tests.passed}/${verification.tests.total} passed`,
      details: (
        <div className="text-xs space-y-2">
          <div className="flex gap-4 text-shadow-text-secondary">
            <span>
              Total: <span className="text-shadow-text font-medium">{verification.tests.total}</span>
            </span>
            <span>
              Passed: <span className="text-emerald-400 font-medium">{verification.tests.passed}</span>
            </span>
            <span>
              Failed: <span className="text-red-400 font-medium">{verification.tests.failed}</span>
            </span>
            <span>
              Skipped: <span className="text-shadow-text-muted font-medium">{verification.tests.skipped}</span>
            </span>
          </div>
          {verification.tests.details.length > 0 && (
            <div className="space-y-1 mt-2">
              {verification.tests.details.map((detail, i) => (
                <p key={i} className="text-shadow-text-muted font-mono text-2xs">
                  {detail}
                </p>
              ))}
            </div>
          )}
          <p className="text-shadow-text-muted">
            Duration: {verification.tests.duration_ms}ms
          </p>
        </div>
      ),
    },
    {
      key: "ast",
      label: "AST Comparison",
      icon: <GitCompare className="h-3.5 w-3.5" />,
      status: verification.astComparison.status,
      summary: `Similarity: ${(verification.astComparison.similarityScore * 100).toFixed(1)}%`,
      details: (
        <div className="text-xs space-y-2">
          <div className="flex items-center gap-2">
            <span className="text-shadow-text-secondary">Structural Similarity:</span>
            <div className="flex-1 h-1.5 rounded-full bg-shadow-border overflow-hidden">
              <div
                className="h-full rounded-full bg-shadow-accent transition-all"
                style={{ width: `${verification.astComparison.similarityScore * 100}%` }}
              />
            </div>
            <span className="text-shadow-text font-medium font-mono text-2xs">
              {(verification.astComparison.similarityScore * 100).toFixed(1)}%
            </span>
          </div>
          {verification.astComparison.structuralChanges.length > 0 && (
            <div className="space-y-1">
              <span className="text-shadow-text-muted text-2xs">Changes:</span>
              {verification.astComparison.structuralChanges.map((change, i) => (
                <p key={i} className="text-shadow-text-secondary font-mono text-2xs pl-2 border-l border-shadow-border">
                  {change}
                </p>
              ))}
            </div>
          )}
        </div>
      ),
    },
    {
      key: "bytecode",
      label: "Bytecode Verification",
      icon: <Binary className="h-3.5 w-3.5" />,
      status: verification.bytecodeVerification.status,
      summary: verification.bytecodeVerification.descriptorMatch
        ? "Descriptors match"
        : "Descriptor mismatch",
      details: (
        <div className="text-xs space-y-1.5 text-shadow-text-secondary">
          <div className="flex items-center gap-3">
            <span>
              Descriptor Match:{" "}
              <span className={verification.bytecodeVerification.descriptorMatch ? "text-emerald-400" : "text-red-400"}>
                {verification.bytecodeVerification.descriptorMatch ? "Yes" : "No"}
              </span>
            </span>
            <span>
              Signature Compatible:{" "}
              <span className={verification.bytecodeVerification.signatureCompatible ? "text-emerald-400" : "text-red-400"}>
                {verification.bytecodeVerification.signatureCompatible ? "Yes" : "No"}
              </span>
            </span>
          </div>
          <p className="text-shadow-text-muted font-mono text-2xs">
            {verification.bytecodeVerification.details}
          </p>
        </div>
      ),
    },
    {
      key: "api",
      label: "API Surface",
      icon: <Globe className="h-3.5 w-3.5" />,
      status: verification.apiSurface.status,
      summary: verification.apiSurface.compatible
        ? "Compatible"
        : `${verification.apiSurface.breakingChanges.length} breaking changes`,
      details: (
        <div className="text-xs space-y-2">
          <div className="flex items-center gap-2">
            <span className="text-shadow-text-secondary">
              Compatible:{" "}
              <span className={verification.apiSurface.compatible ? "text-emerald-400" : "text-red-400"}>
                {verification.apiSurface.compatible ? "Yes" : "No"}
              </span>
            </span>
          </div>
          {verification.apiSurface.breakingChanges.length > 0 && (
            <div>
              <span className="text-red-400/80 text-2xs">Breaking Changes:</span>
              {verification.apiSurface.breakingChanges.map((bc, i) => (
                <p key={i} className="text-red-300 font-mono text-2xs pl-2 mt-0.5">
                  • {bc}
                </p>
              ))}
            </div>
          )}
        </div>
      ),
    },
    {
      key: "golden",
      label: "Golden Master",
      icon: <FileCheck className="h-3.5 w-3.5" />,
      status: verification.goldenMaster.status,
      summary: verification.goldenMaster.match
        ? "Output matches"
        : `${verification.goldenMaster.diffCount} differences`,
      details: (
        <div className="text-xs space-y-1 text-shadow-text-secondary">
          <div className="flex items-center gap-3">
            <span>
              Match:{" "}
              <span className={verification.goldenMaster.match ? "text-emerald-400" : "text-red-400"}>
                {verification.goldenMaster.match ? "Yes" : "No"}
              </span>
            </span>
            <span>
              Diff Count:{" "}
              <span className="text-shadow-text font-medium">{verification.goldenMaster.diffCount}</span>
            </span>
          </div>
          <p className="text-shadow-text-muted font-mono text-2xs">
            {verification.goldenMaster.details}
          </p>
        </div>
      ),
    },
  ];

  const passCount = sections.filter((s) => s.status === "pass").length;

  return (
    <div className={cn("space-y-2", className)}>
      {/* Header with overall status */}
      <div className="flex items-center justify-between mb-3">
        <span className="text-sm font-semibold text-shadow-text">
          Verification Evidence
        </span>
        <span
          className={cn(
            "text-xs font-medium px-2 py-0.5 rounded-full",
            passCount === sections.length
              ? "bg-emerald-500/15 text-emerald-400"
              : passCount >= sections.length - 1
              ? "bg-amber-500/15 text-amber-400"
              : "bg-red-500/15 text-red-400"
          )}
        >
          {passCount}/{sections.length} layers verified
        </span>
      </div>

      {/* Accordion sections */}
      {sections.map((section) => {
        const config = statusConfig[section.status];
        const StatusIcon = config.icon;
        const isOpen = expanded.has(section.key);

        return (
          <div
            key={section.key}
            className={cn(
              "rounded-lg border transition-colors",
              config.border,
              isOpen ? config.bg : "bg-transparent hover:bg-shadow-surface/30"
            )}
          >
            {/* Header row */}
            <button
              onClick={() => toggle(section.key)}
              className="w-full flex items-center gap-3 px-3 py-2.5 text-left"
            >
              <StatusIcon className={cn("h-4 w-4 flex-shrink-0", config.color)} />
              <div className="flex-1 min-w-0 flex items-center gap-2">
                <span className="text-shadow-text-muted">{section.icon}</span>
                <span className="text-xs font-medium text-shadow-text">
                  {section.label}
                </span>
              </div>
              <span className="text-2xs text-shadow-text-muted truncate max-w-[140px]">
                {section.summary}
              </span>
              <span
                className={cn(
                  "text-2xs font-bold tracking-wider px-1.5 py-0.5 rounded",
                  config.color,
                  config.bg
                )}
              >
                {config.label}
              </span>
              {isOpen ? (
                <ChevronDown className="h-3.5 w-3.5 text-shadow-text-muted" />
              ) : (
                <ChevronRight className="h-3.5 w-3.5 text-shadow-text-muted" />
              )}
            </button>

            {/* Expanded details */}
            {isOpen && (
              <div className="px-4 pb-3 pt-0 border-t border-shadow-border/30 ml-7">
                <div className="pt-2.5">{section.details}</div>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
