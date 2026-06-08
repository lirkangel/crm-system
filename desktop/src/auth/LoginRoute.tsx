import { useNavigate } from "react-router";

import { useAuth } from "./AuthContext";
import { LoginPage } from "./LoginPage";

/**
 * Routed wrapper for the login screen: on success, adopts the session into the
 * auth context and navigates to the authenticated landing.
 */
export function LoginRoute() {
  const { adoptStatus } = useAuth();
  const navigate = useNavigate();

  return (
    <LoginPage
      onLoggedIn={(status) => {
        adoptStatus(status);
        navigate("/", { replace: true });
      }}
    />
  );
}
