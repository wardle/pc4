module.exports = {
  content: [
    './components/araf/resources/**/*.html',
    './components/araf/**/*.clj',
    './bases/araf-server/src/**/*.clj'
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