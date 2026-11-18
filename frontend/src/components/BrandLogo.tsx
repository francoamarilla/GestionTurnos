// Recepta logo, untouched brand assets (public/brand). The light version
// (dark text) or the dark one (light text) shows according to <html
// data-theme>, see index.css. The horizontal logo is a PNG: the SVG draws
// the name as Inter text, which an <img> can't render with the page's fonts.
//
// withMark: also renders the isotype alone, which the sidebar shows instead
// of the full logo when it collapses into the narrow top bar.
export function BrandLogo({ withMark = false }: { withMark?: boolean }) {
  return (
    <>
      <img className="brand-logo theme-light" src="/brand/logo-claro.png" alt="Recepta" />
      <img className="brand-logo theme-dark" src="/brand/logo-oscuro.png" alt="Recepta" />
      {withMark && (
        <>
          <img className="brand-mark theme-light" src="/brand/isotipo.svg" alt="Recepta" />
          <img className="brand-mark theme-dark" src="/brand/isotipo-oscuro.svg" alt="Recepta" />
        </>
      )}
    </>
  );
}
