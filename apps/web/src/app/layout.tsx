import type { Metadata } from "next";
import { Sidebar } from "@/components/Sidebar";
import "./globals.css";

export const metadata: Metadata = {
  title: "ShadowStack — Verified Language Modernization",
  description:
    "Enterprise verified code modernization engine. Cautious, explainable, and under your control.",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en" className="dark">
      <body className="min-h-screen bg-shadow-bg font-sans">
        {/* Fixed sidebar + scrollable main content */}
        <Sidebar />
        <main className="ml-64 min-h-screen">
          <div className="p-8">{children}</div>
        </main>
      </body>
    </html>
  );
}
