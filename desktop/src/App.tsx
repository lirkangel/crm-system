import { BrowserRouter } from "react-router";
import { Toaster } from "sonner";

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
        <Toaster richColors position="top-right" />
      </ApiProvider>
    </AuthProvider>
  );
}

export default App;
