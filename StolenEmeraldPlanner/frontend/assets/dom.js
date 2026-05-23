// el(tag, props, ...children) -> Element.
// Strings become text nodes (escaped by the DOM), so repo-derived content is
// never interpreted as markup. No innerHTML is used anywhere in the app.
function el(tag, props, ...kids) {
  const e = document.createElement(tag);
  const p = props || {};
  for (const k in p) {
    const v = p[k];
    if (k === "cls") e.className = v;
    else if (k === "text") e.textContent = v;
    else if (k === "style" && typeof v === "object") Object.assign(e.style, v);
    else if (k.startsWith("on") && typeof v === "function") e.addEventListener(k.slice(2), v);
    else if (v !== null && v !== undefined) e.setAttribute(k, v);
  }
  for (const kid of kids.flat()) {
    if (kid === null || kid === undefined) continue;
    e.appendChild(typeof kid === "object" ? kid : document.createTextNode(String(kid)));
  }
  return e;
}

function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
}

function mount(node, ...kids) {
  clear(node);
  for (const k of kids.flat()) if (k) node.appendChild(k);
}
