module.exports = {
  content: [
    './components/ods-ui/**/*.clj',
    './components/ui-core/**/*.html',
    './components/ui-core/**/*.clj',
    './components/snomed-ui/**/*.clj',
    './components/workbench/**/*.html',
    './components/workbench/**/*.clj',
    './components/workbench/**/*.cljc',
    './components/workbench/**/*.cljs',
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