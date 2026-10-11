/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        ink: { DEFAULT: 'rgb(var(--ink) / <alpha-value>)', soft: 'rgb(var(--ink-soft) / <alpha-value>)' },
        mist: 'rgb(var(--mist) / <alpha-value>)',
        surface: 'rgb(var(--surface) / <alpha-value>)',
        line: 'rgb(var(--line) / <alpha-value>)',
        slate: 'rgb(var(--slate) / <alpha-value>)',
        tag: { DEFAULT: 'rgb(var(--tag) / <alpha-value>)', deep: 'rgb(var(--tag-deep) / <alpha-value>)' },
        leaf: 'rgb(var(--leaf) / <alpha-value>)',
        brick: 'rgb(var(--brick) / <alpha-value>)',
      },
      fontFamily: {
        display: ['"Bricolage Grotesque"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
        sans: ['"Public Sans"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      },
      borderRadius: { tag: '0.35rem 0.9rem 0.9rem 0.35rem' },
    },
  },
  plugins: [],
};
