"use client";

import { Suspense, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { DiffViewer } from "@/components/DiffViewer";
import { RiskGauge } from "@/components/RiskGauge";
import { Badge } from "@/components/Badge";
import { Button } from "@/components/Button";
import { api, ApiError, type PatchDetail } from "@/lib/api";
import {
  CheckCircle2,
  XCircle,
  FileCode2,
  BookOpen,
  Shield,
  AlertTriangle,
  ChevronLeft,
  Loader2,
  Lock,
} from "lucide-react";

function toUiRisk(tier: string): "low" | "medium" | "high" | "critical" {
  const t = tier.toLowerCase();
  if (t === "cosmetic" || t === "low") return "low";
  if (t === "medium") return "medium";
  if (t === "high") return "high";
  return "critical";
}

function ReviewInner() {
  const searchParams = useSearchParams();
  const router = useRouter();
  const patchId = searchParams.get("patch");

  const [patch, setPatch] = useState<PatchDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [rejectReason, setRejectReason] = useState("");
  const [showRejectForm, setShowRejectForm] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [decision, setDecision] = useState<"accept" | "reject" | null>(null);

  const load = useCallback(async () => {
    if (!patchId) {
      setError("No patch selected. Open a patch from the review queue.");
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      setPatch(await api.reviews.get(patchId));
    } catch (err) {
      setError(
        err instanceof ApiError
          ? `Backend ${err.status}: ${String(err.body ?? err.statusText)}`
          : err instanceof Error
            ? err.message
            : "Failed to load patch"
      );
      setPatch(null);
    } finally {
      setLoading(false);
    }
  }, [patchId]);

  useEffect(() => {
    void load();
  }, [load]);

  const handleAccept = async () => {
    if (!patch) return;
    setSubmitting(true);
    try {
      setPatch(await api.reviews.accept(patch.patchId));
      setDecision("accept");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Accept failed");
    } finally {
      setSubmitting(false);
    }
  };

  const handleReject = async () => {
    if (!patch || !rejectReason.trim()) return;
    setSubmitting(true);
    try {
      setPatch(await api.reviews.reject(patch.patchId, rejectReason.trim()));
      setDecision("reject");
      setShowRejectForm(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Reject failed");
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center justify-center py-24 text-shadow-text-muted gap-2">
        <Loader2 className="h-5 w-5 animate-spin" />
        <span className="text-sm">Loading live patch…</span>
      </div>
    );
  }

  if (error && !patch) {
    return (
      <div className="space-y-4">
        <Link href="/queue" className="inline-flex items-center gap-1 text-sm text-shadow-text-muted hover:text-shadow-text">
          <ChevronLeft className="h-4 w-4" /> Back to queue
        </Link>
        <div className="glass-panel p-6 border border-amber-500/30 bg-amber-500/5 flex gap-3">
          <AlertTriangle className="h-5 w-5 text-amber-400 shrink-0" />
          <div>
            <p className="text-sm font-medium text-shadow-text">Cannot load patch</p>
            <p className="text-xs text-shadow-text-muted mt-1">{error}</p>
          </div>
        </div>
      </div>
    );
  }

  if (!patch) return null;

  const riskTier = toUiRisk(patch.risk.tier);
  const decided = decision !== null || patch.status === "ACCEPTED" || patch.status === "REJECTED";
  const evidence = patch.verificationEvidence;

  return (
    <div className="space-y-6 animate-in max-w-[1600px]">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2 text-xs text-shadow-text-muted">
          <Link href="/queue" className="hover:text-shadow-text">Queue</Link>
          <span>/</span>
          <span className="text-shadow-accent-bright font-mono">{patch.patchId.slice(0, 8)}</span>
        </div>
        <Button variant="ghost" size="sm" onClick={() => router.push("/queue")}>
          <ChevronLeft className="h-3 w-3" />
          Queue
        </Button>
      </div>

      {error && <div className="glass-panel p-3 border border-amber-500/30 text-xs text-amber-200">{error}</div>}

      <div className="glass-panel p-6">
        <div className="flex items-start justify-between gap-4">
          <div className="space-y-2">
            <div className="flex flex-wrap items-center gap-3">
              <FileCode2 className="h-5 w-5 text-shadow-accent" />
              <h1 className="text-lg font-bold text-shadow-text">{patch.filePath.split("/").pop()}</h1>
              <Badge variant="risk" riskTier={riskTier}>{riskTier} risk</Badge>
              <Badge color="blue">{patch.status}</Badge>
            </div>
            <p className="text-xs text-shadow-text-muted font-mono pl-8">{patch.filePath}</p>
            <p className="text-xs text-shadow-accent-bright font-mono pl-8">{patch.ruleName}</p>
          </div>
          <div className="flex items-center gap-3">
            <div className="flex flex-col items-center gap-1 px-4 py-2 rounded-xl bg-shadow-surface/50 border border-shadow-border">
              <span className="text-2xs text-shadow-text-muted uppercase tracking-wider font-semibold">Evidence</span>
              <span className="text-2xl font-bold font-mono text-shadow-accent-bright">
                {((patch.risk.evidenceStrength ?? patch.risk.confidenceScore) * 100).toFixed(0)}%
              </span>
            </div>
            <div className="flex flex-col items-center gap-1 px-4 py-2 rounded-xl bg-shadow-surface/50 border border-shadow-border">
              <span className="text-2xs text-shadow-text-muted uppercase tracking-wider font-semibold">Confidence</span>
              <span className="text-xl font-bold font-mono text-shadow-text-secondary">
                {(patch.risk.confidenceScore * 100).toFixed(0)}%
              </span>
            </div>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-5 gap-6">
        <div className="xl:col-span-3 space-y-4">
          <DiffViewer diff={patch.unifiedDiff || "No diff available"} />
          <div className="glass-panel p-5">
            <div className="flex items-center gap-2 mb-3">
              <BookOpen className="h-4 w-4 text-shadow-accent" />
              <span className="text-sm font-semibold text-shadow-text">Rationale</span>
            </div>
            <p className="text-sm text-shadow-text-secondary leading-relaxed">
              {patch.rationale || "No rationale provided."}
            </p>
          </div>
        </div>

        <div className="xl:col-span-2 space-y-5">
          <div className="glass-panel p-5 flex flex-col items-center">
            <span className="section-header mb-4 self-start">Residual risk</span>
            <RiskGauge value={patch.risk.score} size="md" />
            {Array.isArray(patch.risk.factors) && patch.risk.factors.length > 0 && (
              <ul className="mt-4 w-full space-y-1.5 self-start">
                {patch.risk.factors
                  .filter((f) => typeof f !== "string")
                  .slice(0, 8)
                  .map((f) => {
                    const factor = f as {
                      name: string;
                      description: string;
                      contribution: number;
                    };
                    return (
                      <li key={factor.name} className="text-2xs text-shadow-text-muted flex justify-between gap-2">
                        <span className="font-mono text-shadow-text truncate" title={factor.description}>
                          {factor.name}
                        </span>
                        <span className="font-mono shrink-0">
                          {(factor.contribution * 100).toFixed(0)}%
                        </span>
                      </li>
                    );
                  })}
              </ul>
            )}
          </div>

          <div className="glass-panel p-5 space-y-3">
            <div className="flex items-center gap-2">
              <Shield className="h-4 w-4 text-shadow-accent" />
              <span className="text-sm font-semibold text-shadow-text">Safety Invariants</span>
            </div>
            {(patch.invariants ?? []).length === 0 && (
              <p className="text-xs text-shadow-text-muted">No invariants attached to this patch.</p>
            )}
            {(patch.invariants ?? []).map((inv) => (
              <div key={inv.type} className="flex items-start gap-2 text-xs">
                {inv.preserved ? (
                  <CheckCircle2 className="h-3.5 w-3.5 text-emerald-400 mt-0.5" />
                ) : (
                  <XCircle className="h-3.5 w-3.5 text-red-400 mt-0.5" />
                )}
                <div>
                  <p className="font-mono text-shadow-text">{inv.type}</p>
                  <p className="text-shadow-text-muted">{inv.description}</p>
                </div>
              </div>
            ))}
          </div>

          <div className="glass-panel p-5 space-y-3">
            <div className="flex items-center gap-2">
              <Shield className="h-4 w-4 text-shadow-accent" />
              <span className="text-sm font-semibold text-shadow-text">Verification Evidence</span>
            </div>
            {!evidence && <p className="text-xs text-shadow-text-muted">No verification evidence.</p>}
            {evidence && (
              <>
                <p className="text-xs">
                  Behavioral equivalence:{" "}
                  <span className={evidence.behaviorallyEquivalent ? "text-emerald-400" : "text-amber-400"}>
                    {evidence.behaviorallyEquivalent ? "yes" : "no"}
                  </span>
                </p>
                <ul className="space-y-1">
                  {evidence.invariantsVerified.map((line) => (
                    <li key={line} className="text-xs text-emerald-300/90 flex gap-2">
                      <CheckCircle2 className="h-3.5 w-3.5 mt-0.5 shrink-0" />
                      {line}
                    </li>
                  ))}
                  {evidence.invariantsViolated.map((line) => (
                    <li key={line} className="text-xs text-red-300/90 flex gap-2">
                      <XCircle className="h-3.5 w-3.5 mt-0.5 shrink-0" />
                      {line}
                    </li>
                  ))}
                </ul>
                <div className="text-2xs text-shadow-text-muted font-mono break-all">
                  layers: {JSON.stringify(evidence.proofArtifacts)}
                </div>
                {evidence.proofArtifacts && (
                  <div className="mt-2 space-y-1">
                    {"translateGaps" in evidence.proofArtifacts &&
                      String(evidence.proofArtifacts.translateGaps || "").length > 0 && (
                        <p className="text-2xs text-amber-300/90">
                          Translate gaps: {String(evidence.proofArtifacts.translateGaps)}
                        </p>
                      )}
                    {"resolvedCalls" in evidence.proofArtifacts &&
                      String(evidence.proofArtifacts.resolvedCalls || "").length > 0 && (
                        <p className="text-2xs text-emerald-300/90">
                          Resolved CALLs: {String(evidence.proofArtifacts.resolvedCalls)}
                        </p>
                      )}
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </div>

      <div className="glass-panel p-6 sticky bottom-4 z-30 bg-shadow-surface/95 backdrop-blur-xl shadow-xl">
        {decided ? (
          <div className="flex items-center justify-center gap-3 py-2">
            {(decision === "accept" || patch.status === "ACCEPTED") ? (
              <>
                <CheckCircle2 className="h-5 w-5 text-emerald-400" />
                <span className="text-sm font-semibold text-emerald-400">Patch accepted</span>
              </>
            ) : (
              <>
                <XCircle className="h-5 w-5 text-red-400" />
                <span className="text-sm font-semibold text-red-400">
                  Patch rejected{patch.review?.reason ? ` — ${patch.review.reason}` : ""}
                </span>
              </>
            )}
          </div>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center justify-center gap-2 text-xs text-shadow-text-muted">
              <Lock className="h-3 w-3" />
              <span>Live review — accept/reject hits the real API.</span>
            </div>
            <div className="flex items-center justify-end gap-3 flex-wrap">
              {showRejectForm ? (
                <>
                  <input
                    value={rejectReason}
                    onChange={(e) => setRejectReason(e.target.value)}
                    placeholder="Reason for rejection (required)"
                    className="px-3 py-2 w-72 rounded-lg bg-shadow-bg border border-shadow-border text-xs text-shadow-text"
                  />
                  <Button
                    variant="reject"
                    size="md"
                    onClick={() => void handleReject()}
                    loading={submitting}
                    disabled={!rejectReason.trim()}
                    icon={<XCircle className="h-4 w-4" />}
                  >
                    Confirm Reject
                  </Button>
                  <Button variant="ghost" size="sm" onClick={() => setShowRejectForm(false)}>
                    Cancel
                  </Button>
                </>
              ) : (
                <>
                  <Button variant="reject" size="md" onClick={() => setShowRejectForm(true)} icon={<XCircle className="h-4 w-4" />}>
                    Reject
                  </Button>
                  <Button variant="accept" size="lg" onClick={() => void handleAccept()} loading={submitting} icon={<CheckCircle2 className="h-4 w-4" />}>
                    Accept Patch
                  </Button>
                </>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

export default function ReviewPage() {
  return (
    <Suspense
      fallback={
        <div className="flex items-center justify-center py-24 text-shadow-text-muted gap-2">
          <Loader2 className="h-5 w-5 animate-spin" />
          <span className="text-sm">Loading…</span>
        </div>
      }
    >
      <ReviewInner />
    </Suspense>
  );
}
