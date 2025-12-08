module.exports = {
  content: [
    'components/frontend/src/**/*.cljs',
    './resources/public/**/*.js'],
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
