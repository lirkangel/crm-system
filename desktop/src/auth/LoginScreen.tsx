import * as React from "react";
import { BedDouble, Loader2, TriangleAlert, CircleAlert } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

export interface LoginScreenProps {
  title: string;
  subtitle: string;
  usernameLabel: string;
  usernamePlaceholder: string;
  passwordLabel: string;
  passwordPlaceholder: string;
  submitLabel: string;
  submittingLabel: string;
  values: { username: string; password: string };
  errors: { username?: string; password?: string };
  formError: string | null;
  submitting: boolean;
  onFieldChange: (field: "username" | "password", value: string) => void;
  onSubmit: () => void;
  /** Rendered top-right of the window — e.g. a VI/EN switcher. */
  languageSlot?: React.ReactNode;
}

/**
 * Presentational login screen for the on-prem hotel PMS desktop window
 * (~1280×800, Tauri). Purely driven by props — no local state, no API calls.
 * Light + dark mode come from the app's shadcn CSS variables.
 */
export function LoginScreen({
  title,
  subtitle,
  usernameLabel,
  usernamePlaceholder,
  passwordLabel,
  passwordPlaceholder,
  submitLabel,
  submittingLabel,
  values,
  errors,
  formError,
  submitting,
  onFieldChange,
  onSubmit,
  languageSlot,
}: LoginScreenProps) {
  const usernameErrorId = "login-username-error";
  const passwordErrorId = "login-password-error";
  const formErrorId = "login-form-error";

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    onSubmit();
  };

  return (
    <div className="bg-background text-foreground relative flex min-h-screen w-full items-center justify-center px-4 py-8">
      {/* Subtle dotted backdrop — calm, enterprise, non-distracting */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 [background-image:radial-gradient(color-mix(in_oklch,var(--muted-foreground)_8%,transparent)_1px,transparent_1px)] [background-size:22px_22px]"
      />

      {/* Language slot, top-right of the window */}
      {languageSlot ? <div className="absolute top-5 right-5 z-10">{languageSlot}</div> : null}

      <Card className="relative z-10 w-full max-w-sm shadow-lg">
        <CardHeader className="items-center gap-3 text-center">
          <div className="bg-primary text-primary-foreground flex size-12 items-center justify-center rounded-xl">
            <BedDouble className="size-6" aria-hidden />
          </div>
          <div className="space-y-1">
            <CardTitle className="text-xl font-semibold tracking-tight">{title}</CardTitle>
            <CardDescription>{subtitle}</CardDescription>
          </div>
        </CardHeader>

        <CardContent>
          <form onSubmit={handleSubmit} noValidate className="flex flex-col gap-4">
            {/* Username */}
            <div className="flex flex-col gap-2">
              <Label htmlFor="login-username">{usernameLabel}</Label>
              <Input
                id="login-username"
                name="username"
                type="text"
                autoComplete="username"
                autoFocus
                disabled={submitting}
                placeholder={usernamePlaceholder}
                value={values.username}
                aria-invalid={errors.username ? true : undefined}
                aria-describedby={errors.username ? usernameErrorId : undefined}
                onChange={(e) => onFieldChange("username", e.target.value)}
              />
              {errors.username ? (
                <p
                  id={usernameErrorId}
                  className="text-destructive flex items-center gap-1.5 text-sm"
                >
                  <CircleAlert className="size-3.5 shrink-0" aria-hidden />
                  <span>{errors.username}</span>
                </p>
              ) : null}
            </div>

            {/* Password */}
            <div className="flex flex-col gap-2">
              <Label htmlFor="login-password">{passwordLabel}</Label>
              <Input
                id="login-password"
                name="password"
                type="password"
                autoComplete="current-password"
                disabled={submitting}
                placeholder={passwordPlaceholder}
                value={values.password}
                aria-invalid={errors.password ? true : undefined}
                aria-describedby={errors.password ? passwordErrorId : undefined}
                onChange={(e) => onFieldChange("password", e.target.value)}
              />
              {errors.password ? (
                <p
                  id={passwordErrorId}
                  className="text-destructive flex items-center gap-1.5 text-sm"
                >
                  <CircleAlert className="size-3.5 shrink-0" aria-hidden />
                  <span>{errors.password}</span>
                </p>
              ) : null}
            </div>

            {/* Form-level error */}
            {formError ? (
              <div
                id={formErrorId}
                role="alert"
                className={cn(
                  "border-destructive/40 bg-destructive/10 text-destructive flex items-center gap-2 rounded-md border px-3 py-2.5 text-sm",
                )}
              >
                <TriangleAlert className="size-4 shrink-0" aria-hidden />
                <span>{formError}</span>
              </div>
            ) : null}

            {/* Submit */}
            <Button type="submit" className="mt-1 w-full" disabled={submitting} aria-busy={submitting}>
              {submitting ? (
                <>
                  <Loader2 className="size-4 animate-spin" aria-hidden />
                  {submittingLabel}
                </>
              ) : (
                submitLabel
              )}
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}

export default LoginScreen;
