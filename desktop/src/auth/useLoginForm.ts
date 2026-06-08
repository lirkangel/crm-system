import { useCallback, useState } from "react";

import { login as loginRequest } from "./authClient";
import type { AuthStatus } from "./types";
import {
  validateLoginForm,
  type LoginFormErrors,
  type LoginFormValues,
} from "./validateLoginForm";

interface UseLoginFormOptions {
  /** Called after a successful login (F204 will wire this to navigation). */
  onSuccess?: (status: AuthStatus) => void;
}

export interface UseLoginForm {
  values: LoginFormValues;
  errors: LoginFormErrors;
  /** i18n key for a form-level failure (e.g. bad credentials), or null. */
  formError: string | null;
  submitting: boolean;
  setField: (field: keyof LoginFormValues, value: string) => void;
  submit: () => Promise<void>;
}

/**
 * Headless login form logic: state, validation, and the Tauri login call with
 * error handling. UI (claude.ai/design) plugs into the returned values.
 */
export function useLoginForm({ onSuccess }: UseLoginFormOptions = {}): UseLoginForm {
  const [values, setValues] = useState<LoginFormValues>({ username: "", password: "" });
  const [errors, setErrors] = useState<LoginFormErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const setField = useCallback((field: keyof LoginFormValues, value: string) => {
    setValues((prev) => ({ ...prev, [field]: value }));
  }, []);

  const submit = useCallback(async () => {
    setFormError(null);
    const nextErrors = validateLoginForm(values);
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) {
      return;
    }

    setSubmitting(true);
    try {
      const status = await loginRequest(values.username, values.password);
      onSuccess?.(status);
    } catch {
      setFormError("auth.errors.loginFailed");
    } finally {
      setSubmitting(false);
    }
  }, [values, onSuccess]);

  return { values, errors, formError, submitting, setField, submit };
}
