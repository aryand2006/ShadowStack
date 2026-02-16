import type { Config } from "tailwindcss";

const config: Config = {
  content: [
    "./src/pages/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/components/**/*.{js,ts,jsx,tsx,mdx}",
    "./src/app/**/*.{js,ts,jsx,tsx,mdx}",
  ],
  darkMode: "class",
  theme: {
    extend: {
      colors: {
        shadow: {
          bg: "#0a0e1a",
          "bg-alt": "#060910",
          surface: "#111827",
          "surface-hover": "#1a2236",
          "surface-active": "#1f2b3f",
          border: "#1e293b",
          "border-bright": "#334155",
          accent: "#3b82f6",
          "accent-bright": "#60a5fa",
          "accent-dim": "#1d4ed8",
          "accent-glow": "rgba(59, 130, 246, 0.15)",
          warning: "#f59e0b",
          "warning-dim": "#b45309",
          danger: "#ef4444",
          "danger-dim": "#b91c1c",
          success: "#10b981",
          "success-dim": "#047857",
          text: "#f1f5f9",
          "text-secondary": "#94a3b8",
          "text-muted": "#64748b",
        },
      },
      fontFamily: {
        sans: [
          "Inter",
          "SF Pro Display",
          "-apple-system",
          "BlinkMacSystemFont",
          "sans-serif",
        ],
        mono: [
          "JetBrains Mono",
          "Fira Code",
          "SF Mono",
          "Consolas",
          "monospace",
        ],
      },
      fontSize: {
        "2xs": ["0.65rem", { lineHeight: "0.85rem" }],
      },
      boxShadow: {
        glow: "0 0 20px rgba(59, 130, 246, 0.15)",
        "glow-lg": "0 0 40px rgba(59, 130, 246, 0.2)",
        "inner-glow": "inset 0 1px 0 rgba(255,255,255,0.05)",
      },
      backgroundImage: {
        "gradient-radial": "radial-gradient(var(--tw-gradient-stops))",
        "gradient-subtle":
          "linear-gradient(135deg, rgba(59,130,246,0.05) 0%, rgba(16,185,129,0.03) 100%)",
      },
      animation: {
        "pulse-slow": "pulse 4s cubic-bezier(0.4, 0, 0.6, 1) infinite",
        "fade-in": "fadeIn 0.3s ease-in-out",
        "slide-up": "slideUp 0.3s ease-out",
      },
      keyframes: {
        fadeIn: {
          "0%": { opacity: "0" },
          "100%": { opacity: "1" },
        },
        slideUp: {
          "0%": { opacity: "0", transform: "translateY(10px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
      },
    },
  },
  plugins: [require("@tailwindcss/typography")],
};

export default config;
