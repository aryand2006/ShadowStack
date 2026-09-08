/** @type {import('next').NextConfig} */

/**
 * Security headers for the Pilot B dashboard.
 * HSTS is intentionally omitted here — terminate TLS and set
 * Strict-Transport-Security at the ingress / load balancer in production.
 */
const apiOrigin = (() => {
  const raw =
    process.env.NEXT_PUBLIC_API_URL?.replace(/\/$/, "") ||
    "http://localhost:8080/api/v1";
  try {
    return new URL(raw).origin;
  } catch {
    return "http://localhost:8080";
  }
})();

const contentSecurityPolicy = [
  "default-src 'self'",
  // Next.js / React hydration and Tailwind utility injection need inline styles;
  // scripts stay self-hosted (no third-party script CDNs in this app).
  "script-src 'self' 'unsafe-inline' 'unsafe-eval'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob:",
  "font-src 'self' data:",
  `connect-src 'self' ${apiOrigin}`,
  "frame-ancestors 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "object-src 'none'",
].join("; ");

const securityHeaders = [
  { key: "Content-Security-Policy", value: contentSecurityPolicy },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  {
    key: "Permissions-Policy",
    value: "camera=(), microphone=(), geolocation=(), payment=()",
  },
];

const nextConfig = {
  reactStrictMode: true,
  transpilePackages: [],
  async headers() {
    return [
      {
        source: "/:path*",
        headers: securityHeaders,
      },
    ];
  },
};

module.exports = nextConfig;
