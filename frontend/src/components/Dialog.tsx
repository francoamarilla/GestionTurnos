import { useEffect, useRef, type ReactNode } from "react";

// Mockup .scrim/.sheet on top of the native <dialog>: showModal() gives
// focus trapping, Escape-to-close and the backdrop for free.
export function Dialog({
  title,
  subtitle,
  onClose,
  footer,
  width = 520,
  children,
}: {
  title: string;
  subtitle?: ReactNode;
  onClose: () => void;
  footer?: ReactNode;
  width?: number;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    dialog?.showModal();
    // showModal() focuses the first focusable element, which is the close
    // button (React's autoFocus prop doesn't set the DOM attribute
    // showModal looks for) - a typed space would then close the dialog.
    // Start on the first field instead, when there is one.
    dialog?.querySelector<HTMLElement>(".sheet-content :is(input, select, textarea)")?.focus();
    return () => dialog?.close();
  }, []);

  return (
    <dialog
      ref={ref}
      className="sheet"
      style={{ maxWidth: width }}
      aria-labelledby="sheet-title"
      onCancel={(e) => {
        e.preventDefault();
        onClose();
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <header>
        <div>
          <h2 id="sheet-title">{title}</h2>
          {subtitle && <p>{subtitle}</p>}
        </div>
        <button type="button" className="icon" onClick={onClose} aria-label="Cerrar">
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M18 6 6 18M6 6l12 12" />
          </svg>
        </button>
      </header>
      <div className="sheet-content">{children}</div>
      {footer && <footer>{footer}</footer>}
    </dialog>
  );
}
