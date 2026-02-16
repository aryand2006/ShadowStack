"use client";

import { useState } from "react";
import { DiffViewer } from "@/components/DiffViewer";
import { SafetyChecklist } from "@/components/SafetyChecklist";
import { RiskGauge } from "@/components/RiskGauge";
import { VerificationEvidence } from "@/components/VerificationEvidence";
import { Badge } from "@/components/Badge";
import { Button } from "@/components/Button";
import { cn } from "@/lib/utils";
import type { PatchDetail, SafetyInvariant, VerificationResult } from "@/types";
import {
  CheckCircle2,
  XCircle,
  FileCode2,
  BookOpen,
  Shield,
  AlertTriangle,
  ChevronLeft,
  ChevronRight,
  MessageSquare,
  Sparkles,
  Lock,
  Eye,
} from "lucide-react";

/* ─── Mock Patch Detail ──────────────────────────────────────────────────────
 *  In production, this comes from GET /api/v1/patches/:id
 *  This is a realistic example of a Java stream migration patch with
 *  full verification evidence.
 * ──────────────────────────────────────────────────────────────────────────── */

const mockDiff = `@@ -45,12 +45,8 @@ public class PaymentProcessor {
 
     public List<Transaction> filterValidTransactions(List<Transaction> transactions) {
-        List<Transaction> result = new ArrayList<>();
-        for (Transaction tx : transactions) {
-            if (tx.getAmount() > 0 && tx.getStatus() == Status.VALID) {
-                result.add(tx);
-            }
-        }
-        return result;
+        return transactions.stream()
+                .filter(tx -> tx.getAmount() > 0 && tx.getStatus() == Status.VALID)
+                .collect(Collectors.toList());
     }
 
@@ -62,15 +58,10 @@ public class PaymentProcessor {
 
     public Map<Currency, Double> aggregateByCurrency(List<Transaction> transactions) {
-        Map<Currency, Double> totals = new HashMap<>();
-        for (Transaction tx : transactions) {
-            Currency currency = tx.getCurrency();
-            double current = totals.getOrDefault(currency, 0.0);
-            totals.put(currency, current + tx.getAmount());
-        }
-        return totals;
+        return transactions.stream()
+                .collect(Collectors.groupingBy(
+                        Transaction::getCurrency,
+                        Collectors.summingDouble(Transaction::getAmount)));
     }`;

const mockVerification: VerificationResult = {
  overall: "passed",
  compile: {
    status: "pass",
    message: "Compilation successful. No errors or warnings.",
    duration_ms: 2340,
  },
  tests: {
    status: "pass",
    total: 47,
    passed: 47,
    failed: 0,
    skipped: 0,
    details: [
      "PaymentProcessorTest.testFilterValidTransactions — PASS (12ms)",
      "PaymentProcessorTest.testFilterEmptyList — PASS (2ms)",
      "PaymentProcessorTest.testAggregateByCurrency — PASS (8ms)",
      "PaymentProcessorTest.testAggregateMultipleCurrencies — PASS (15ms)",
      "IntegrationTest.testEndToEndPaymentFlow — PASS (234ms)",
    ],
    duration_ms: 4820,
  },
  astComparison: {
    status: "pass",
    similarityScore: 0.92,
    structuralChanges: [
      "ForStatement → MethodInvocation(stream)",
      "IfStatement → MethodInvocation(filter)",
      "ArrayList initialization removed — Collectors.toList() used",
      "HashMap loop → Collectors.groupingBy",
    ],
  },
  bytecodeVerification: {
    status: "pass",
    descriptorMatch: true,
    signatureCompatible: true,
    details:
      "Method descriptors match: filterValidTransactions(Ljava/util/List;)Ljava/util/List; — Return type and parameters identical.",
  },
  apiSurface: {
    status: "pass",
    compatible: true,
    breakingChanges: [],
    addedEndpoints: [],
    removedEndpoints: [],
  },
  goldenMaster: {
    status: "pass",
    match: true,
    diffCount: 0,
    details:
      "Golden master output comparison: 1,200 test vectors validated. Zero divergence detected across all scenarios.",
  },
};

const mockInvariants: SafetyInvariant[] = [
  {
    id: "si-001",
    name: "Return Type Preserved",
    description: "The method return type remains List<Transaction> — no change in contract.",
    status: "passed",
    category: "structural",
    evidence: "AST analysis confirms return type: java.util.List<Transaction>",
  },
  {
    id: "si-002",
    name: "Null Behavior Equivalent",
    description: "Null handling is preserved. NullPointerException thrown in both old and new code for null input.",
    status: "passed",
    category: "behavioral",
    evidence: "Tested with null input — both versions throw NPE at same call site",
  },
  {
    id: "si-003",
    name: "Order Preservation",
    description: "Element ordering in result list is maintained — stream preserves encounter order for sequential streams.",
    status: "passed",
    category: "behavioral",
    evidence: "Sequential stream guarantees encounter order per JLS §17.4.5",
  },
  {
    id: "si-004",
    name: "Side-Effect Free",
    description: "The filter predicate contains no side effects. Stream operations are safe for parallelization.",
    status: "passed",
    category: "behavioral",
  },
  {
    id: "si-005",
    name: "Performance Bounded",
    description: "Execution time remains within 2x of original for inputs up to 100k elements.",
    status: "warning",
    category: "performance",
    evidence: "Benchmark: 1.3x overhead for 100k elements. Acceptable but flagged for monitoring.",
  },
  {
    id: "si-006",
    name: "Thread Safety",
    description: "No shared mutable state introduced. Method remains thread-safe.",
    status: "passed",
    category: "security",
  },
  {
    id: "si-007",
    name: "Exception Transparency",
    description: "All exceptions from the original code path are preserved in the modernized version.",
    status: "passed",
    category: "behavioral",
    evidence: "Exception paths verified: IllegalArgumentException, NullPointerException",
  },
];

const mockPatch: {
  id: string;
  candidateId: string;
  projectId: string;
  filePath: string;
  rule: string;
  ruleDescription: string;
  riskTier: "low" | "medium" | "high" | "critical";
  confidence: number;
  status: "pending" | "reviewing" | "accepted" | "rejected" | "deferred";
  createdAt: string;
  rationale: string;
} = {
  id: "p-1001",
  candidateId: "c-2001",
  projectId: "proj-001",
  filePath: "src/main/java/com/acme/payment/PaymentProcessor.java",
  rule: "java-stream-migration",
  ruleDescription: "Convert imperative for-loops with filtering/mapping to Java Stream API equivalents",
  riskTier: "high",
  confidence: 0.87,
  status: "reviewing",
  createdAt: "2026-02-16T08:12:00Z",
  rationale:
    "This patch modernizes two methods in PaymentProcessor that use traditional for-loops with conditional accumulation. The Stream API equivalents are more declarative, less error-prone, and express intent more clearly. The filter+collect pattern directly replaces the if-add loop, while groupingBy+summingDouble replaces the manual map accumulation. Both transformations preserve the original method contracts, return types, and exception behavior. The confidence score of 87% reflects the high structural similarity and full test passage, with a minor deduction for the performance overhead of stream creation on small collections.",
};

export default function ReviewPage() {
  const [decision, setDecision] = useState<"accept" | "reject" | null>(null);
  const [rejectReason, setRejectReason] = useState("");
  const [showRejectForm, setShowRejectForm] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitted, setSubmitted] = useState(false);

  const handleAccept = async () => {
    setIsSubmitting(true);
    // In production: await api.queue.submit({ patchId: mockPatch.id, decision: "accept", ... })
    await new Promise((r) => setTimeout(r, 800));
    setDecision("accept");
    setIsSubmitting(false);
    setSubmitted(true);
  };

  const handleReject = async () => {
    if (!rejectReason.trim()) return;
    setIsSubmitting(true);
    // In production: await api.queue.submit({ patchId: mockPatch.id, decision: "reject", reason: rejectReason, ... })
    await new Promise((r) => setTimeout(r, 800));
    setDecision("reject");
    setIsSubmitting(false);
    setSubmitted(true);
    setShowRejectForm(false);
  };

  return (
    <div className="space-y-6 animate-in max-w-[1600px]">
      {/* Breadcrumb + Navigation */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2 text-xs text-shadow-text-muted">
          <a href="/queue" className="hover:text-shadow-text transition-colors">
            Queue
          </a>
          <ChevronRight className="h-3 w-3" />
          <span className="text-shadow-text">Patch Review</span>
          <ChevronRight className="h-3 w-3" />
          <span className="text-shadow-accent-bright font-mono">{mockPatch.id}</span>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" icon={<ChevronLeft className="h-3 w-3" />}>
            Previous
          </Button>
          <Button variant="ghost" size="sm">
            Next
            <ChevronRight className="h-3 w-3" />
          </Button>
        </div>
      </div>

      {/* Patch Header */}
      <div className="glass-panel p-6">
        <div className="flex items-start justify-between">
          <div className="space-y-2">
            <div className="flex items-center gap-3">
              <FileCode2 className="h-5 w-5 text-shadow-accent" />
              <h1 className="text-lg font-bold text-shadow-text">
                {mockPatch.filePath.split("/").pop()}
              </h1>
              <Badge variant="risk" riskTier={mockPatch.riskTier}>
                {mockPatch.riskTier} risk
              </Badge>
              <Badge color="blue" pulse>
                {mockPatch.status}
              </Badge>
            </div>
            <p className="text-xs text-shadow-text-muted font-mono pl-8">
              {mockPatch.filePath}
            </p>
            <div className="flex items-center gap-4 pl-8 mt-1">
              <div className="flex items-center gap-1.5 text-xs text-shadow-text-secondary">
                <Sparkles className="h-3 w-3 text-shadow-accent" />
                <span className="font-mono text-shadow-accent-bright">{mockPatch.rule}</span>
              </div>
              <span className="text-2xs text-shadow-text-muted">
                {mockPatch.ruleDescription}
              </span>
            </div>
          </div>

          {/* Confidence badge large */}
          <div className="flex flex-col items-center gap-1 px-4 py-2 rounded-xl bg-shadow-surface/50 border border-shadow-border">
            <span className="text-2xs text-shadow-text-muted uppercase tracking-wider font-semibold">
              Confidence
            </span>
            <span
              className={cn(
                "text-2xl font-bold font-mono",
                mockPatch.confidence >= 0.9
                  ? "text-emerald-400"
                  : mockPatch.confidence >= 0.8
                  ? "text-shadow-accent-bright"
                  : mockPatch.confidence >= 0.7
                  ? "text-amber-400"
                  : "text-red-400"
              )}
            >
              {(mockPatch.confidence * 100).toFixed(0)}%
            </span>
          </div>
        </div>
      </div>

      {/* Main Review Area: Diff (left) + Evidence (right) */}
      <div className="grid grid-cols-1 xl:grid-cols-5 gap-6">
        {/* Left Panel: Diff Viewer (3 columns) */}
        <div className="xl:col-span-3 space-y-4">
          <div className="flex items-center gap-2 mb-1">
            <Eye className="h-4 w-4 text-shadow-text-muted" />
            <span className="section-header">Code Changes</span>
          </div>
          <DiffViewer diff={mockDiff} />

          {/* Rationale Card */}
          <div className="glass-panel p-5">
            <div className="flex items-center gap-2 mb-3">
              <BookOpen className="h-4 w-4 text-shadow-accent" />
              <span className="text-sm font-semibold text-shadow-text">
                Rationale
              </span>
            </div>
            <p className="text-sm text-shadow-text-secondary leading-relaxed">
              {mockPatch.rationale}
            </p>
          </div>
        </div>

        {/* Right Panel: Evidence (2 columns) */}
        <div className="xl:col-span-2 space-y-5">
          {/* Risk Gauge */}
          <div className="glass-panel p-5 flex flex-col items-center">
            <span className="section-header mb-4 self-start">Risk Assessment</span>
            <RiskGauge value={mockPatch.riskTier === "critical" ? 0.92 : mockPatch.riskTier === "high" ? 0.68 : mockPatch.riskTier === "medium" ? 0.42 : 0.15} size="md" />
          </div>

          {/* Safety Invariants */}
          <div className="glass-panel p-5">
            <SafetyChecklist invariants={mockInvariants} />
          </div>

          {/* Verification Evidence Accordion */}
          <div className="glass-panel p-5">
            <VerificationEvidence verification={mockVerification} />
          </div>
        </div>
      </div>

      {/* Decision Footer */}
      <div
        className={cn(
          "glass-panel p-6 sticky bottom-4 z-30",
          "bg-shadow-surface/95 backdrop-blur-xl",
          "border-shadow-border-bright shadow-xl"
        )}
      >
        {submitted ? (
          <div className="flex items-center justify-center gap-3 py-2">
            {decision === "accept" ? (
              <>
                <CheckCircle2 className="h-5 w-5 text-emerald-400" />
                <span className="text-sm font-semibold text-emerald-400">
                  Patch Accepted — Changes will be applied to the codebase
                </span>
              </>
            ) : (
              <>
                <XCircle className="h-5 w-5 text-red-400" />
                <span className="text-sm font-semibold text-red-400">
                  Patch Rejected — Reason recorded for model improvement
                </span>
              </>
            )}
          </div>
        ) : (
          <div className="space-y-4">
            {/* Trust message */}
            <div className="flex items-center justify-center gap-2 text-xs text-shadow-text-muted">
              <Lock className="h-3 w-3" />
              <span>
                This system is cautious, explainable, and under your control.
                Every change requires your explicit approval.
              </span>
            </div>

            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-shadow-surface/50 border border-shadow-border">
                  <Shield className="h-3.5 w-3.5 text-shadow-accent" />
                  <span className="text-xs text-shadow-text-secondary">
                    Verification:{" "}
                    <span className="text-emerald-400 font-semibold">
                      6/6 layers passed
                    </span>
                  </span>
                </div>
                <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-shadow-surface/50 border border-shadow-border">
                  <AlertTriangle className="h-3.5 w-3.5 text-amber-400" />
                  <span className="text-xs text-shadow-text-secondary">
                    1 warning: Performance monitoring recommended
                  </span>
                </div>
              </div>

              <div className="flex items-center gap-3">
                {showRejectForm ? (
                  <div className="flex items-center gap-2 animate-fade-in">
                    <div className="relative">
                      <MessageSquare className="absolute left-3 top-3 h-3.5 w-3.5 text-shadow-text-muted" />
                      <textarea
                        value={rejectReason}
                        onChange={(e) => setRejectReason(e.target.value)}
                        placeholder="Reason for rejection (required)..."
                        className="pl-9 pr-4 py-2 w-80 h-10 rounded-lg bg-shadow-bg border border-shadow-border text-xs text-shadow-text placeholder:text-shadow-text-muted focus:outline-none focus:border-red-500/50 focus:ring-1 focus:ring-red-500/30 resize-none"
                        rows={1}
                      />
                    </div>
                    <Button
                      variant="reject"
                      size="md"
                      onClick={handleReject}
                      loading={isSubmitting}
                      disabled={!rejectReason.trim()}
                      icon={<XCircle className="h-4 w-4" />}
                    >
                      Confirm Reject
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => {
                        setShowRejectForm(false);
                        setRejectReason("");
                      }}
                    >
                      Cancel
                    </Button>
                  </div>
                ) : (
                  <>
                    <Button
                      variant="reject"
                      size="md"
                      onClick={() => setShowRejectForm(true)}
                      icon={<XCircle className="h-4 w-4" />}
                    >
                      Reject
                    </Button>
                    <Button
                      variant="accept"
                      size="lg"
                      onClick={handleAccept}
                      loading={isSubmitting}
                      icon={<CheckCircle2 className="h-4 w-4" />}
                    >
                      Accept Patch
                    </Button>
                  </>
                )}
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
