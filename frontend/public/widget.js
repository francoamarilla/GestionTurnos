/*
 * Recepta chat widget for a business's own website.
 *
 *   <script src="https://YOUR-APP/widget.js" data-tenant="TENANT_ID" async></script>
 *
 * Adds a floating button (bottom right) that opens the business's chat in a
 * popup. The chat itself is /chat/<tenant>?embed=1 in an iframe, loaded on
 * the first open, so visiting the site doesn't start a conversation.
 * Optional: data-label="Texto del botón" (accessible name / tooltip).
 *
 * Self-contained on purpose (it runs on someone else's page): plain JS, no
 * dependencies, styles namespaced under .recepta-*. The two colors below
 * are the brand primary and the WhatsApp wallpaper the chat shows behind
 * itself while loading; the host page can't read the app's CSS tokens.
 */
(function () {
  var script = document.currentScript;
  var tenant = script && script.getAttribute("data-tenant");
  if (!tenant) {
    console.warn("[recepta] widget.js needs data-tenant");
    return;
  }
  if (document.querySelector(".recepta-launcher")) return; // pasted twice
  var base = new URL(script.src).origin;
  var label = script.getAttribute("data-label") || "Chateá con nosotros";

  var style = document.createElement("style");
  style.textContent =
    ".recepta-launcher{position:fixed;right:20px;bottom:20px;z-index:2147483000;width:62px;height:62px;" +
    "padding:0;border:0;border-radius:50%;background:#fff;cursor:pointer;display:grid;place-items:center;" +
    "box-shadow:0 6px 22px rgba(0,0,0,.22);transition:transform .15s}" +
    ".recepta-launcher:hover{transform:scale(1.05)}" +
    ".recepta-launcher:focus-visible{outline:3px solid #2f5d50;outline-offset:3px}" +
    ".recepta-launcher img{width:40px;height:40px;display:block}" +
    ".recepta-panel{position:fixed;right:20px;bottom:96px;z-index:2147483000;width:380px;" +
    "height:min(640px,calc(100vh - 120px));border-radius:16px;overflow:hidden;background:#efeae2;" +
    "box-shadow:0 18px 50px rgba(0,0,0,.3);opacity:0;visibility:hidden;transform:translateY(14px) scale(.98);" +
    "transform-origin:bottom right;transition:opacity .18s,transform .18s,visibility 0s .18s}" +
    ".recepta-panel.recepta-open{opacity:1;visibility:visible;transform:none;transition:opacity .18s,transform .18s}" +
    ".recepta-panel iframe{display:block;width:100%;height:100%;border:0}" +
    "@media (max-width:520px){.recepta-panel{right:0;bottom:0;width:100vw;height:100vh;height:100dvh;" +
    "border-radius:0}.recepta-panel.recepta-open~.recepta-launcher{display:none}}" +
    "@media (prefers-reduced-motion:reduce){.recepta-panel,.recepta-launcher{transition:none}}";
  document.head.appendChild(style);

  var panel = document.createElement("div");
  panel.className = "recepta-panel";
  panel.id = "recepta-chat";
  panel.setAttribute("role", "dialog");
  panel.setAttribute("aria-label", label);

  var button = document.createElement("button");
  button.type = "button";
  button.className = "recepta-launcher";
  button.title = label;
  button.setAttribute("aria-label", label);
  button.setAttribute("aria-controls", "recepta-chat");
  button.setAttribute("aria-expanded", "false");
  var icon = document.createElement("img");
  icon.src = base + "/brand/isotipo.svg";
  icon.alt = "";
  button.appendChild(icon);

  var iframe = null;
  function setOpen(open) {
    if (open && !iframe) {
      iframe = document.createElement("iframe");
      iframe.title = label;
      iframe.src = base + "/chat/" + encodeURIComponent(tenant) + "?embed=1";
      panel.appendChild(iframe);
    }
    panel.classList.toggle("recepta-open", open);
    button.setAttribute("aria-expanded", String(open));
    if (open && iframe) iframe.focus();
    if (!open) button.focus();
  }

  button.addEventListener("click", function () {
    setOpen(!panel.classList.contains("recepta-open"));
  });
  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape" && panel.classList.contains("recepta-open")) setOpen(false);
  });
  // The close button inside the chat (only shown when embedded).
  window.addEventListener("message", function (e) {
    if (iframe && e.source === iframe.contentWindow && e.origin === base && e.data && e.data.type === "recepta:close") {
      setOpen(false);
    }
  });

  document.body.appendChild(panel);
  document.body.appendChild(button);
})();
