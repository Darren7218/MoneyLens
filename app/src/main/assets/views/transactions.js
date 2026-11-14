'use strict';
function renderTxRow(txRow){
  const c=cat(txRow.category),transfer=txRow.kind==='transfer',catLabel=tr(c[0])+(txRow.subcategory?' › '+txRow.subcategory:'');
  const pills=transfer
    ? `<span class="tx-pill">${esc(account(txRow.account).name)} → ${esc(account(txRow.to).name)}</span>`
    : `<span class="tx-pill">${esc(catLabel)}</span><span class="tx-pill">${esc(account(txRow.account).name)}</span>${txRow.autoRecorded?`<span class="tx-pill auto">${txRow.needsNote?'Auto · Add note':'Auto'}</span>`:''}${txRow.billId?`<span class="tx-pill">Linked · ${esc((state.bills.find(b=>b.id===txRow.billId)||{}).name||'Bill')}</span>`:''}${txRow.alsoSeenFrom?`<span class="tx-pill">Also from ${esc(txRow.alsoSeenFrom)}</span>`:''}`;
  const noteLine=txRow.note?`<p>${esc(txRow.note)}</p>`:'';
  return `<div class="tx-item"><button class="transaction" style="border-bottom:0;flex:1" data-action="detail" data-id="${esc(txRow.id)}"><div class="icon-bg ${c[2]}">${icon(transfer?'transfer':txRow.kind==='income'?'down':c[1])}</div><div class="grow"><h3>${esc(txRow.merchant||(transfer?'Account transfer':tr(txRow.category)))}</h3>${noteLine}<div class="tx-meta-row">${pills}</div></div><div class="trailing"><span class="amount money ${['income','refund'].includes(txRow.kind)?'positive':''}">${txRow.kind==='expense'?'−':transfer?'':'+'}${money(txRow.amount)}</span><small>${dateLabel(txRow.date)}</small></div></button><div class="tx-quick-actions"><button type="button" class="row-menu" data-action="quickNote" data-id="${esc(txRow.id)}" aria-label="Edit ${esc(txRow.merchant||txRow.category)}" title="Quick edit category / purpose">${icon('edit')}</button><button type="button" class="row-menu" data-action="quickRemove" data-id="${esc(txRow.id)}" aria-label="Remove ${esc(txRow.merchant||txRow.category)}" title="Remove transaction">✕</button></div></div>`;
}

function transactionList(rows,grouped=true){
  if(page==='transactions'){
    rows=rows.filter(txRow=>(!advancedFilters.exactDate||txRow.date===advancedFilters.exactDate)&&(!advancedFilters.category||txRow.category===advancedFilters.category)&&(!advancedFilters.needsNote||txRow.needsNote)&&(!advancedFilters.from||txRow.date>=advancedFilters.from)&&(!advancedFilters.until||txRow.date<=advancedFilters.until)&&(!advancedFilters.account||txRow.account===advancedFilters.account||txRow.to===advancedFilters.account)&&(!advancedFilters.min||txRow.amount>=Number(advancedFilters.min)*100)&&(!advancedFilters.max||txRow.amount<=Number(advancedFilters.max)*100)&&(!advancedFilters.source||(txRow.source||'manual')===advancedFilters.source)&&(!advancedFilters.tag||String(txRow.tags||'').toLowerCase().includes(advancedFilters.tag.toLowerCase())));
  }
  if(!rows.length)return empty('list',tr('Your story starts here'),tr('Coffee, groceries, a payday—add your first transaction to see it here.'),tr('Add transaction'),'add');
  if(!grouped)return rows.map(renderTxRow).join('');
  const byDate=new Map();
  for(const txRow of rows){
    if(!byDate.has(txRow.date))byDate.set(txRow.date,[]);
    byDate.get(txRow.date).push(txRow);
  }
  let out='';
  for(const [d,list] of byDate.entries()){
    const netDay=list.reduce((s,x)=>s+(x.kind==='income'||x.kind==='refund'?x.amount:x.kind==='expense'?-x.amount:0),0);
    out+=`<div class="date-group"><div class="date-group-head"><span>${esc(dayTitleFor(d))}</span><span class="money">${netDay<0?'−':netDay>0?'+':''}${money(netDay)}</span></div>${list.map(renderTxRow).join('')}</div>`;
  }
  return out;
}

function spendingCalendarCard(){
  const now=new Date();
  const first=new Date(now.getFullYear(),now.getMonth()+monthOffset,1),last=new Date(first.getFullYear(),first.getMonth()+1,0);
  const ymLabel=first.toLocaleDateString(appLocale(),{month:'long',year:'numeric'});
  const startDow=(first.getDay()+6)%7;
  const daysInMonth=last.getDate();
  const ymPrefix=`${first.getFullYear()}-${String(first.getMonth()+1).padStart(2,'0')}-`;
  const spendMap=new Map();
  let maxDay=0,monthTotal=0;
  for(const txRow of validTx()){
    if(txRow.kind==='expense'&&txRow.date.startsWith(ymPrefix)){
      const prev=spendMap.get(txRow.date)||0;
      const next=prev+txRow.amount;
      spendMap.set(txRow.date,next);
      monthTotal+=txRow.amount;
      if(next>maxDay)maxDay=next;
    }
  }
  let cells='';
  for(let i=0;i<startDow;i++)cells+=`<div class="cal-cell empty-cell"></div>`;
  for(let d=1;d<=daysInMonth;d++){
    const iso=`${ymPrefix}${String(d).padStart(2,'0')}`;
    const sp=spendMap.get(iso)||0;
    const ratio=maxDay>0?sp/maxDay:0;
    const lvl=sp===0?'':ratio>0.75?'lvl-4':ratio>0.45?'lvl-3':ratio>0.18?'lvl-2':'lvl-1';
    const sel=advancedFilters.exactDate===iso?'selected':'';
    const shortAmt=sp>0?`RM ${Math.round(sp/100)}`:'—';
    cells+=`<button type="button" class="cal-cell ${lvl} ${sel}" data-action="selectCalDay" data-date="${iso}" title="${iso}: ${money(sp)}"><span>${d}</span><small class="money">${shortAmt}</small></button>`;
  }
  const dows=currentLang()==='ms'?['Isn','Sel','Rab','Kha','Jum','Sab','Aha']:['Mon','Tue','Wed','Thu','Fri','Sat','Sun'];
  return `<details class="card" style="margin-bottom:14px" open><summary style="display:flex;justify-content:space-between;align-items:center"><span>${esc(tr('Spending Calendar Heatmap'))} · ${esc(ymLabel)}</span><span class="money muted" style="font-size:12px">${money(monthTotal)}</span></summary><div class="flex between" style="margin-top:8px"><span class="note">${esc(tr('Tap any day to filter records for that date.'))}</span>${cyclePicker()}</div><div class="cal-dow-row">${dows.map(d=>`<span>${d}</span>`).join('')}</div><div class="cal-grid">${cells}</div></details>`;
}

function filteredTransactions(){
  const q=query.toLowerCase();
  return validTx().filter(txRow=>txRow.kind!=='opening'&&(filter==='all'||txRow.kind===filter)&&(!q||[txRow.merchant,txRow.category,txRow.subcategory,account(txRow.account).name,txRow.note].join(' ').toLowerCase().includes(q))).sort((a,b)=>b.date.localeCompare(a.date)||(b.created||0)-(a.created||0));
}

function transactions(){
  const rows=filteredTransactions();
  const topCats=activeCategories().slice(0,8).map(c=>c[0]);
  const catChips=`<div class="cat-filter-strip"><button type="button" class="chip ${!advancedFilters.category&&!advancedFilters.needsNote?'active':''}" data-action="filterCat" data-cat="">${esc(tr('All Categories'))}</button><button type="button" class="chip ${advancedFilters.needsNote?'active':''}" data-action="filterNeedsNote">${esc(tr('Needs Purpose / Note'))}</button>${topCats.map(c=>`<button type="button" class="chip ${advancedFilters.category===c?'active':''}" data-action="filterCat" data-cat="${esc(c)}">${esc(tr(c))}</button>`).join('')}</div>`;
  const kindChips=[['all',tr('All')],['expense',tr('Expenses')],['income',tr('Income')],['transfer',tr('Transfers')],['refund',tr('Refunds')]].map(([id,l])=>`<button class="chip ${filter===id?'active':''}" data-filter="${id}">${esc(l)}</button>`).join('');
  return `<div class="tools-grid" style="margin-bottom:12px">${actionButton(tr('Filter records'),'filters')}${actionButton(tr('Import CSV'),'csvPick')}${actionButton(tr('Insights'),'insights')}</div>${spendingCalendarCard()}${catChips}${Object.keys(advancedFilters).length?`<p class="note" style="margin-bottom:10px">Active filter: ${esc(advancedFilters.exactDate||advancedFilters.category||(advancedFilters.needsNote?'Needs Note':'Custom'))} ${actionButton(tr('Clear'),'clearFilters','','ghost')}</p>`:''}<div class="search">${icon('search')}<input id="search" type="search" placeholder="${esc(tr('Search merchant, category, subcategory or note'))}" value="${esc(query)}" aria-label="Search transactions"></div><div class="flex between" style="margin-bottom:18px"><div class="chips">${kindChips}</div><button class="round" data-action="add" aria-label="${esc(tr('Add transaction'))}">${icon('plus')}</button></div><div id="tx-results"><section class="card">${transactionList(rows,true)}</section><p class="note" style="padding-top:12px">${rows.length} confirmed record${rows.length===1?'':'s'} · Grouped by day with daily net subtotals · Tap ✕ for 1-tap removal</p></div>`;
}

function inbox(){
  const autoOn=state.settings.autoRecord!==false;
  const autoRows=validTx().filter(txRow=>txRow.autoRecorded).sort((a,b)=>b.created-a.created||b.date.localeCompare(a.date)).slice(0,30);
  const rows=state.drafts.filter(d=>d.state==='review');
  const dupes=state.drafts.filter(d=>d.duplicateOf).slice(-10).reverse();
  const dupHtml=dupes.length?`<section class="card"><h2>${esc(tr('Merged duplicates'))}</h2><p class="note">These alerts had the same amount as a payment another app reported within 3 minutes, so they weren't recorded twice.</p>${dupes.map(d=>`<div class="plan-row"><div class="flex between">${brandBadge(d.source)}<span class="money">${money(d.amount)}</span></div><p class="note" style="margin-top:6px">${esc(d.merchant)} · ${dateLabel(d.date)}</p><div class="actions"><button class="btn ghost small" data-action="unmergeDuplicate" data-id="${esc(d.id)}">${esc(tr('Not a duplicate — record it'))}</button></div></div>`).join('')}</section>`:'';
  return `<div class="stack"><div class="tools-grid">${actionButton(tr('Scan receipt'),'scan')}${actionButton(tr('Paste text'),'paste')}${actionButton(tr('Send test notification'),'testNotification')}${actionButton(tr('Review together'),'batch')}</div><div class="hint" style="margin:0">${icon('shield')}${esc(autoOn?tr('Auto-record is active — detected payments are added to your balance immediately. Tap Remove on any item to undo in 1 tap.'):tr('Auto-record is paused — review suggestions below to record them.'))}</div><section class="card"><div class="section-head"><h2>${esc(tr('Recently Auto-Recorded Payments'))}</h2><button class="btn secondary small" data-action="testNotification">${icon('bell')}${esc(tr('Send test notification'))}</button></div>${autoRows.length?autoRows.map(txRow=>`<div class="plan-row"><div class="flex between">${brandBadge(txRow.source||'Auto-captured')}<span class="muted" style="font-size:11px">${dateLabel(txRow.date)} · ${esc(account(txRow.account).name)}</span></div><div class="flex between" style="margin-top:10px"><div><h3>${esc(txRow.merchant||tr(txRow.category))}</h3><div class="tx-meta-row"><span class="tx-pill">${esc(tr(txRow.category))}${txRow.subcategory?' › '+esc(txRow.subcategory):''}</span>${txRow.note?`<span class="tx-pill">${esc(txRow.note)}</span>`:txRow.needsNote?`<span class="tx-pill auto">Tap Edit to add purpose</span>`:''}</div></div><strong class="money" style="font-size:18px">${txRow.kind==='expense'?'−':''}${money(txRow.amount)}</strong></div><div class="actions" style="display:flex;gap:8px;margin-top:12px"><button class="btn secondary small" style="flex:1" data-action="quickNote" data-id="${esc(txRow.id)}">${icon('edit')}${esc(tr('Edit Category / Purpose'))}</button><button class="btn danger small" data-action="quickRemove" data-id="${esc(txRow.id)}">${icon('trash')}${esc(tr('Remove'))}</button>${txRow.billId?`<button class="btn ghost small" data-action="unlinkBill" data-id="${esc(txRow.id)}" title="Keep the payment but unlink it from ${esc((state.bills.find(b=>b.id===txRow.billId)||{}).name||'the bill')}">${esc(tr('Undo bill link'))}</button>`:''}</div></div>`).join(''):`<p class="note">No auto-recorded payments yet. Tap "Send test notification" above or make a payment with TNG eWallet / bank app to see it auto-record here.</p>`}</section>${rows.length?`<section class="card"><h2>${esc(tr('Unconfirmed items'))}</h2>${rows.slice().reverse().map(d=>`<div class="plan-row"><div class="flex between">${brandBadge(d.source)}<span class="muted" style="font-size:11px">${dateLabel(d.date)}</span></div><div class="draft-title money">${d.amount?money(d.amount):'Check amount'}</div><h3>${esc(d.merchant)}</h3><p class="note" style="margin-top:10px">${esc(d.reason)}</p><div class="actions"><button class="btn" data-action="review" data-id="${esc(d.id)}">${esc(tr('Review & record'))} ${icon('arrow')}</button><button class="btn ghost" data-action="ignore" data-id="${esc(d.id)}">${esc(tr('Ignore'))}</button>${actionButton(tr('Already recorded?'),'linkDraft',d.id,'ghost')}</div></div>`).join('')}</section>`:''}${dupHtml}<div class="flex"><button class="btn ghost full" data-action="sample">${esc(tr('Try a labelled sample'))}</button></div></div>`;
}
