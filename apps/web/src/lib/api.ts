/* ─── ShadowStack API Client ─────────────────────────────────────────────────
 *  Typed fetch wrapper for the ShadowStack backend API.
 *  In production, BASE_URL would come from environment variables.
 * ──────────────────────────────────────────────────────────────────────────── */

import type {
  Project,
  PatchUnit,
  PatchDetail,
  ReviewQueueItem,
  ReviewDecision,
  AnalyticsDashboard,
  CorpusAnalytics,
} from "@/types";

const BASE_URL = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8000/api/v1";

class ApiError extends Error {
  constructor(
    public status: number,
    public statusText: string,
    public body: unknown
  ) {
    super(`API Error ${status}: ${statusText}`);
    this.name = "ApiError";
  }
}

async function fetchApi<T>(
  endpoint: string,
  options: RequestInit = {}
): Promise<T> {
  const url = `${BASE_URL}${endpoint}`;

  const response = await fetch(url, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...options.headers,
    },
  });

  if (!response.ok) {
    const body = await response.text().catch(() => null);
    throw new ApiError(response.status, response.statusText, body);
  }

  return response.json();
}

/** Project endpoints */
export const projects = {
  list: () => fetchApi<Project[]>("/projects"),
  get: (id: string) => fetchApi<Project>(`/projects/${id}`),
};

/** Patch endpoints */
export const patches = {
  list: (projectId?: string) => {
    const query = projectId ? `?project_id=${projectId}` : "";
    return fetchApi<PatchUnit[]>(`/patches${query}`);
  },
  get: (id: string) => fetchApi<PatchDetail>(`/patches/${id}`),
};

/** Review queue endpoints */
export const queue = {
  list: (filters?: { status?: string; riskTier?: string }) => {
    const params = new URLSearchParams();
    if (filters?.status) params.set("status", filters.status);
    if (filters?.riskTier) params.set("risk_tier", filters.riskTier);
    const query = params.toString() ? `?${params.toString()}` : "";
    return fetchApi<ReviewQueueItem[]>(`/queue${query}`);
  },
  submit: (decision: ReviewDecision) =>
    fetchApi<{ success: boolean }>("/queue/review", {
      method: "POST",
      body: JSON.stringify(decision),
    }),
};

/** Analytics endpoints */
export const analytics = {
  dashboard: () => fetchApi<AnalyticsDashboard>("/analytics/dashboard"),
  corpus: (projectId: string) =>
    fetchApi<CorpusAnalytics>(`/analytics/corpus/${projectId}`),
};

export const api = { projects, patches, queue, analytics };
export default api;
