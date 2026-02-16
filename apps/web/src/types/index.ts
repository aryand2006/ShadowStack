/* ─── ShadowStack Type Definitions ────────────────────────────────────────────
 *  Core domain types for the verified language modernization engine.
 *  These mirror the backend API response shapes.
 * ──────────────────────────────────────────────────────────────────────────── */

/** A modernization project targeting a specific codebase */
export interface Project {
  id: string;
  name: string;
  description: string;
  sourceLanguage: string;
  targetLanguage: string;
  repositoryUrl: string;
  createdAt: string;
  updatedAt: string;
  status: "active" | "paused" | "completed" | "archived";
  stats: {
    totalModules: number;
    analyzedModules: number;
    patchesGenerated: number;
    patchesAccepted: number;
    patchesRejected: number;
    averageConfidence: number;
  };
}

/** A snapshot of the pre-modernization codebase state used for comparison */
export interface BaselineSnapshot {
  id: string;
  projectId: string;
  capturedAt: string;
  commitHash: string;
  moduleCount: number;
  testSuiteHash: string;
  goldenMasterHash: string;
}

/** A detected candidate for modernization within the codebase */
export interface RefactorCandidate {
  id: string;
  projectId: string;
  filePath: string;
  moduleName: string;
  rule: string;
  ruleDescription: string;
  riskTier: "low" | "medium" | "high" | "critical";
  confidence: number;
  estimatedComplexity: "trivial" | "simple" | "moderate" | "complex";
  lineRange: { start: number; end: number };
  detectedAt: string;
}

/** The atomic unit of a code change — a single patch */
export interface PatchUnit {
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
  reviewedAt?: string;
  reviewedBy?: string;
}

/** Full detail view of a patch, including diff and verification data */
export interface PatchDetail extends PatchUnit {
  unifiedDiff: string;
  rationale: string;
  originalCode: string;
  modernizedCode: string;
  verification: VerificationResult;
  safetyInvariants: SafetyInvariant[];
}

/** Aggregated verification evidence for a patch */
export interface VerificationResult {
  overall: "passed" | "failed" | "partial" | "pending";
  compile: {
    status: "pass" | "fail" | "skip";
    message: string;
    duration_ms: number;
  };
  tests: {
    status: "pass" | "fail" | "skip";
    total: number;
    passed: number;
    failed: number;
    skipped: number;
    details: string[];
    duration_ms: number;
  };
  astComparison: {
    status: "pass" | "fail" | "skip";
    similarityScore: number;
    structuralChanges: string[];
  };
  bytecodeVerification: {
    status: "pass" | "fail" | "skip";
    descriptorMatch: boolean;
    signatureCompatible: boolean;
    details: string;
  };
  apiSurface: {
    status: "pass" | "fail" | "skip";
    compatible: boolean;
    breakingChanges: string[];
    addedEndpoints: string[];
    removedEndpoints: string[];
  };
  goldenMaster: {
    status: "pass" | "fail" | "skip";
    match: boolean;
    diffCount: number;
    details: string;
  };
}

/** An item in the human review queue */
export interface ReviewQueueItem {
  id: string;
  patchId: string;
  projectName: string;
  filePath: string;
  rule: string;
  riskTier: "low" | "medium" | "high" | "critical";
  confidence: number;
  status: "pending" | "reviewing" | "accepted" | "rejected" | "deferred";
  assignedTo?: string;
  priority: number;
  createdAt: string;
  estimatedReviewTime: string;
}

/** The human reviewer's decision on a patch */
export interface ReviewDecision {
  patchId: string;
  decision: "accept" | "reject" | "defer";
  reviewer: string;
  reason?: string;
  notes?: string;
  timestamp: string;
}

/** Top-level analytics dashboard data */
export interface AnalyticsDashboard {
  summary: {
    totalProjects: number;
    totalPatches: number;
    acceptanceRate: number;
    averageConfidence: number;
    totalVerified: number;
    pendingReview: number;
  };
  acceptanceByRule: { rule: string; accepted: number; rejected: number; total: number }[];
  riskDistribution: RiskDistribution;
  volumeOverTime: { date: string; generated: number; accepted: number; rejected: number }[];
  confidenceCalibration: { predicted: number; actual: number }[];
  topFailurePatterns: { pattern: string; count: number; lastSeen: string; severity: string }[];
}

/** Corpus-level analytics about the analyzed codebase */
export interface CorpusAnalytics {
  totalFiles: number;
  totalLines: number;
  languageBreakdown: { language: string; files: number; lines: number; percentage: number }[];
  moduleComplexity: { module: string; complexity: number; risk: string }[];
  coveragePercentage: number;
  technicalDebtScore: number;
}

/** Distribution of risk across the codebase */
export interface RiskDistribution {
  low: number;
  medium: number;
  high: number;
  critical: number;
}

/** A behavioral safety invariant that must hold after modernization */
export interface SafetyInvariant {
  id: string;
  name: string;
  description: string;
  status: "passed" | "failed" | "warning" | "unchecked";
  category: "behavioral" | "structural" | "performance" | "security";
  evidence?: string;
}

/** A certificate proving behavioral equivalence between original and modernized code */
export interface BehavioralEquivalenceCertificate {
  id: string;
  patchId: string;
  issuedAt: string;
  expiresAt: string;
  verificationLayers: string[];
  overallVerdict: "equivalent" | "non-equivalent" | "inconclusive";
  confidence: number;
  hash: string;
  signingAuthority: string;
}
