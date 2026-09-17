/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{js,jsx}"],
  theme: {
    extend: {
      colors: {
        brand: {
          50: "#f2f1ff",
          100: "#e6e4ff",
          200: "#cfccff",
          500: "#6d5bf0",
          600: "#5b48e8",
          700: "#4a38c9",
        },
        ink: {
          900: "#0f1222",
          700: "#3a3f52",
          500: "#6b7086",
          300: "#a4a8ba",
        },
      },
      fontFamily: {
        sans: [
          "Inter",
          "-apple-system",
          "BlinkMacSystemFont",
          "Segoe UI",
          "sans-serif",
        ],
      },
      boxShadow: {
        card: "0 1px 2px rgba(15, 18, 34, 0.04), 0 1px 8px rgba(15, 18, 34, 0.04)",
      },
    },
  },
  plugins: [],
};
