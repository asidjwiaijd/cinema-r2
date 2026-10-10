import { defineConfig } from "vite";
export default defineConfig({
  server: {
    proxy: {
      "/api": "http://127.0.0.1:3210",
      "/media": "http://127.0.0.1:3210",
      "/live": "http://127.0.0.1:3210",
      "/posters": "http://127.0.0.1:3210",
    },
  },
  build: { outDir: "dist" },
});
