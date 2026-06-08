import { BrowserRouter } from "react-router";

import { ApiProvider } from "@/api/ApiProvider";
import { AuthProvider } from "@/auth/AuthContext";
import { AppRoutes } from "@/routes";

function App() {
  return (
    <AuthProvider>
      <ApiProvider>
        <BrowserRouter>
          <AppRoutes />
        </BrowserRouter>
      </ApiProvider>
    </AuthProvider>
  );
}

export default App;
