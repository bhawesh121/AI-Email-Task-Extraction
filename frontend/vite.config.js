import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ".", "");

  const backendUrl =
    env.VITE_BACKEND_URL || "http://localhost:8080";

  return {
    base: "/sla/",
    plugins: [react()],

    server: {
      port: 5173,

      proxy: {
        "/api": {
          target: backendUrl,
          changeOrigin: true,
        },

        "/oauth2": {
          target: backendUrl,
          changeOrigin: true,
        },

        "/login": {
          target: backendUrl,
          changeOrigin: true,
        },

        "/logout": {
          target: backendUrl,
          changeOrigin: true,
        },
      },
    },
  };
});