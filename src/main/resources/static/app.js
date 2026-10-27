// Interfaz de REDXela: consulta /api/state cada 500 ms y dibuja el estado del sistema.
const $ = id => document.getElementById(id);
const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

let catalog = null;
const resLabel = {};
let genRunning = false;
let lastDeadlockKey = '';

async function api(path, method = 'GET', body) {
  const r = await fetch('/api' + path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const data = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(data.error || r.statusText);
  return data;
}

let toastTimer;
function toast(msg, isErr) {
  const t = $('toast');
  t.textContent = msg;
  t.className = isErr ? 'err' : '';
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), 4000);
}

/** Ejecuta una acción de la API mostrando el error en un aviso en lugar de romper la página. */
async function act(fn, okMsg) {
  try {
    const r = await fn();
    if (okMsg) toast(typeof okMsg === 'function' ? okMsg(r) : okMsg);
  } catch (e) {
    toast(e.message, true);
  }
}

const lvlTag = (n, label) => `<span class="lvl lvl${n}" title="${esc(label || '')}">${n}</span>`;
const rl = id => resLabel[id] || id;
const chips = (ids, cls = '') => ids.map(i => `<span class="chip ${cls}">${esc(rl(i))}</span>`).join('');
const hue = id => `hsl(${(id * 47) % 360} 65% 52%)`;

// ------------------------------------------------------------------ inicialización

async function init() {
  catalog = await api('/catalog');
  catalog.resources.forEach(r => (resLabel[r.id] = r.label));

  $('policy').innerHTML = catalog.policies.map(p => `<option value="${p.id}">${esc(p.label)}</option>`).join('');
  $('arrivalProduct').innerHTML = catalog.products.map(p => `<option value="${p.id}">${esc(p.name)} (${esc(p.typeLabel)})</option>`).join('');
  $('custLevel').innerHTML = catalog.levels.map(l => `<option value="${l.level}">${l.level} - ${esc(l.label)} (máx. ${l.maxWaitMinutes} min)</option>`).join('');
  $('orderRows').innerHTML = [0, 1, 2].map(i => `
    <div class="orderrow">
      <select class="oprod">${i ? '<option value="">— sin producto —</option>' : ''}${catalog.products.map(p => `<option value="${p.id}">${esc(p.name)}</option>`).join('')}</select>
      <input class="oqty" type="number" min="1" value="${i ? '' : 10}" placeholder="cant.">
    </div>`).join('');

  $('types').innerHTML = '<tr><th>Tipo</th><th>Descripción</th><th>Recursos (orden natural de solicitud)</th><th>Productos</th></tr>' +
    catalog.types.map(t => `<tr><td><b>${esc(t.label)}</b></td><td>${esc(t.description)}</td>
      <td>${t.resources.map(r => `<span class="chip">${esc(rl(r))}</span>`).join(' → ')}</td>
      <td>${catalog.products.filter(p => p.type === t.id).map(p => esc(p.name)).join(', ')}</td></tr>`).join('');

  await loadCustomers();
  bindEvents();
  poll();
}

async function loadCustomers() {
  const list = await api('/customers');
  const sel = $('orderCustomer');
  const keep = sel.value;
  sel.innerHTML = list.map(c => `<option value="${c.id}">${esc(c.name)} — nivel ${levelNum(c.level)}</option>`).join('');
  if (keep) sel.value = keep;
}

function levelNum(name) {
  return { EXPRES: 1, PRIORITARIO: 2, ESTANDAR: 3, PROGRAMADO: 4, ECONOMICO: 5 }[name] || '?';
}

function bindEvents() {
  $('policy').onchange = e => act(() => api('/config', 'POST', { policy: e.target.value }));
  $('prevention').onchange = e => act(() => api('/config', 'POST', { prevention: e.target.checked }));

  $('genToggle').onclick = () => act(() => api('/generator', 'POST', {
    running: !genRunning,
    intervalMs: parseInt($('genInterval').value, 10) || 2500,
    autoArrivals: $('genArrivals').checked
  }));
  $('provoke').onclick = () => act(() => api('/deadlock/provoke', 'POST'),
    'Escenario lanzado: en unos segundos debe aparecer el deadlock (se detuvo el generador).');
  $('resetStats').onclick = () => act(() => api('/stats/reset', 'POST'), 'Estadísticas reiniciadas');

  $('orderForm').onsubmit = e => {
    e.preventDefault();
    const prods = [...document.querySelectorAll('.oprod')], qtys = [...document.querySelectorAll('.oqty')];
    const items = [];
    prods.forEach((p, i) => { if (p.value && qtys[i].value) items.push({ productId: +p.value, qty: +qtys[i].value }); });
    act(() => api('/orders', 'POST', { customerId: +$('orderCustomer').value, items }), r => 'Pedido ' + r.holder + ' creado');
  };
  $('arrivalForm').onsubmit = e => {
    e.preventDefault();
    act(() => api('/arrivals', 'POST', { productId: +$('arrivalProduct').value, qty: +$('arrivalQty').value }), 'Llegada registrada');
  };
  $('customerForm').onsubmit = e => {
    e.preventDefault();
    act(async () => {
      await api('/customers', 'POST', { name: $('custName').value, level: +$('custLevel').value });
      $('custName').value = '';
      await loadCustomers();
    }, 'Cuenta creada');
  };
}

// ------------------------------------------------------------------ refresco

async function poll() {
  try {
    render(await api('/state'));
    $('conn').textContent = 'conectado';
    $('conn').className = 'pill ok';
  } catch (e) {
    $('conn').textContent = 'sin conexión';
    $('conn').className = 'pill bad';
  }
  setTimeout(poll, 500);
}

function render(s) {
  const mm = s.config.minuteMs;
  renderControls(s);
  renderDeadlock(s.deadlock);
  renderResources(s.resources);
  renderProcessing(s.processing, s.config);
  renderWaiting(s);
  renderStats(s.stats);
  renderInventory(s.inventory);
  $('events').textContent = s.events.join('\n');
}

function renderControls(s) {
  genRunning = s.generator.running;
  $('genToggle').textContent = genRunning ? 'Detener generador' : 'Iniciar generador';
  $('genToggle').className = genRunning ? 'danger' : 'primary';
  if (document.activeElement !== $('policy')) $('policy').value = s.config.policy;
  if (document.activeElement !== $('prevention')) $('prevention').checked = s.config.prevention;
  const pol = catalog.policies.find(p => p.id === s.config.policy);
  $('policyDesc').textContent = pol ? pol.description : '';
}

function renderDeadlock(d) {
  const box = $('deadlockBox');
  box.hidden = !d;
  if (!d) { lastDeadlockKey = ''; return; }
  const key = d.participants.map(p => p.holder).join(',');
  $('deadlockWhy').textContent = d.explanation;
  $('deadlockTable').innerHTML = '<tr><th>Operación</th><th>Retiene</th><th>Espera</th><th>Bloqueada por</th></tr>' +
    d.participants.map(p => `<tr><td><b>${esc(p.holder)}</b></td><td>${chips(p.held)}</td>
      <td>${p.waitingFor ? chips([p.waitingFor], 'wait') : '-'}</td><td>${esc(p.blockedBy.join(', '))}</td></tr>`).join('');
  if (key !== lastDeadlockKey) { // no se recrean los botones en cada refresco
    lastDeadlockKey = key;
    $('deadlockButtons').innerHTML = d.participants.map(p =>
      `<button class="primary" data-w="${esc(p.holder)}">Que continúe ${esc(p.holder)}</button>`).join('');
    $('deadlockButtons').querySelectorAll('button').forEach(b => {
      b.onclick = () => act(async () => (await api('/deadlock/resolve', 'POST', { winner: b.dataset.w })).message, m => m);
    });
  }
}

function renderResources(list) {
  $('resources').innerHTML = list.map(r => {
    const pct = Math.round(100 * r.inUse / r.capacity);
    return `<div class="res-card">
      <b>${esc(r.label)}</b>
      <div class="bar ${r.available === 0 ? 'full' : ''}"><span style="width:${pct}%"></span></div>
      <div>${r.available === 0 ? '<span class="late">Agotado</span>' : 'Disponible'}: ${r.available}/${r.capacity} · ocupación media ${r.utilization}%</div>
      <div>${r.holders.length ? 'En uso por: ' + r.holders.map(h => `<span class="chip">${esc(h)}</span>`).join('') : '<span class="muted">libre</span>'}</div>
      ${r.waiters.length ? '<div>Esperando: ' + r.waiters.map(h => `<span class="chip wait">${esc(h)}</span>`).join('') + '</div>' : ''}
    </div>`;
  }).join('');
}

const STATUS = { ACQUIRING: 'Obteniendo recursos', PROCESSING: 'Procesando', WAITING: 'En espera', WAITING_STOCK: 'Sin existencias' };

function renderProcessing(list, cfg) {
  $('procCount').textContent = `(${list.length} de ${cfg.workers} hilos trabajadores)`;
  $('processing').innerHTML = '<tr><th>Pedido</th><th>Cliente</th><th>Nivel</th><th>Productos</th><th>Estado</th><th>Progreso</th><th>Tiempo restante</th><th>Recursos que retiene</th><th>Espera</th></tr>' +
    (list.length ? list.map(o => `<tr>
      <td><b>${esc(o.holder)}</b>${o.retries ? ` <span class="muted">(reintento ${o.retries})</span>` : ''}</td>
      <td>${esc(o.customer)}</td><td>${lvlTag(o.level, o.levelLabel)}</td>
      <td>${o.items.map(esc).join('<br>')}<br><span class="muted">${o.units} uds · ${o.types.map(esc).join(', ')}</span></td>
      <td>${STATUS[o.status] || o.status}</td>
      <td><progress max="100" value="${o.progress}"></progress> ${o.progress}%</td>
      <td>${o.etaMs >= 0 ? (o.etaMs / 1000).toFixed(1) + ' s' : '—'}</td>
      <td>${chips(o.held)}</td>
      <td>${o.waitingFor ? chips([o.waitingFor], 'wait') : ''}</td></tr>`).join('')
      : '<tr><td colspan="9" class="muted">Ningún pedido en proceso</td></tr>');
}

function renderWaiting(s) {
  const list = s.waiting;
  const pol = catalog.policies.find(p => p.id === s.config.policy);
  $('waitCount').textContent = `(${list.length})`;
  $('waitCriterion').textContent = 'Orden aplicado — ' + (pol ? pol.label + ': ' + pol.description : '');
  $('waiting').innerHTML = '<tr><th>#</th><th>Pedido</th><th>Cliente</th><th>Nivel</th><th>Unidades</th><th>Criterio</th><th>Esperó / objetivo (min sim)</th><th>Motivo</th></tr>' +
    list.slice(0, 200).map(o => `<tr>
      <td>${o.pos}</td><td><b>${esc(o.holder)}</b>${o.retries ? ` <span class="muted">(reintento ${o.retries})</span>` : ''}</td>
      <td>${esc(o.customer)}</td><td>${lvlTag(o.level, o.levelLabel)}</td><td>${o.units}</td>
      <td>${esc(o.sortKey)}</td>
      <td class="${o.late ? 'late' : ''}">${o.waitedMin} / ${o.targetMin}${o.late ? ' ⚠' : ''}</td>
      <td>${esc(o.reason)}</td></tr>`).join('') +
    (list.length > 200 ? `<tr><td colspan="8" class="muted">… y ${list.length - 200} más</td></tr>` : '') +
    (list.length ? '' : '<tr><td colspan="8" class="muted">Lista de espera vacía</td></tr>');
}

function renderStats(st) {
  $('statsGlobal').innerHTML = [
    ['Pedidos creados', st.created], ['Completados', st.completed], ['Fuera de objetivo', st.late],
    ['Espera media (min sim)', st.avgWaitMin], ['Procesamiento medio (min sim)', st.avgProcMin]
  ].map(([l, v]) => `<div class="kpi"><b>${v}</b><span>${l}</span></div>`).join('');
  const rows = (arr) => arr.map(r => `<tr><td>${esc(r.label)}</td><td>${r.count}</td><td>${r.avgWaitMin}</td><td>${r.avgProcMin}</td><td>${r.late}</td></tr>`).join('');
  const head = '<tr><th></th><th>Completados</th><th>Espera media</th><th>Proc. medio</th><th>Fuera de objetivo</th></tr>';
  $('statsPolicy').innerHTML = head + rows(st.byPolicy);
  $('statsLevel').innerHTML = head + rows(st.byLevel);
}

function renderInventory(inv) {
  $('mailbox').innerHTML = `
    <div class="kpis">
      <div class="kpi"><b>${inv.trucksWaiting}</b><span>camiones en recepción</span></div>
      <div class="kpi"><b>${inv.mailboxSize}/${inv.mailboxCapacity}</b><span>mensajes en el buzón</span></div>
      <div class="kpi"><b>${inv.messagesSent}</b><span>mensajes enviados</span></div>
    </div>
    <div class="bar ${inv.mailboxSize >= inv.mailboxCapacity ? 'full' : ''}"><span style="width:${100 * inv.mailboxSize / inv.mailboxCapacity}%"></span></div>
    <p class="muted">Si el buzón se llena, Recepción espera (contrapresión) y no se pierde ningún mensaje.</p>`;

  $('stock').innerHTML = '<tr><th>Producto</th><th>Tipo</th><th>Disponible</th><th>Reservado</th></tr>' +
    inv.products.map(p => `<tr><td><i class="slot" style="display:inline-block;width:10px;height:10px;background:${hue(p.id)}"></i> ${esc(p.name)}</td><td>${esc(p.type)}</td><td>${p.available}</td><td>${p.reserved}</td></tr>`).join('');

  const used = inv.totalSlots - inv.freeSlots;
  $('whCount').textContent = `(${used}/${inv.totalSlots} ubicaciones ocupadas)`;
  let html = '';
  const AISLES = 6, LEVELS = 4, POS = 5;
  for (let a = 0; a < AISLES; a++) {
    html += `<div class="aisle"><small>Pasillo ${a + 1}</small>`;
    for (let l = 0; l < LEVELS; l++) {
      html += '<div class="lv">';
      for (let p = 0; p < POS; p++) {
        const s = (a * LEVELS + l) * POS + p, prod = inv.slotProducts[s];
        const name = prod > 0 ? (catalog.products.find(x => x.id === prod) || {}).name : 'libre';
        html += `<div class="slot" title="P${a + 1}-N${l + 1}-${p + 1}: ${esc(name)}${prod > 0 ? ' (' + inv.slotUnits[s] + ' uds)' : ''}" style="${prod > 0 ? 'background:' + hue(prod) : ''}"></div>`;
      }
      html += '</div>';
    }
    html += '</div>';
  }
  $('warehouse').innerHTML = html;
  $('whLegend').innerHTML = catalog.products.map(p => `<span><i style="background:${hue(p.id)}"></i>${esc(p.name)}</span>`).join('');
  $('lots').innerHTML = '<tr><th>Lote</th><th>Producto</th><th>Unidades</th><th>Ubicaciones (pasillo-nivel-posición)</th></tr>' +
    inv.lots.map(l => `<tr><td>${l.lot}</td><td>${esc((catalog.products.find(x => x.id === l.productId) || {}).name || l.productId)}</td><td>${l.units}</td><td>${esc(l.locations.join(', '))}</td></tr>`).join('');
}

init().catch(e => toast('No se pudo iniciar la interfaz: ' + e.message, true));
