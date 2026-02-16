"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";
import {
  LayoutDashboard,
  ListTodo,
  FileSearch,
  BarChart3,
  Shield,
  Zap,
  Settings,
  HelpCircle,
} from "lucide-react";

const navigation = [
  { name: "Dashboard", href: "/dashboard", icon: LayoutDashboard },
  { name: "Refactor Queue", href: "/queue", icon: ListTodo },
  { name: "Patch Review", href: "/review", icon: FileSearch },
  { name: "Analytics", href: "/analytics", icon: BarChart3 },
];

const secondaryNav = [
  { name: "Settings", href: "/settings", icon: Settings },
  { name: "Documentation", href: "/docs", icon: HelpCircle },
];

export function Sidebar() {
  const pathname = usePathname();

  return (
    <aside className="fixed inset-y-0 left-0 z-50 w-64 flex flex-col bg-shadow-bg-alt border-r border-shadow-border">
      {/* Logo / Branding */}
      <div className="flex items-center gap-3 px-6 py-5 border-b border-shadow-border">
        <div className="flex items-center justify-center w-9 h-9 rounded-lg bg-gradient-to-br from-shadow-accent to-blue-400 shadow-glow">
          <Shield className="h-5 w-5 text-white" />
        </div>
        <div>
          <h1 className="text-base font-bold tracking-tight text-shadow-text">
            ShadowStack
          </h1>
          <p className="text-2xs text-shadow-text-muted font-medium tracking-wide uppercase">
            Verified Modernization
          </p>
        </div>
      </div>

      {/* Primary Navigation */}
      <nav className="flex-1 px-3 py-4 space-y-1 overflow-y-auto">
        <div className="px-3 mb-3">
          <span className="section-header">Navigation</span>
        </div>
        {navigation.map((item) => {
          const isActive = pathname === item.href || pathname?.startsWith(item.href + "/");
          return (
            <Link
              key={item.href}
              href={item.href}
              className={cn(
                "flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-all duration-200",
                isActive
                  ? "bg-shadow-accent/10 text-shadow-accent-bright border border-shadow-accent/20 shadow-[inset_0_1px_0_rgba(96,165,250,0.1)]"
                  : "text-shadow-text-secondary hover:text-shadow-text hover:bg-shadow-surface-hover border border-transparent"
              )}
            >
              <item.icon
                className={cn(
                  "h-4 w-4 flex-shrink-0",
                  isActive ? "text-shadow-accent-bright" : "text-shadow-text-muted"
                )}
              />
              {item.name}
              {isActive && (
                <div className="ml-auto w-1.5 h-1.5 rounded-full bg-shadow-accent-bright shadow-[0_0_6px_rgba(96,165,250,0.6)]" />
              )}
            </Link>
          );
        })}

        <div className="px-3 mb-3 mt-8">
          <span className="section-header">System</span>
        </div>
        {secondaryNav.map((item) => (
          <Link
            key={item.href}
            href={item.href}
            className="flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-shadow-text-secondary hover:text-shadow-text hover:bg-shadow-surface-hover border border-transparent transition-all duration-200"
          >
            <item.icon className="h-4 w-4 flex-shrink-0 text-shadow-text-muted" />
            {item.name}
          </Link>
        ))}
      </nav>

      {/* Status Footer */}
      <div className="px-4 py-4 border-t border-shadow-border">
        <div className="flex items-center gap-2 px-2 py-2 rounded-lg bg-shadow-surface/50">
          <div className="flex items-center gap-2">
            <Zap className="h-3.5 w-3.5 text-shadow-accent" />
            <span className="text-xs font-medium text-shadow-text-secondary">
              Engine Active
            </span>
          </div>
          <div className="ml-auto flex items-center gap-1.5">
            <span className="relative flex h-2 w-2">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75" />
              <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500" />
            </span>
            <span className="text-2xs text-emerald-400 font-medium">Online</span>
          </div>
        </div>
      </div>
    </aside>
  );
}
