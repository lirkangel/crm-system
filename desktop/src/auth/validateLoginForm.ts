export interface LoginFormValues {
  username: string;
  password: string;
}

export interface LoginFormErrors {
  username?: string;
  password?: string;
}

/**
 * Validates the login form, returning i18n message keys per invalid field so
 * the rendering component owns translation. An empty object means valid.
 */
export function validateLoginForm(values: LoginFormValues): LoginFormErrors {
  const errors: LoginFormErrors = {};

  if (!values.username.trim()) {
    errors.username = "auth.errors.usernameRequired";
  }
  if (!values.password) {
    errors.password = "auth.errors.passwordRequired";
  }

  return errors;
}
