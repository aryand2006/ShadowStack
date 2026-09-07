/* Live ShadowStack API client — no mock fallbacks. */

export type RiskTier = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL" | "COSMETIC";

export interface Project {
  id: string;
  name: string;
  description: string;
  repositoryUrl: string;
  branch: string;
  sourceLanguage: string;
  targetLanguageVersion: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  baseline?: Record<string, unknown> | null;
  analysisSummary?: Record<string, unknown> | null;
}

export interface ReviewQueueItem {
  patchId: string;
  projectId: string;
  projectName: string;
  ruleName: string;
  ruleCategory: string;
  filePath: string;
  startLine: number;
  endLine: number;
  riskScore: number;
  riskTier: RiskTier;
  evidenceStrength?: number;
  shortDescription: string;
  verificationPassed: boolean;
  invariantsPreserved: number;
  invariantsTotal: number;
  generatedAt: string;
  verifiedAt?: string | null;
}

export interface PatchDetail {
  patchId: string;
  projectId: string;
  candidateId: string;
  ruleName: string;
  ruleCategory: string;
  status: string;
  filePath: string;
  startLine: number;
  endLine: number;
  unifiedDiff: string;
  rationale: string;
  invariants: Array<{
    type: string;
    description: string;
    expression: string;
    preserved: boolean;
  }>;
  risk: {
    score: number;
    tier: RiskTier;
    factors: Array<{
      name: string;
      description: string;
      weight: number;
      contribution: number;
    }>;
    confidenceScore: number;
    evidenceStrength?: number;
  };
  verificationEvidence?: {
    behaviorallyEquivalent: boolean;
    testsPassed: number;
    testsFailed: number;
    testsSkipped: number;
    invariantsVerified: string[];
    invariantsViolated: string[];
    proofArtifacts: Record<string, boolean>;
    verifiedAt: string;
  } | null;
  review?: {
    reviewer: string;
    accepted: boolean;
    reason: string | null;
    decidedAt: string;
  } | null;
  createdAt: string;
  updatedAt: string;
}

export interface AnalyticsDashboard {
  totalProjects: number;
  totalPatches: number;
  pendingReview: number;
  acceptedPatches: number;
  rejectedPatches: number;
  verificationPassRate: number;
  averageConfidence: number;
  riskDistribution: Record<string, number>;
}

const BASE_URL =
  process.env.NEXT_PUBLIC_API_URL?.replace(/\/$/, "") ||
  "http://localhost:8080/api/v1";

const DEMO_USER = process.env.NEXT_PUBLIC_DEMO_USER || "admin";
const DEMO_PASS = process.env.NEXT_PUBLIC_DEMO_PASS || "admin";

export class ApiError extends Error {
  constructor(
    public status: number,
    public statusText: string,
    public body: unknown
  ) {
    super(`API Error ${status}: ${statusText}`);
    this.name = "ApiError";
  }
}

function authHeader(): string {
  const token =
    typeof btoa === "function"
      ? btoa(`${DEMO_USER}:${DEMO_PASS}`)
      : Buffer.from(`${DEMO_USER}:${DEMO_PASS}`).toString("base64");
  return `Basic ${token}`;
}

async function fetchApi<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`${BASE_URL}${endpoint}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      Authorization: authHeader(),
      ...options.headers,
    },
    cache: "no-store",
  });
  if (!response.ok) {
    throw new ApiError(
      response.status,
      response.statusText,
      await response.text().catch(() => null)
    );
  }
  if (response.status === 204) return undefined as T;
  return response.json();
}

export const api = {
  projects: {
    list: () => fetchApi<Project[]>("/projects"),
    get: (id: string) => fetchApi<Project>(`/projects/${id}`),
  },
  reviews: {
    queue: () => fetchApi<ReviewQueueItem[]>("/reviews/queue"),
    get: (patchId: string) => fetchApi<PatchDetail>(`/reviews/${patchId}`),
    accept: (patchId: string) =>
      fetchApi<PatchDetail>(`/reviews/${patchId}/accept`, { method: "POST" }),
    reject: (patchId: string, reason: string) =>
      fetchApi<PatchDetail>(`/reviews/${patchId}/reject`, {
        method: "POST",
        body: JSON.stringify({ accepted: false, reason }),
      }),
    history: () => fetchApi<PatchDetail[]>("/reviews/history"),
  },
  analytics: {
    dashboard: () => fetchApi<AnalyticsDashboard>("/analytics/dashboard"),
  },
};

export default api;
