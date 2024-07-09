module.exports = {
  content: [
    './resources/public/**/*.html',
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
