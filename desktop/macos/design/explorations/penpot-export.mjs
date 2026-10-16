// Exports liquid-glass.html as a Penpot file: every screen in light and dark,
// plus the palette and type scale as library assets.
//
// The screens are read back from the rendered DOM rather than drawn a second
// time, so the Penpot file cannot drift from the page. Penpot has layer blur
// but no backdrop blur, so a glass surface over the wallpaper becomes a
// clipped board holding a blurred copy of the wallpaper under its tint.

const LIB = "https://cdn.jsdelivr.net/npm/@penpot/library@1.2.0-RC2/target/library/penpot.js";
const W = 1180, H = 760, GAP = 160, COLS = 3;
// Only these sit directly on the wallpaper; anything deeper would need the
// window's content as its backdrop, which a layer blur cannot reproduce.
const BACKDROP = ["win", "pop", "menubar", "desk-win"];

/* ---------- colour and CSS parsing ---------- */

function splitTop(s) {
  const out = []; let depth = 0, cur = "";
  for (const ch of s) {
    if (ch === "(") depth++;
    if (ch === ")") depth--;
    if (ch === "," && depth === 0) { out.push(cur.trim()); cur = ""; } else cur += ch;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}

function parseColor(s) {
  if (!s) return null;
  s = s.trim();
  let m = s.match(/^rgba?\(([^)]+)\)/);
  if (m) {
    const p = m[1].split(/[\s,/]+/).filter(Boolean).map(Number);
    return { rgb: p.slice(0, 3), a: p.length > 3 ? p[3] : 1 };
  }
  m = s.match(/^color\(srgb ([^)]+)\)/);
  if (m) {
    const p = m[1].split(/[\s/]+/).filter(Boolean).map(Number);
    return { rgb: p.slice(0, 3).map(v => Math.round(v * 255)), a: p.length > 3 ? p[3] : 1 };
  }
  m = s.match(/^#([0-9a-f]{6})/i);
  if (m) { const n = parseInt(m[1], 16); return { rgb: [n >> 16 & 255, n >> 8 & 255, n & 255], a: 1 }; }
  return null;
}
const hex = c => "#" + c.rgb.map(v => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, "0")).join("");
const fill = c => ({ fillColor: hex(c), fillOpacity: +c.a.toFixed(3) });

function gradient(css, w, h) {
  const m = css.match(/^linear-gradient\((.*)\)$/s);
  if (!m) return null;
  const parts = splitTop(m[1]);
  let angle = 180;
  if (/deg$/.test(parts[0])) angle = parseFloat(parts.shift());
  else if (/^to /.test(parts[0])) { const d = parts.shift(); angle = { "to top": 0, "to right": 90, "to bottom": 180, "to left": 270 }[d] ?? 180; }
  const stops = parts.map(p => {
    const pos = p.match(/\s(-?[\d.]+)%\s*$/);
    return { c: parseColor(p), pos: pos ? parseFloat(pos[1]) / 100 : null };
  }).filter(s => s.c);
  stops.forEach((s, i) => { if (s.pos === null) s.pos = stops.length === 1 ? 0 : i / (stops.length - 1); });
  const t = angle * Math.PI / 180, dx = Math.sin(t), dy = -Math.cos(t);
  const len = Math.abs(w * dx) + Math.abs(h * dy);
  const cx = .5, cy = .5, hx = dx * len / 2 / w, hy = dy * len / 2 / h;
  return {
    fillOpacity: 1,
    fillColorGradient: {
      type: "linear", startX: cx - hx, startY: cy - hy, endX: cx + hx, endY: cy + hy, width: 1,
      stops: stops.map(s => ({ color: hex(s.c), opacity: +s.c.a.toFixed(3), offset: Math.max(0, Math.min(1, s.pos)) })),
    },
  };
}

function shadows(css) {
  if (!css || css === "none") return [];
  return splitTop(css).map(s => {
    const inset = /\binset\b/.test(s);
    const colorStr = (s.match(/(rgba?\([^)]*\)|color\([^)]*\)|#[0-9a-f]{6})/i) || [])[0];
    const c = parseColor(colorStr);
    const nums = s.replace(colorStr || "", "").replace("inset", "").trim().split(/\s+/).map(parseFloat);
    if (!c || c.a === 0) return null;
    return {
      id: crypto.randomUUID(), style: inset ? "inner-shadow" : "drop-shadow", hidden: false,
      offsetX: nums[0] || 0, offsetY: nums[1] || 0, blur: nums[2] || 0, spread: nums[3] || 0,
      color: { color: hex(c), opacity: +c.a.toFixed(3) },
    };
  }).filter(Boolean);
}

function radius(cs, w, h) {
  const one = v => {
    const n = parseFloat(v) || 0;
    return Math.min(/%/.test(v) ? n / 100 * Math.min(w, h) : n, w / 2, h / 2);
  };
  return { r1: one(cs.borderTopLeftRadius), r2: one(cs.borderTopRightRadius), r3: one(cs.borderBottomRightRadius), r4: one(cs.borderBottomLeftRadius) };
}

/* ---------- SVG icon paths: absolute, scaled into page space ---------- */

function transformPath(d, s, ox, oy) {
  const tok = d.match(/[a-zA-Z]|-?\d*\.?\d+(?:e-?\d+)?/g) || [];
  let i = 0, cmd = "", x = 0, y = 0, sx = 0, sy = 0; const out = [];
  const num = () => parseFloat(tok[i++]);
  const P = (px, py) => `${(ox + px * s).toFixed(2)} ${(oy + py * s).toFixed(2)}`;
  while (i < tok.length) {
    if (/[a-zA-Z]/.test(tok[i])) cmd = tok[i++];
    const rel = cmd === cmd.toLowerCase(), C = cmd.toUpperCase();
    if (C === "Z") { out.push("Z"); x = sx; y = sy; continue; }
    if (C === "M" || C === "L") {
      let nx = num(), ny = num(); if (rel) { nx += x; ny += y; }
      out.push(`${C} ${P(nx, ny)}`);
      if (C === "M") { sx = nx; sy = ny; cmd = rel ? "l" : "L"; }
      x = nx; y = ny;
    } else if (C === "H") { let nx = num(); if (rel) nx += x; out.push(`L ${P(nx, y)}`); x = nx; }
    else if (C === "V") { let ny = num(); if (rel) ny += y; out.push(`L ${P(x, ny)}`); y = ny; }
    else if (C === "A") {
      const rx = num(), ry = num(), rot = num(), la = num(), sw = num(); let nx = num(), ny = num();
      if (rel) { nx += x; ny += y; }
      out.push(`A ${(rx * s).toFixed(2)} ${(ry * s).toFixed(2)} ${rot} ${la} ${sw} ${P(nx, ny)}`); x = nx; y = ny;
    } else { i++; }
  }
  return out.join(" ");
}

/* ---------- DOM capture ---------- */

function capture(stage) {
  const o = stage.getBoundingClientRect();
  const box = el => { const r = el.getBoundingClientRect(); return { x: r.left - o.left, y: r.top - o.top, w: r.width, h: r.height }; };
  const root = { children: [] };

  function textRuns(el) {
    const paras = [[]]; const nodes = [];
    (function collect(node, cs) {
      for (const ch of node.childNodes) {
        if (ch.nodeType === 3) {
          if (!ch.textContent.trim() && !paras.at(-1).length) continue;
          paras.at(-1).push({ text: ch.textContent.replace(/\s+/g, " "), cs }); nodes.push(ch);
        } else if (ch.nodeType === 1) {
          if (ch.tagName === "BR") { paras.push([]); continue; }
          const ccs = getComputedStyle(ch);
          if (ccs.display === "inline" && ch.tagName !== "svg" && ch.tagName !== "INPUT") collect(ch, ccs);
        }
      }
    })(el, getComputedStyle(el));
    const clean = paras.map(p => {
      while (p.length && !p[0].text.trim()) p.shift();
      while (p.length && !p.at(-1).text.trim()) p.pop();
      if (p.length) { p[0].text = p[0].text.replace(/^ /, ""); p.at(-1).text = p.at(-1).text.replace(/ $/, ""); }
      return p.filter(r => r.text);
    }).filter(p => p.length);
    return { paras: clean, nodes };
  }

  function textNode(el, cs) {
    const { paras, nodes } = textRuns(el);
    if (!paras.length) return null;
    const rects = [];
    for (const n of nodes) { const rg = document.createRange(); rg.selectNodeContents(n); rects.push(...[...rg.getClientRects()].filter(r => r.width > 0)); }
    if (!rects.length) return null;
    const x0 = Math.min(...rects.map(r => r.left)) - o.left, y0 = Math.min(...rects.map(r => r.top)) - o.top;
    const x1 = Math.max(...rects.map(r => r.right)) - o.left, y1 = Math.max(...rects.map(r => r.bottom)) - o.top;
    const lines = new Set(rects.map(r => Math.round(r.top))).size;
    const centred = cs.textAlign === "center" || cs.justifyItems === "center" || cs.justifyContent === "center";
    const b = box(el);
    const leaf = r => {
      const mono = /mono/i.test(r.cs.fontFamily);
      const w = Math.round(parseFloat(r.cs.fontWeight) / 100) * 100 || 400;
      const fs = parseFloat(r.cs.fontSize), lh = parseFloat(r.cs.lineHeight);
      return {
        text: r.text,
        fontId: mono ? "gfont-jetbrains-mono" : "gfont-inter", fontFamily: mono ? "JetBrains Mono" : "Inter",
        fontVariantId: w === 400 ? "regular" : String(Math.min(w, mono ? 800 : 900)), fontWeight: String(w), fontStyle: "normal",
        fontSize: String(fs), lineHeight: isNaN(lh) ? "1.2" : (lh / fs).toFixed(2),
        letterSpacing: r.cs.letterSpacing === "normal" ? "0" : String(parseFloat(r.cs.letterSpacing)),
        textTransform: r.cs.textTransform === "uppercase" ? "uppercase" : "none", textDecoration: "none",
        fills: [fill(parseColor(r.cs.color) || { rgb: [0, 0, 0], a: 1 })],
      };
    };
    const align = centred ? "center" : cs.textAlign === "right" || cs.textAlign === "end" ? "right" : "left";
    const wrap = lines > paras.length;
    return {
      kind: "text", name: paras.map(p => p.map(r => r.text).join("")).join(" ").slice(0, 40),
      x: centred ? b.x : x0, y: y0, w: centred ? b.w : wrap ? x1 - x0 + 2 : x1 - x0, h: y1 - y0,
      grow: centred || wrap ? "auto-height" : "auto-width",
      content: { type: "root", children: [{ type: "paragraph-set", children: paras.map(p => ({ type: "paragraph", textAlign: align, ...leaf(p[0]), children: p.map(leaf) })) }] },
    };
  }

  function visuals(el, cs, b) {
    const fills = [], strokes = [];
    const bi = cs.backgroundImage;
    if (bi && bi !== "none") {
      for (const layer of splitTop(bi)) {
        if (layer.startsWith("linear-gradient")) { const g = gradient(layer, b.w, b.h); if (g) fills.push(g); }
        else if (layer.startsWith("conic-gradient")) {
          if (el.classList.contains("clear")) { fills.push({ fillColor: "#ffffff", fillOpacity: 1 }); strokes.push({ strokeColor: "#c4c9d4", strokeOpacity: 1, strokeWidth: 1, strokeAlignment: "inner", strokeStyle: "dashed" }); }
          else fills.push(gradient("linear-gradient(135deg, rgb(255,0,0), rgb(255,255,0), rgb(0,255,0), rgb(0,255,255), rgb(0,0,255), rgb(255,0,255))", b.w, b.h));
        }
      }
    }
    const bg = parseColor(cs.backgroundColor);
    if (bg && bg.a > 0) fills.push(fill(bg));
    const bw = parseFloat(cs.borderTopWidth), bc = parseColor(cs.borderTopColor);
    if (bw > 0 && bc && bc.a > 0 && cs.borderTopStyle !== "none") strokes.push({ strokeColor: hex(bc), strokeOpacity: +bc.a.toFixed(3), strokeWidth: bw, strokeAlignment: "inner", strokeStyle: "solid" });
    const sh = shadows(cs.boxShadow);
    const bf = (cs.backdropFilter || cs.webkitBackdropFilter || "").match(/blur\(([\d.]+)px\)/);
    const backdrop = bf && BACKDROP.some(c => el.classList.contains(c)) ? parseFloat(bf[1]) : 0;
    if (!fills.length && !strokes.length && !sh.length && !backdrop) return null;
    return { fills, strokes, shadow: sh, backdrop, clip: cs.overflow === "hidden" || !!backdrop, ...radius(cs, b.w, b.h) };
  }

  function name(el) { return (el.className && typeof el.className === "string" && el.className.split(" ")[0]) || el.tagName.toLowerCase(); }

  function walk(el, out) {
    const cs = getComputedStyle(el);
    if (cs.display === "none" || cs.visibility === "hidden" || +cs.opacity === 0) return;
    const b = box(el);
    if (b.w === 0 || b.h === 0) return;
    const op = +cs.opacity < 1 ? +cs.opacity : undefined;

    if (el.tagName === "svg") {
      const vb = (el.getAttribute("viewBox") || "0 0 24 24").split(/\s+/).map(Number);
      const s = b.w / vb[2], c = parseColor(cs.color) || { rgb: [0, 0, 0], a: 1 };
      const sw = parseFloat(el.getAttribute("stroke-width") || "2") * s;
      for (const p of el.querySelectorAll("path, circle")) {
        let d = p.getAttribute("d");
        if (p.tagName === "circle") { const cx = +p.getAttribute("cx"), cy = +p.getAttribute("cy"), r = +p.getAttribute("r"); d = `M${cx - r} ${cy}A${r} ${r} 0 1 0 ${cx + r} ${cy}A${r} ${r} 0 1 0 ${cx - r} ${cy}Z`; }
        out.children.push({ kind: "path", name: "icon", x: b.x, y: b.y, w: b.w, h: b.h, d: transformPath(d, s, b.x, b.y), opacity: op,
          strokes: [{ strokeColor: hex(c), strokeOpacity: +c.a.toFixed(3), strokeWidth: +sw.toFixed(2), strokeAlignment: "center", strokeStyle: "solid", strokeCapStart: "round", strokeCapEnd: "round" }] });
      }
      return;
    }
    if (el.tagName === "INPUT") {
      const acc = parseColor(getComputedStyle(document.documentElement).getPropertyValue("--g-accent")) || { rgb: [10, 132, 255], a: 1 };
      if (el.type === "range") {
        const t = (el.value - el.min) / (el.max - el.min), cy = b.y + b.h / 2;
        out.children.push({ kind: "box", name: "track", x: b.x, y: cy - 2, w: b.w, h: 4, r1: 2, r2: 2, r3: 2, r4: 2, fills: [{ fillColor: "#8e95a3", fillOpacity: .35 }] });
        out.children.push({ kind: "box", name: "track-fill", x: b.x, y: cy - 2, w: b.w * t, h: 4, r1: 2, r2: 2, r3: 2, r4: 2, fills: [fill(acc)] });
        out.children.push({ kind: "box", name: "thumb", x: b.x + (b.w - 16) * t, y: cy - 8, w: 16, h: 16, r1: 8, r2: 8, r3: 8, r4: 8, fills: [{ fillColor: "#ffffff", fillOpacity: 1 }],
          shadow: shadows("rgba(0,0,0,0.3) 0px 1px 3px 0px") });
      } else if (el.type === "checkbox") {
        out.children.push({ kind: "box", name: "checkbox", x: b.x, y: b.y, w: b.w, h: b.h, r1: 4, r2: 4, r3: 4, r4: 4,
          fills: [el.checked ? fill(acc) : { fillColor: "#ffffff", fillOpacity: .9 }], strokes: el.checked ? [] : [{ strokeColor: "#8e95a3", strokeOpacity: .7, strokeWidth: 1, strokeAlignment: "inner", strokeStyle: "solid" }] });
      }
      return;
    }

    const v = visuals(el, cs, b);
    let me = out;
    if (v) { const node = { kind: "box", name: name(el), x: b.x, y: b.y, w: b.w, h: b.h, opacity: op, ...v, children: [] }; out.children.push(node); me = node; }
    if (cs.outlineStyle !== "none" && parseFloat(cs.outlineWidth) > 0) {
      const ow = parseFloat(cs.outlineWidth), off = parseFloat(cs.outlineOffset) || 0, oc = parseColor(cs.outlineColor);
      if (oc) out.children.push({ kind: "box", name: "selected", x: b.x - off - ow, y: b.y - off - ow, w: b.w + 2 * (off + ow), h: b.h + 2 * (off + ow),
        ...radius({ borderTopLeftRadius: "50%", borderTopRightRadius: "50%", borderBottomRightRadius: "50%", borderBottomLeftRadius: "50%" }, b.w + 2 * (off + ow), b.h + 2 * (off + ow)),
        fills: [], strokes: [{ strokeColor: hex(oc), strokeOpacity: +oc.a.toFixed(3), strokeWidth: ow, strokeAlignment: "inner", strokeStyle: "solid" }] });
    }
    const t = textNode(el, cs);
    if (t) me.children.push(t);
    for (const ch of el.children) {
      const ccs = getComputedStyle(ch);
      if (ccs.display === "inline" && ch.tagName !== "svg" && ch.tagName !== "INPUT" && ch.tagName !== "BR") continue;
      walk(ch, me);
    }
    const after = getComputedStyle(el, "::after");
    if (after.content !== "none" && after.position === "absolute") {
      const av = visuals(el, after, b);
      if (av) me.children.push({ kind: "box", name: "gloss", x: b.x, y: b.y, w: b.w, h: b.h, ...av, ...radius(cs, b.w, b.h), clip: false, backdrop: 0, children: [] });
    }
  }
  for (const ch of stage.children) walk(ch, root);
  return root.children;
}

/* ---------- Penpot build ---------- */

async function wallpaperBlob(cssUrl, fallback) {
  const canvas = document.createElement("canvas"); canvas.width = W; canvas.height = H;
  const g = canvas.getContext("2d");
  const m = cssUrl.match(/url\("?(.*?)"?\)\s*$/s);
  if (m) {
    const img = new Image(); img.src = m[1]; await img.decode();
    g.drawImage(img, 0, 0, W, H);
  } else { g.fillStyle = fallback; g.fillRect(0, 0, W, H); }
  return new Promise(res => canvas.toBlob(res, "image/png"));
}

function emit(ctx, n, ox, oy, wall) {
  const geo = { x: ox + n.x, y: oy + n.y, width: Math.max(n.w, .01), height: Math.max(n.h, .01) };
  const op = n.opacity !== undefined ? { opacity: n.opacity } : {};
  if (n.kind === "text") {
    ctx.addText({ name: n.name, ...geo, growType: n.grow, content: n.content });
    return;
  }
  if (n.kind === "path") {
    const d = n.d.replace(/A ([\d.]+) ([\d.]+) (\S+) (\S+) (\S+) (-?[\d.]+) (-?[\d.]+)/g, (_, rx, ry, r, la, sw, x, y) => `A ${rx} ${ry} ${r} ${la} ${sw} ${(+x + ox).toFixed(2)} ${(+y + oy).toFixed(2)}`)
      .replace(/([ML]) (-?[\d.]+) (-?[\d.]+)/g, (_, c, x, y) => `${c} ${(+x + ox).toFixed(2)} ${(+y + oy).toFixed(2)}`);
    ctx.addPath({ name: n.name, ...geo, content: d, fills: [], strokes: n.strokes, ...op });
    return;
  }
  const rad = { r1: n.r1 || 0, r2: n.r2 || 0, r3: n.r3 || 0, r4: n.r4 || 0 };
  const hasKids = n.children && n.children.length;
  if (!hasKids && !n.backdrop) {
    ctx.addRect({ name: n.name, ...geo, ...rad, fills: n.fills || [], strokes: n.strokes || [], shadow: n.shadow || [], ...op });
    return;
  }
  const drop = (n.shadow || []).filter(s => s.style === "drop-shadow"), inner = (n.shadow || []).filter(s => s.style !== "drop-shadow");
  ctx.addBoard({ name: n.name, ...geo, ...rad, showContent: !n.clip, ...op,
    fills: n.backdrop ? [] : n.fills, strokes: n.backdrop ? [] : n.strokes, shadow: n.backdrop ? drop : n.shadow });
  if (n.backdrop) {
    ctx.addRect({ name: "backdrop blur", x: ox, y: oy, width: W, height: H, fills: [{ fillOpacity: 1, fillImage: wall }], blur: { type: "layer-blur", value: n.backdrop, hidden: false } });
    ctx.addRect({ name: "glass", ...geo, ...rad, fills: n.fills, strokes: n.strokes, shadow: inner });
  }
  for (const c of n.children || []) emit(ctx, c, ox, oy, wall);
  ctx.closeBoard();
}

/**
 * Builds the .penpot file from the page's current Appearance settings.
 * `mock` is what liquid-glass.html exposes: its state, screens and re-apply hook.
 */
export async function exportPenpot(mock) {
  const penpot = await import(LIB);
  const { S, SCREENS, apply, sync } = mock;
  const saved = { ...S };
  const host = document.createElement("div");
  host.style.cssText = `position:fixed;left:-${W * 3}px;top:0;width:${W}px;height:${H}px;pointer-events:none`;
  document.body.appendChild(host);
  const pages = [];
  try {
    for (const mode of ["light", "dark"]) {
      Object.assign(S, { mode, depth: 0 }); apply();
      const root = getComputedStyle(document.documentElement);
      const v = k => root.getPropertyValue(k).trim();
      const screens = [];
      for (const [id, [title]] of Object.entries(SCREENS)) {
        host.innerHTML = `<div class="stage" style="transform:none">${SCREENS[id][2]()}</div>`;
        sync();
        screens.push({ title, nodes: capture(host.firstElementChild) });
      }
      pages.push({ mode, screens, wall: await wallpaperBlob(v("--g-wall"), S.wallColor),
        colors: ["--g-accent", "--g-accent-soft", "--g-accent-ink", "--g-glass", "--g-glass-hi", "--g-glass-lo", "--g-glass-top", "--g-glass-sheet", "--g-ink", "--g-ink-2", "--g-hair", "--g-edge", "--g-rim-hi", "--g-rim-lo"]
          .map(k => [k.replace("--g-", ""), parseColor(v(k))]).filter(([, c]) => c) });
    }
  } finally {
    host.remove();
    Object.assign(S, saved); apply();
  }

  const ctx = penpot.createBuildContext();
  ctx.addFile({ name: "Ketch — Liquid glass" });
  for (const p of pages) for (const [n, c] of p.colors) ctx.addLibraryColor({ name: n, path: p.mode === "light" ? "Light" : "Dark", color: hex(c), opacity: +c.a.toFixed(3) });
  for (const [n, size, w, fam] of [["Large title", 34, 800, "Inter"], ["Title", 26, 700, "Inter"], ["Headline", 14, 600, "Inter"], ["Body", 13, 400, "Inter"], ["Caption", 12, 400, "Inter"], ["Badge", 11, 700, "Inter"], ["Mono", 12, 500, "JetBrains Mono"]]) {
    const mono = fam !== "Inter";
    ctx.addLibraryTypography({ name: n, path: "Ketch", fontId: mono ? "gfont-jetbrains-mono" : "gfont-inter", fontFamily: fam, fontVariantId: w === 400 ? "regular" : String(w),
      fontSize: String(size), fontWeight: String(w), fontStyle: "normal", lineHeight: "1.3", letterSpacing: "0", textTransform: "none" });
  }
  for (const p of pages) {
    ctx.addPage({ name: p.mode === "light" ? "Light" : "Dark" });
    const mediaId = ctx.addFileMedia({ name: `wallpaper-${p.mode}`, width: W, height: H }, p.wall);
    const wall = { id: mediaId, width: W, height: H, mtype: "image/png", name: `wallpaper-${p.mode}`, keepAspectRatio: false };
    p.screens.forEach((s, i) => {
      const ox = (i % COLS) * (W + GAP), oy = Math.floor(i / COLS) * (H + GAP);
      ctx.addBoard({ name: `${String(i + 1).padStart(2, "0")} ${s.title}`, x: ox, y: oy, width: W, height: H, r1: 22, r2: 22, r3: 22, r4: 22, showContent: false,
        fills: [{ fillOpacity: 1, fillImage: wall }] });
      for (const n of s.nodes) emit(ctx, n, ox, oy, wall);
      ctx.closeBoard();
    });
    ctx.closePage();
  }
  ctx.closeFile();
  return penpot.exportAsBlob(ctx);
}
