// Lapis, not verdigris: this is a different application, operating every tenant rather than
// one, and it must never be mistaken for the tenant app at a glance. A hundred degrees of hue
// separation is the cheapest possible way to make "which window am I typing into" answerable
// without reading the URL.
export default defineAppConfig({
  ui: {
    colors: {
      primary: 'lapis',
      neutral: 'basalt'
    }
  }
})
