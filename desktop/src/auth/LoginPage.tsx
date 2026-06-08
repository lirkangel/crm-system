import { useTranslation } from "react-i18next";

import { LanguageToggle } from "@/components/LanguageToggle";
import { LoginScreen } from "./LoginScreen";
import type { AuthStatus } from "./types";
import { useLoginForm } from "./useLoginForm";

interface LoginPageProps {
  /** Called after a successful login (F204 wires this to navigation). */
  onLoggedIn?: (status: AuthStatus) => void;
}

/**
 * Container for the login screen: connects the headless `useLoginForm` hook and
 * i18n to the presentational `LoginScreen`, translating error keys to strings.
 */
export function LoginPage({ onLoggedIn }: LoginPageProps) {
  const { t } = useTranslation();
  const { values, errors, formError, submitting, setField, submit } = useLoginForm({
    onSuccess: onLoggedIn,
  });

  return (
    <LoginScreen
      title={t("auth.login.title")}
      subtitle={t("auth.login.subtitle")}
      usernameLabel={t("auth.login.username")}
      usernamePlaceholder={t("auth.login.usernamePlaceholder")}
      passwordLabel={t("auth.login.password")}
      passwordPlaceholder={t("auth.login.passwordPlaceholder")}
      submitLabel={t("auth.login.submit")}
      submittingLabel={t("auth.login.submitting")}
      values={values}
      errors={{
        username: errors.username ? t(errors.username) : undefined,
        password: errors.password ? t(errors.password) : undefined,
      }}
      formError={formError ? t(formError) : null}
      submitting={submitting}
      onFieldChange={setField}
      onSubmit={() => void submit()}
      languageSlot={<LanguageToggle />}
    />
  );
}

export default LoginPage;
