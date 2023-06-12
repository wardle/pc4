/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{clj,cljs,cljc}'],
  theme: {
    extend: {},
  },
  plugins: [
      require('@tailwindcss/forms'),
    ],
}
