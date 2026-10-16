/* Cotizador Vidriera Gómez — cada usuario guarda sus datos en su cuenta (Firebase). */
import * as Cloud from './cloud.js';

(() => {
  'use strict';

  // Datos de la versión anterior (sin cuentas) que vivían solo en este navegador
  const LEGACY_KEY = 'cotizador-vidriera-v1';
  const $ = (sel, root = document) => root.querySelector(sel);
  const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

  // ---------- Datos iniciales (tomados del Google Sheets) ----------
  const SEED = {
    settings: {
      name: 'Mi negocio',
      address: '',
      phone: '',
      email: '',
      prefix: 'COT00',
      nextFolio: 1,
      iva: 16,
      template: 'clasica',
      notes: '',
    },
    clients: [],
    products: [
      ['Perfil Aluminio 2"', 'Línea 2 Pulgadas', 1300],
      ['Perfil Aluminio 3"', 'Línea 3 Pulgadas', 1500],
      ['Zoclo 01', 'Zoclo superior ventana corrediza', 100],
      ['Zoclo 02', 'Zoclo inferior ventana corrediza', 100],
      ['Bastidor', 'Puerta Línea 3" y contramarco', 500],
      ['Vidrio Plano 01', 'Vidrio claro 4mm', 2000],
      ['Vidrio Plano 02', 'Vidrio claro 5mm', 2300],
      ['Vidrio flotado 01', 'Vidrio 4mm', 3200],
      ['Vidrio flotado 02', 'Vidrio 5mm', 3400],
      ['Vidrio templado 01', 'Vidrio 6mm', 4000],
      ['Vidrio templado 02', 'Vidrio 8mm', 4300],
      ['Kit cancel de baño', 'Herrajes para cancel de baño', 500],
      ['Carretillas', 'Línea 2" y 3"', 120],
      ['Rodamientos', 'Línea 2" y 3"', 150],
      ['Bisagra', 'Bisagras tipo libro p/aluminio', 45],
      ['Silicón 01', 'Para baño y cocina (fungicida)', 180],
      ['Silicón 02', 'Pens 100%', 250],
      ['Acrilastic', 'Sellador acrílico vidrio/aluminio', 300],
    ].map(([name, desc, price], i) => ({ id: i + 1, name, desc, price })),
    quotes: [],
    draft: null,
  };

  const TEMPLATES = [
    { id: 'clasica', name: 'Clásica', hint: 'Como tu hoja', sw: 'linear-gradient(#0f4c5c 0 22%, #fff 22% 40%, #eef4f6 40% 100%)' },
    { id: 'moderna', name: 'Moderna', hint: 'Banda de color', sw: 'linear-gradient(135deg, #0f4c5c, #1b7f94 45%, #fff 45%)' },
    { id: 'minimal', name: 'Minimalista', hint: 'B/N, ahorra tinta', sw: 'linear-gradient(#fff 0 20%, #111 20% 23%, #fff 23%)' },
    { id: 'ticket', name: 'Ticket', hint: 'Rollo 80 mm', sw: 'repeating-linear-gradient(#fff 0 4px, #bbb 4px 5px)' },
  ];

  // ---------- Estado ----------
  let db = null;
  let editingQuoteId = null; // id de cotización guardada que se está editando (null = nueva)

  const normalize = (data) => ({
    ...structuredClone(SEED), ...data,
    settings: { ...SEED.settings, ...data.settings },
    quotes: Array.isArray(data.quotes) ? data.quotes : [],
  });
  const mainPart = () => ({ settings: db.settings, clients: db.clients, products: db.products, draft: db.draft });
  // Guarda ajustes, catálogos y borrador; las cotizaciones se guardan una por una.
  function save() { Cloud.saveMain(mainPart()); }
  const cloudErr = (e) => { console.error(e); toast(Cloud.errorText(e)); };
  const nextId = (arr) => arr.reduce((m, x) => Math.max(m, x.id), 0) + 1;

  // ---------- Utilidades ----------
  const money = new Intl.NumberFormat('es-MX', { style: 'currency', currency: 'MXN' });
  const fmt = (n) => money.format(Number(n) || 0);
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const today = () => {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  };
  const fmtDate = (iso) => {
    if (!iso) return '';
    const [y, m, d] = iso.split('-').map(Number);
    return new Date(y, m - 1, d).toLocaleDateString('es-MX', { day: '2-digit', month: 'long', year: 'numeric' });
  };
  const round2 = (n) => Math.round((Number(n) || 0) * 100) / 100;
  const folioText = (n) => `${db.settings.prefix}${n}`;

  let toastTimer;
  function toast(msg) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 2200);
  }

  // ---------- Navegación ----------
  function showView(name) {
    $$('.view').forEach((v) => v.classList.toggle('active', v.id === `view-${name}`));
    $$('.nav-btn').forEach((b) => b.classList.toggle('active', b.dataset.view === name));
    if (name === 'historial') renderHistory();
    if (name === 'clientes') renderClients();
    if (name === 'productos') renderProducts();
    if (name === 'ajustes') renderSettings();
    window.scrollTo({ top: 0 });
  }
  $$('.nav-btn').forEach((b) => b.addEventListener('click', () => showView(b.dataset.view)));

  // =====================================================================
  // COTIZACIÓN (editor)
  // =====================================================================
  function blankDraft() {
    return { folio: null, date: today(), clientId: null, client: null, items: [{ productId: null, qty: 1 }] };
  }

  const q = () => db.draft;

  // Datos de cliente/producto: del catálogo si existe, si no, la copia guardada en la cotización.
  function clientOf(quote) {
    return db.clients.find((c) => c.id === quote.clientId) || quote.client || null;
  }
  function lineData(item) {
    const p = db.products.find((x) => x.id === item.productId);
    if (p) return { name: p.name, desc: p.desc, price: Number(p.price) };
    if (item.name) return { name: item.name, desc: item.desc, price: Number(item.price) };
    return null;
  }
  function computeTotals(quote, ivaRate = db.settings.iva) {
    const subtotal = round2(quote.items.reduce((s, it) => {
      const d = lineData(it);
      return s + (d ? d.price * (Number(it.qty) || 0) : 0);
    }, 0));
    const iva = round2(subtotal * (Number(ivaRate) || 0) / 100);
    return { subtotal, iva, total: round2(subtotal + iva) };
  }

  function productOptions(selectedId) {
    return '<option value="">Elige un producto…</option>' + db.products
      .map((p) => `<option value="${p.id}" ${p.id === selectedId ? 'selected' : ''}>${esc(p.name)}</option>`).join('');
  }

  function renderQuote() {
    const d = q();
    $('#brand-name').textContent = db.settings.name;
    $('#q-title').textContent = editingQuoteId ? `Editando ${d.folio}` : 'Nueva cotización';
    $('#q-sub').textContent = editingQuoteId ? 'Guarda para actualizar la cotización' : 'Se guarda automáticamente como borrador';
    $('#q-folio').value = d.folio || folioText(db.settings.nextFolio);
    $('#q-date').value = d.date || today();

    // Cliente
    const sel = $('#q-client');
    sel.innerHTML = '<option value="">Elige un cliente…</option>' + db.clients
      .map((c) => `<option value="${c.id}">${esc(c.name)}</option>`).join('');
    // Si el cliente fue borrado del catálogo, mantenerlo visible desde la copia
    if (d.clientId && !db.clients.some((c) => c.id === d.clientId) && d.client) {
      sel.insertAdjacentHTML('beforeend', `<option value="${d.clientId}">${esc(d.client.name)} (eliminado)</option>`);
    }
    sel.value = d.clientId ? String(d.clientId) : '';
    renderClientInfo();
    renderItems();
  }

  function renderClientInfo() {
    const c = clientOf(q());
    $('#q-client-info').innerHTML = c ? `
      <dt>Dirección</dt><dd>${esc(c.address) || '—'}</dd>
      <dt>Teléfono</dt><dd>${esc(c.phone) || '—'}</dd>
      <dt>Correo</dt><dd>${esc(c.email) || '—'}</dd>` : '';
  }

  function renderItems() {
    const box = $('#q-items');
    const items = q().items;
    if (!items.length) {
      box.innerHTML = '<div class="empty">Sin productos. Agrega el primero.</div>';
    } else {
      box.innerHTML = items.map((it, i) => {
        const d = lineData(it);
        const deleted = it.productId && !db.products.some((p) => p.id === it.productId);
        const opts = productOptions(it.productId) + (deleted ? `<option value="${it.productId}" selected>${esc(it.name)} (eliminado)</option>` : '');
        return `
        <div class="item" data-i="${i}">
          <div class="i-prod"><label class="lbl">Producto</label><select class="f-prod" aria-label="Producto">${opts}</select></div>
          <div class="i-desc">${d ? esc(d.desc) : ''}</div>
          <div class="i-price"><span class="lbl">Valor unitario</span><div class="val num">${d ? fmt(d.price) : '—'}</div></div>
          <div class="i-qty"><span class="lbl">Cantidad</span>
            <div class="qty">
              <button type="button" class="f-dec" aria-label="Menos">−</button>
              <input class="f-qty" type="number" inputmode="decimal" min="0" step="any" value="${esc(it.qty)}" aria-label="Cantidad">
              <button type="button" class="f-inc" aria-label="Más">+</button>
            </div>
          </div>
          <div class="i-total num">${d ? fmt(d.price * (Number(it.qty) || 0)) : '—'}</div>
          <div class="i-del"><button type="button" class="icon-btn danger f-del" aria-label="Quitar producto">✕</button></div>
        </div>`;
      }).join('');
    }
    const n = items.filter((it) => lineData(it)).length;
    $('#items-count').textContent = n ? `${n} ${n === 1 ? 'partida' : 'partidas'}` : '';
    renderTotals();
  }

  function renderTotals() {
    const ivaRate = editingQuoteId ? (db.quotes.find((x) => x.id === editingQuoteId)?.ivaRate ?? db.settings.iva) : db.settings.iva;
    const t = computeTotals(q(), ivaRate);
    $('#t-sub').textContent = fmt(t.subtotal);
    $('#t-iva-label').textContent = `IVA (${ivaRate}%)`;
    $('#t-iva').textContent = fmt(t.iva);
    $('#t-total').textContent = fmt(t.total);
    $('#m-total').textContent = fmt(t.total);
    // Actualiza totales por línea sin redibujar (no perder foco del input)
    $$('#q-items .item').forEach((row) => {
      const it = q().items[row.dataset.i];
      const d = it && lineData(it);
      $('.i-total', row).textContent = d ? fmt(d.price * (Number(it.qty) || 0)) : '—';
    });
  }

  function persistDraft() { save(); }

  // Eventos del editor
  $('#q-date').addEventListener('change', (e) => { q().date = e.target.value; persistDraft(); });
  $('#q-client').addEventListener('change', (e) => {
    q().clientId = e.target.value ? Number(e.target.value) : null;
    q().client = null;
    renderClientInfo(); persistDraft();
  });
  $('#btn-add-item').addEventListener('click', () => {
    q().items.push({ productId: null, qty: 1 });
    renderItems(); persistDraft();
    const rows = $$('#q-items .f-prod');
    rows[rows.length - 1]?.focus();
  });
  $('#q-items').addEventListener('change', (e) => {
    const row = e.target.closest('.item'); if (!row) return;
    const it = q().items[row.dataset.i];
    if (e.target.classList.contains('f-prod')) {
      it.productId = e.target.value ? Number(e.target.value) : null;
      delete it.name; delete it.desc; delete it.price;
      renderItems();
    }
    persistDraft();
  });
  $('#q-items').addEventListener('input', (e) => {
    if (!e.target.classList.contains('f-qty')) return;
    const it = q().items[e.target.closest('.item').dataset.i];
    const v = parseFloat(e.target.value);
    it.qty = Number.isFinite(v) && v >= 0 ? v : 0;
    renderTotals(); persistDraft();
  });
  $('#q-items').addEventListener('click', (e) => {
    const row = e.target.closest('.item'); if (!row) return;
    const i = Number(row.dataset.i);
    const it = q().items[i];
    if (e.target.closest('.f-del')) { q().items.splice(i, 1); renderItems(); persistDraft(); return; }
    const step = e.target.closest('.f-inc') ? 1 : e.target.closest('.f-dec') ? -1 : 0;
    if (step) {
      it.qty = Math.max(0, round2((Number(it.qty) || 0) + step));
      $('.f-qty', row).value = it.qty;
      renderTotals(); persistDraft();
    }
  });

  function newQuote(askFirst = true) {
    const hasContent = q().clientId || q().items.some((it) => it.productId);
    if (askFirst && hasContent && !editingQuoteId && !confirm('¿Descartar el borrador actual y empezar una nueva cotización?')) return;
    editingQuoteId = null;
    db.draft = blankDraft();
    save(); renderQuote();
  }
  $('#btn-new').addEventListener('click', () => newQuote());

  // Construye la cotización final (con copias de datos) lista para guardar o exportar
  function buildQuote() {
    const d = q();
    const existing = editingQuoteId ? db.quotes.find((x) => x.id === editingQuoteId) : null;
    const ivaRate = existing ? existing.ivaRate : Number(db.settings.iva);
    const c = clientOf(d);
    const items = d.items.map((it) => {
      const ld = lineData(it);
      return ld ? { productId: it.productId, name: ld.name, desc: ld.desc, price: ld.price, qty: Number(it.qty) || 0 } : null;
    }).filter(Boolean);
    const t = computeTotals({ items }, ivaRate);
    return {
      id: existing ? existing.id : null,
      folio: existing ? existing.folio : folioText(db.settings.nextFolio),
      date: d.date || today(),
      clientId: d.clientId,
      client: c ? { name: c.name, address: c.address, phone: c.phone, email: c.email } : null,
      items, ivaRate, ...t,
      createdAt: existing ? existing.createdAt : new Date().toISOString(),
    };
  }

  function validate(quote) {
    if (!quote.client) { toast('Elige un cliente'); $('#q-client').focus(); return false; }
    if (!quote.items.length) { toast('Agrega al menos un producto'); return false; }
    return true;
  }

  function saveQuote() {
    const quote = buildQuote();
    if (!validate(quote)) return null;
    if (quote.id) {
      const i = db.quotes.findIndex((x) => x.id === quote.id);
      db.quotes[i] = quote;
      toast(`${quote.folio} actualizada`);
    } else {
      quote.id = nextId(db.quotes);
      db.quotes.unshift(quote);
      db.settings.nextFolio = Number(db.settings.nextFolio) + 1;
      toast(`${quote.folio} guardada`);
    }
    Cloud.putQuote(quote).catch(cloudErr);
    // Queda abierta en modo edición para poder exportarla
    editingQuoteId = quote.id;
    db.draft = quoteToDraft(quote);
    save(); renderQuote();
    return quote;
  }
  $('#btn-save').addEventListener('click', saveQuote);
  $('#m-save').addEventListener('click', saveQuote);

  function quoteToDraft(quote) {
    return {
      folio: quote.folio, date: quote.date, clientId: quote.clientId, client: quote.client,
      items: quote.items.map((it) => ({ ...it })),
    };
  }

  function openExportFromEditor() {
    const quote = buildQuote();
    if (!validate(quote)) return;
    openExport(quote);
  }
  $('#btn-export').addEventListener('click', openExportFromEditor);
  $('#m-export').addEventListener('click', openExportFromEditor);

  // Alta rápida de cliente desde el editor
  $('#btn-quick-client').addEventListener('click', () => editClient(null, (c) => {
    q().clientId = c.id; q().client = null; persistDraft(); renderQuote();
  }));

  // =====================================================================
  // HISTORIAL
  // =====================================================================
  function renderHistory() {
    const term = $('#h-search').value.trim().toLowerCase();
    const list = db.quotes.filter((x) => !term || x.folio.toLowerCase().includes(term) || (x.client?.name || '').toLowerCase().includes(term));
    $('#h-list').innerHTML = list.length ? list.map((x) => `
      <div class="list-item" data-id="${x.id}">
        <div class="grow">
          <div class="title"><span class="badge">${esc(x.folio)}</span>${esc(x.client?.name || 'Sin cliente')}</div>
          <div class="meta">${esc(fmtDate(x.date))} · ${x.items.length} ${x.items.length === 1 ? 'partida' : 'partidas'}</div>
        </div>
        <div class="amount">${fmt(x.total)}</div>
        <div class="actions">
          <button class="btn small" data-act="open">Abrir</button>
          <button class="btn small primary" data-act="export">PDF</button>
          <button class="icon-btn danger" data-act="del" aria-label="Eliminar">✕</button>
        </div>
      </div>`).join('') : `<div class="empty">${db.quotes.length ? 'Sin resultados' : 'Aún no hay cotizaciones guardadas.'}</div>`;
  }
  $('#h-search').addEventListener('input', renderHistory);
  $('#h-list').addEventListener('click', (e) => {
    const btn = e.target.closest('[data-act]'); if (!btn) return;
    const id = Number(btn.closest('.list-item').dataset.id);
    const quote = db.quotes.find((x) => x.id === id);
    if (btn.dataset.act === 'open') {
      editingQuoteId = id; db.draft = quoteToDraft(quote); save();
      renderQuote(); showView('cotizar');
    } else if (btn.dataset.act === 'export') {
      openExport(quote);
    } else if (btn.dataset.act === 'del' && confirm(`¿Eliminar la cotización ${quote.folio}?`)) {
      db.quotes = db.quotes.filter((x) => x.id !== id);
      Cloud.removeQuote(id).catch(cloudErr);
      if (editingQuoteId === id) { editingQuoteId = null; db.draft = blankDraft(); renderQuote(); }
      save(); renderHistory();
    }
  });

  // =====================================================================
  // CLIENTES y PRODUCTOS (catálogos)
  // =====================================================================
  function renderClients() {
    const term = $('#c-search').value.trim().toLowerCase();
    const list = db.clients.filter((c) => !term || [c.name, c.phone, c.email, c.address].join(' ').toLowerCase().includes(term));
    $('#c-count').textContent = `${db.clients.length} registrados`;
    $('#c-list').innerHTML = list.length ? list.map((c) => `
      <div class="list-item" data-id="${c.id}">
        <div class="grow">
          <div class="title">${esc(c.name)}</div>
          <div class="meta">${[c.phone, c.email, c.address].filter(Boolean).map(esc).join(' · ') || '—'}</div>
        </div>
        <div class="actions">
          <button class="btn small" data-act="edit">Editar</button>
          <button class="icon-btn danger" data-act="del" aria-label="Eliminar">✕</button>
        </div>
      </div>`).join('') : '<div class="empty">Sin clientes</div>';
  }
  function renderProducts() {
    const term = $('#p-search').value.trim().toLowerCase();
    const list = db.products.filter((p) => !term || `${p.name} ${p.desc}`.toLowerCase().includes(term));
    $('#p-count').textContent = `${db.products.length} en catálogo`;
    $('#p-list').innerHTML = list.length ? list.map((p) => `
      <div class="list-item" data-id="${p.id}">
        <div class="grow">
          <div class="title">${esc(p.name)}</div>
          <div class="meta">${esc(p.desc) || '—'}</div>
        </div>
        <div class="amount">${fmt(p.price)}</div>
        <div class="actions">
          <button class="btn small" data-act="edit">Editar</button>
          <button class="icon-btn danger" data-act="del" aria-label="Eliminar">✕</button>
        </div>
      </div>`).join('') : '<div class="empty">Sin productos</div>';
  }
  $('#c-search').addEventListener('input', renderClients);
  $('#p-search').addEventListener('input', renderProducts);

  // Al borrar del catálogo, el borrador conserva una copia para no perder la partida
  function snapshotDraftRefs() {
    const d = q();
    const c = db.clients.find((x) => x.id === d.clientId);
    if (c) d.client = { name: c.name, address: c.address, phone: c.phone, email: c.email };
    d.items.forEach((it) => {
      const p = db.products.find((x) => x.id === it.productId);
      if (p) Object.assign(it, { name: p.name, desc: p.desc, price: p.price });
    });
  }

  $('#c-list').addEventListener('click', (e) => {
    const btn = e.target.closest('[data-act]'); if (!btn) return;
    const id = Number(btn.closest('.list-item').dataset.id);
    const c = db.clients.find((x) => x.id === id);
    if (btn.dataset.act === 'edit') editClient(c);
    else if (confirm(`¿Eliminar a ${c.name}?`)) {
      snapshotDraftRefs();
      db.clients = db.clients.filter((x) => x.id !== id);
      save(); renderClients(); renderQuote();
    }
  });
  $('#p-list').addEventListener('click', (e) => {
    const btn = e.target.closest('[data-act]'); if (!btn) return;
    const id = Number(btn.closest('.list-item').dataset.id);
    const p = db.products.find((x) => x.id === id);
    if (btn.dataset.act === 'edit') editProduct(p);
    else if (confirm(`¿Eliminar ${p.name}?`)) {
      snapshotDraftRefs();
      db.products = db.products.filter((x) => x.id !== id);
      save(); renderProducts(); renderQuote();
    }
  });
  $('#btn-add-client').addEventListener('click', () => editClient(null));
  $('#btn-add-product').addEventListener('click', () => editProduct(null));

  // Modal de formulario genérico
  function formDialog(title, fields, values, onSave) {
    const dlg = $('#dlg-form');
    $('#dlg-form-title').textContent = title;
    $('#dlg-form-fields').innerHTML = fields.map((f) => `
      <label class="field ${f.span ? 'span-2' : ''}"><span>${f.label}</span>
        <input name="${f.name}" type="${f.type || 'text'}" ${f.required ? 'required' : ''} ${f.attrs || ''} value="${esc(values?.[f.name] ?? '')}">
      </label>`).join('');
    const form = $('#dlg-form-body');
    const handler = (e) => {
      if (!form.reportValidity()) { e.preventDefault(); return; }
      const data = Object.fromEntries(new FormData(form));
      Object.keys(data).forEach((k) => { data[k] = String(data[k]).trim(); });
      onSave(data);
    };
    form.onsubmit = handler;
    $('#dlg-form-cancel').onclick = () => dlg.close();
    dlg.showModal();
    setTimeout(() => $('input', dlg)?.focus(), 50);
  }

  function editClient(c, after) {
    formDialog(c ? 'Editar cliente' : 'Nuevo cliente', [
      { name: 'name', label: 'Nombre del cliente', required: true, span: true },
      { name: 'address', label: 'Dirección', span: true },
      { name: 'phone', label: 'Teléfono', type: 'tel', attrs: 'inputmode="tel"' },
      { name: 'email', label: 'Correo electrónico', type: 'email' },
    ], c, (data) => {
      let rec;
      if (c) { Object.assign(c, data); rec = c; }
      else { rec = { id: nextId(db.clients), ...data }; db.clients.push(rec); }
      save(); renderClients(); renderQuote();
      toast(c ? 'Cliente actualizado' : 'Cliente agregado');
      after?.(rec);
    });
  }

  function editProduct(p) {
    formDialog(p ? 'Editar producto' : 'Nuevo producto', [
      { name: 'name', label: 'Producto', required: true, span: true },
      { name: 'desc', label: 'Descripción', span: true },
      { name: 'price', label: 'Precio unitario', type: 'number', required: true, attrs: 'min="0" step="0.01" inputmode="decimal"' },
    ], p, (data) => {
      data.price = round2(data.price);
      if (p) Object.assign(p, data);
      else db.products.push({ id: nextId(db.products), ...data });
      save(); renderProducts(); renderQuote();
      toast(p ? 'Producto actualizado' : 'Producto agregado');
    });
  }

  // =====================================================================
  // AJUSTES y RESPALDO
  // =====================================================================
  $('#s-template').innerHTML = TEMPLATES.map((t) => `<option value="${t.id}">${t.name}</option>`).join('');
  function renderSettings() {
    const f = $('#settings-form');
    Object.entries(db.settings).forEach(([k, v]) => { if (f.elements[k]) f.elements[k].value = v; });
  }
  $('#settings-form').addEventListener('submit', (e) => {
    e.preventDefault();
    const data = Object.fromEntries(new FormData(e.target));
    Object.keys(data).forEach((k) => { data[k] = String(data[k]).trim(); });
    data.nextFolio = Math.max(1, parseInt(data.nextFolio, 10) || 1);
    data.iva = Math.max(0, Number(data.iva) || 0);
    db.settings = { ...db.settings, ...data };
    save(); renderQuote(); toast('Ajustes guardados');
  });

  $('#btn-backup').addEventListener('click', () => {
    const blob = new Blob([JSON.stringify(db, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `respaldo-cotizador-${today()}.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  });
  $('#file-restore').addEventListener('change', async (e) => {
    const file = e.target.files[0]; e.target.value = '';
    if (!file) return;
    try {
      const data = JSON.parse(await file.text());
      if (!Array.isArray(data.clients) || !Array.isArray(data.products) || !data.settings) throw new Error('formato');
      if (!confirm('Esto reemplazará todos los datos de tu cuenta. ¿Continuar?')) return;
      db = normalize(data);
      if (!db.draft) db.draft = blankDraft();
      editingQuoteId = null;
      await Cloud.replaceAll(mainPart(), db.quotes);
      renderSettings(); renderQuote(); toast('Respaldo restaurado');
    } catch (err) { toast(err.message === 'formato' || err instanceof SyntaxError ? 'Archivo de respaldo no válido' : Cloud.errorText(err)); }
  });

  // =====================================================================
  // PLANTILLAS DE EXPORTACIÓN
  // =====================================================================
  function renderDoc(quote, tpl) {
    const s = db.settings;
    const c = quote.client || {};
    const notes = s.notes ? `<div class="notes">${esc(s.notes)}</div>` : '';
    const ivaLbl = `IVA (${quote.ivaRate}%)`;
    const totalsBox = `
      <table class="totals-box">
        <tr><td>Subtotal</td><td class="r">${fmt(quote.subtotal)}</td></tr>
        <tr><td>${ivaLbl}</td><td class="r">${fmt(quote.iva)}</td></tr>
        <tr class="tt"><td>Total</td><td class="r">${fmt(quote.total)}</td></tr>
      </table>`;
    const rows = (withDesc = true) => quote.items.map((it) => `
      <tr>
        <td>${withDesc ? `<div class="pname">${esc(it.name)}</div>` : esc(it.name)}</td>
        <td>${withDesc ? `<div class="pdesc">${esc(it.desc)}</div>` : esc(it.desc)}</td>
        <td class="r">${fmt(it.price)}</td>
        <td class="c">${it.qty}</td>
        <td class="r">${fmt(it.price * it.qty)}</td>
      </tr>`).join('');
    const thead = `<thead><tr><th>Producto</th><th>Descripción</th><th class="r">Valor unitario</th><th class="c">Cantidad</th><th class="r">Total</th></tr></thead>`;

    if (tpl === 'clasica') return `
      <div class="doc tpl-clasica">
        <div class="head">
          <div>
            <div class="biz-name">${esc(s.name)}</div>
            <div class="biz-data">
              ${s.address ? `Dirección: ${esc(s.address)}<br>` : ''}
              ${s.phone ? `Teléfono: ${esc(s.phone)}<br>` : ''}
              ${s.email ? `Correo: ${esc(s.email)}` : ''}
            </div>
          </div>
          <table class="meta" style="width:auto">
            <tr><td>FECHA:</td><td>${esc(fmtDate(quote.date))}</td></tr>
            <tr><td># COTIZACIÓN:</td><td><b>${esc(quote.folio)}</b></td></tr>
          </table>
        </div>
        <table class="client">
          <tr><td>CLIENTE</td><td><b>${esc(c.name)}</b></td></tr>
          <tr><td>DIRECCIÓN</td><td>${esc(c.address) || '—'}</td></tr>
          <tr><td>TELÉFONO</td><td>${esc(c.phone) || '—'}</td></tr>
          <tr><td>CORREO</td><td>${esc(c.email) || '—'}</td></tr>
        </table>
        <table class="items">${thead}<tbody>${rows(false)}</tbody></table>
        ${totalsBox}
        ${notes}
        <div class="grow"></div>
        <div class="foot">Gracias por su preferencia · ${esc(s.name)}</div>
      </div>`;

    if (tpl === 'moderna') return `
      <div class="doc tpl-moderna">
        <div class="band">
          <div>
            <div class="biz-name">${esc(s.name)}</div>
            <div class="biz-data">${[s.address, s.phone, s.email].filter(Boolean).map(esc).join(' · ')}</div>
          </div>
          <div>
            <div class="q-label">Cotización</div>
            <div class="q-num">${esc(quote.folio)}</div>
            <div class="q-date">${esc(fmtDate(quote.date))}</div>
          </div>
        </div>
        <div class="body">
          <div class="client">
            <div class="full"><div class="k">Cliente</div><div class="v">${esc(c.name)}</div></div>
            <div><div class="k">Teléfono</div><div class="v">${esc(c.phone) || '—'}</div></div>
            <div><div class="k">Correo</div><div class="v">${esc(c.email) || '—'}</div></div>
            <div class="full"><div class="k">Dirección</div><div class="v" style="font-size:10pt;font-weight:400;color:inherit">${esc(c.address) || '—'}</div></div>
          </div>
          <table class="items">${thead}<tbody>${rows(true)}</tbody></table>
          ${totalsBox}
          ${notes}
          <div class="grow"></div>
          <div class="foot">Gracias por su preferencia</div>
        </div>
      </div>`;

    if (tpl === 'minimal') return `
      <div class="doc tpl-minimal">
        <div class="head">
          <div>
            <div class="biz-name">${esc(s.name)}</div>
            <div class="biz-data">${[s.address, s.phone, s.email].filter(Boolean).map(esc).join('<br>')}</div>
          </div>
          <div>
            <div class="title">Cotización</div>
            <div class="q-meta">No. ${esc(quote.folio)}<br>${esc(fmtDate(quote.date))}</div>
          </div>
        </div>
        <div class="rule"></div>
        <div class="k">Preparada para</div>
        <div class="client-name">${esc(c.name)}</div>
        <div style="font-size:9pt;color:#555">${[c.address, c.phone, c.email].filter(Boolean).map(esc).join(' · ')}</div>
        <div class="rule"></div>
        <table class="items">${thead}<tbody>${rows(false)}</tbody></table>
        ${totalsBox}
        ${notes}
        <div class="grow"></div>
        <div class="foot">${esc(s.name)}</div>
      </div>`;

    // Ticket
    return `
      <div class="doc tpl-ticket">
        <div class="c-block">
          <div class="biz-name">${esc(s.name)}</div>
          ${[s.address, s.phone, s.email].filter(Boolean).map((x) => `<div>${esc(x)}</div>`).join('')}
        </div>
        <div class="dash"></div>
        <div class="kv"><span>COTIZACIÓN</span><b>${esc(quote.folio)}</b></div>
        <div class="kv"><span>FECHA</span><span>${esc(fmtDate(quote.date))}</span></div>
        <div class="dash"></div>
        <div><b>${esc(c.name)}</b></div>
        ${c.address ? `<div>${esc(c.address)}</div>` : ''}
        ${c.phone ? `<div>Tel. ${esc(c.phone)}</div>` : ''}
        ${c.email ? `<div>${esc(c.email)}</div>` : ''}
        <div class="dash"></div>
        ${quote.items.map((it) => `
          <div class="it">
            <div class="n">${esc(it.name)}</div>
            ${it.desc ? `<div class="d">${esc(it.desc)}</div>` : ''}
            <div class="kv"><span>${it.qty} x ${fmt(it.price)}</span><b>${fmt(it.price * it.qty)}</b></div>
          </div>`).join('')}
        <div class="dash"></div>
        <div class="kv"><span>SUBTOTAL</span><span>${fmt(quote.subtotal)}</span></div>
        <div class="kv"><span>${ivaLbl}</span><span>${fmt(quote.iva)}</span></div>
        <div class="kv total"><span>TOTAL</span><span>${fmt(quote.total)}</span></div>
        <div class="dash"></div>
        ${notes}
        <div class="foot c-block">¡Gracias por su preferencia!</div>
      </div>`;
  }

  // ---------- Diálogo de exportación ----------
  let exportQuote = null;
  let exportTpl = 'clasica';

  function openExport(quote) {
    exportQuote = quote;
    exportTpl = db.settings.template || 'clasica';
    $('#tpl-picker').innerHTML = TEMPLATES.map((t) => `
      <button class="tpl-opt" role="radio" data-tpl="${t.id}" aria-checked="${t.id === exportTpl}">
        <span class="sw" style="background:${t.sw}"></span>
        <span><b>${t.name}</b><small>${t.hint}</small></span>
      </button>`).join('');
    $('#dlg-export').showModal();
    renderPreview();
  }

  function renderPreview() {
    $$('#tpl-picker .tpl-opt').forEach((b) => b.setAttribute('aria-checked', b.dataset.tpl === exportTpl));
    const inner = $('#preview-inner');
    inner.innerHTML = renderDoc(exportQuote, exportTpl);
    fitPreview();
  }

  function fitPreview() {
    const box = $('#preview'); const inner = $('#preview-inner'); const doc = $('.doc', inner);
    if (!doc || !box.clientWidth) return;
    inner.style.transform = 'none';
    const avail = box.clientWidth - 24;
    const w = doc.offsetWidth;
    const scale = Math.min(1, avail / w);
    inner.style.transform = `scale(${scale})`;
    inner.style.width = `${w}px`;
    inner.style.height = `${doc.offsetHeight * scale}px`;
    inner.style.marginLeft = `${Math.max(0, (avail - w * scale) / 2)}px`;
  }
  window.addEventListener('resize', () => { if ($('#dlg-export').open) fitPreview(); });

  $('#tpl-picker').addEventListener('click', (e) => {
    const b = e.target.closest('.tpl-opt'); if (!b) return;
    exportTpl = b.dataset.tpl;
    renderPreview();
  });
  $('#btn-close-export').addEventListener('click', () => $('#dlg-export').close());

  $('#btn-print').addEventListener('click', () => {
    $('#print-root').innerHTML = renderDoc(exportQuote, exportTpl);
    let page = '@page { size: A4; margin: 0; }';
    if (exportTpl === 'ticket') {
      // El ticket mide lo que mida su contenido (rollo de 80 mm)
      const root = $('#print-root');
      root.style.cssText = 'display:block;position:absolute;left:-9999px;top:0';
      const mm = Math.ceil($('.doc', root).offsetHeight * 25.4 / 96) + 2;
      root.style.cssText = '';
      page = `@page { size: 80mm ${mm}mm; margin: 0; }`;
    }
    $('#page-style').textContent = page;
    const prevTitle = document.title;
    // El título se usa como nombre sugerido del PDF
    document.title = `${exportQuote.folio} - ${exportQuote.client?.name || 'Cotizacion'}`;
    const restore = () => { document.title = prevTitle; window.removeEventListener('afterprint', restore); };
    window.addEventListener('afterprint', restore);
    window.print();
  });

  // =====================================================================
  // CUENTAS (entrar / crear cuenta / salir)
  // =====================================================================
  let authMode = 'login';
  function setAuthMode(mode) {
    authMode = mode;
    $$('#auth .auth-tab').forEach((t) => t.setAttribute('aria-selected', t.dataset.mode === mode));
    $('#auth-submit').textContent = mode === 'login' ? 'Entrar' : 'Crear cuenta';
    $('#auth-pass').autocomplete = mode === 'login' ? 'current-password' : 'new-password';
    $('#auth-forgot').hidden = mode !== 'login';
    authMsg('');
  }
  function authMsg(text, ok = false) {
    const m = $('#auth-msg');
    m.textContent = text;
    m.classList.toggle('ok', ok);
  }
  // state: 'loading' | 'form' | 'hidden'
  function showAuth(state) {
    document.body.classList.toggle('signed-out', state !== 'hidden');
    $('#auth').hidden = state === 'hidden';
    $('#auth-form').hidden = state !== 'form';
    $('#auth-loading').hidden = state !== 'loading';
  }
  $$('#auth .auth-tab').forEach((t) => t.addEventListener('click', () => setAuthMode(t.dataset.mode)));
  $('#auth-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = $('#auth-submit');
    btn.disabled = true; authMsg('');
    try {
      const email = $('#auth-email').value.trim();
      const pass = $('#auth-pass').value;
      if (authMode === 'login') await Cloud.signIn(email, pass);
      else await Cloud.signUp(email, pass);
    } catch (err) { authMsg(Cloud.errorText(err)); }
    btn.disabled = false;
  });
  $('#auth-forgot').addEventListener('click', async () => {
    const email = $('#auth-email').value.trim();
    if (!email) { authMsg('Escribe tu correo y vuelve a tocar “¿Olvidaste tu contraseña?”.'); return; }
    try { await Cloud.resetPassword(email); authMsg('Te enviamos un correo para cambiar tu contraseña.', true); }
    catch (err) { authMsg(Cloud.errorText(err)); }
  });
  $('#btn-logout').addEventListener('click', async () => {
    try { await Cloud.logOut(); } catch (err) { cloudErr(err); }
  });

  // Carga los datos de la cuenta; si es nueva, la crea con el catálogo de ejemplo
  // o con los datos que este navegador tenía de la versión sin cuentas.
  async function openAccount(user) {
    showAuth('loading');
    const { main, quotes } = await Cloud.loadAll();
    if (!main) {
      let legacy = null;
      try { legacy = JSON.parse(localStorage.getItem(LEGACY_KEY) || 'null'); } catch (e) { /* sin datos previos */ }
      const useLegacy = Boolean(legacy?.settings) && confirm('Encontramos datos del cotizador guardados en este dispositivo. ¿Quieres pasarlos a tu cuenta?');
      db = normalize(useLegacy ? legacy : SEED);
      if (!db.draft) db.draft = blankDraft();
      await Cloud.replaceAll(mainPart(), db.quotes);
      if (useLegacy) { try { localStorage.removeItem(LEGACY_KEY); } catch (e) { /* nada que borrar */ } }
    } else {
      db = normalize({ ...main, quotes });
      if (!db.draft) db.draft = blankDraft();
    }
    // Recupera la cotización en edición si el borrador ya tenía folio guardado
    editingQuoteId = db.draft.folio ? (db.quotes.find((x) => x.folio === db.draft.folio)?.id ?? null) : null;
    $('#account-email').textContent = user.email;
    renderQuote();
    showView('cotizar');
    showAuth('hidden');
  }

  // ---------- Inicio ----------
  setAuthMode('login');
  if (!Cloud.configured) {
    showAuth('loading');
    $('#auth-loading').hidden = true;
    authMsg('Falta configurar Firebase: pega tu configuración en firebase-config.js (ver README).');
    return;
  }
  showAuth('loading');
  Cloud.onUser(async (user) => {
    if (!user) {
      db = null;
      $('#auth-pass').value = '';
      showAuth('form');
      return;
    }
    try { await openAccount(user); }
    catch (err) {
      cloudErr(err);
      showAuth('form');
      authMsg(Cloud.errorText(err));
    }
  });
})();
