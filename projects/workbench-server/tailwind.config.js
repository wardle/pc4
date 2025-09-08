module.exports = {
  content: [
    './components/http-server/**/*.html',
    './components/http-server/**/*.clj',
    './components/http-server/**/*.cljc',
    './components/http-server/**/*.cljs',
    './bases/workbench-server/src/**/*.clj'
  ],
  theme: {
    extend: {},
  },
  variants: {
    extend: {},
  },
  plugins: [
    require('@tailwindcss/forms'),
    require('@tailwindcss/typography')
  ],
}