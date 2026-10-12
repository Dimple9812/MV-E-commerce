/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** "true" builds the public demo: an in-browser stand-in backend answers /api calls (src/demo). */
  readonly VITE_DEMO_MODE?: string;
}
