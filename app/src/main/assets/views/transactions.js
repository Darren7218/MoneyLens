'use strict';
function renderTxRow(txRow){
  const c=cat(txRow.category),cm=catMeta(txRow.category),transfer=txRow.kind==='transfer',catLabel=tr(c[0])+(txRow.subcategory?' › '+txRow.subcategory:'');
  const fxPill=(!transfer&&txRow.originalCurrency&&txRow.originalAmount)?`<span class="tx-pill fx">${esc(txRow.originalCurrency)} ${esc(txRow.originalAmount)}${txRow.exchangeRate&&Number(txRow.exchangeRate)>0?` · @${esc(Number(txRow.exchangeRate).toFixed(2))}`:''}</span>`:'';
  const origTx=txRow.originalId?state.transactions.find(x=>x.id===txRow.originalId):null;
  const refundPill=txRow.originalId?`<span class="tx-pill refund-link">↩ ${esc(tr('Linked refund'))}${origTx?.merchant?' · '+esc(origTx.merchant):''}</span>`:'';
  const splitPill=txRow.kind==='reimbursement'?`<span class="tx-pill split-link">🤝 ${esc(tr(txRow.autoReconciledSplit?'Auto-matched split':'Split repayment'))}${txRow.matchedPerson?' · '+esc(txRow.matchedPerson):''}</span>`:txRow.kind==='shared'?`<span class="tx-pill split-link">🤝 ${esc(tr('Split'))}${txRow.person?' · '+esc(txRow.person):''}</span>`:'';
  const anomalyPill=(txRow.anomaly&&!txRow.anomalyDismissed)?`<span class="tx-pill anomaly-warn">⚠ ${esc(txRow.anomalyRatio||'High')} ${esc(tr('usual'))}</span>`:'';
  const semPill=(query.trim()&&searchState.query===query.trim()&&searchState.matches?.[txRow.id]==='semantic')?`<span class="tx-pill semantic-hit">✦ ${esc(tr('Semantic'))}</span>`:'';
  const pills=transfer
    ? `<span class="tx-pill">${esc(account(txRow.account).name)} → ${esc(account(txRow.to).name)}</span>${semPill}`
    : `<span class="tx-pill cat-tint tone-${cm.tone}">${cm.emoji} ${esc(catLabel)}</span><span class="tx-pill">${esc(account(txRow.account).name)}</span>${fxPill}${refundPill}${splitPill}${anomalyPill}${semPill}${txRow.autoRecorded?`<span class="tx-pill auto">${txRow.needsNote?'Auto · Add note':'Auto'}</span>`:''}${txRow.billId?`<span class="tx-pill">Linked · ${esc((state.bills.find(b=>b.id===txRow.billId)||{}).name||'Bill')}</span>`:''}${txRow.alsoSeenFrom?`<span class="tx-pill">Also from ${esc(txRow.alsoSeenFrom)}</span>`:''}`;
  const noteLine=txRow.note?`<p>${esc(txRow.note)}</p>`:'';
  const badgeIcon=transfer?`<div class="icon-bg ${c[2]}">${icon('transfer')}</div>`:`<div class="icon-bg cat-ico-badge tone-${cm.tone}">${txRow.kind==='income'?icon('down'):`<span class="cat-emoji">${cm.emoji}</span>`}</div>`;
  const inflow=['income','refund','reimbursement'].includes(txRow.kind),outflow=['expense','shared'].includes(txRow.kind);
  return `<div class="tx-item"><button class="transaction" style="border-bottom:0;flex:1" data-action="detail" data-id="${esc(txRow.id)}">${badgeIcon}<div class="grow"><h3>${esc(txRow.merchant||(transfer?'Account transfer':tr(txRow.category)))}</h3>${noteLine}<div class="tx-meta-row">${pills}</div></div><div class="trailing"><span class="amount money ${inflow?'positive':''}">${outflow?'−':transfer?'':'+'}${money(txRow.amount)}</span><small>${dateLabel(txRow.date)}</small></div></button><div class="tx-quick-actions"><button type="button" class="row-menu" data-action="quickNote" data-id="${esc(txRow.id)}" aria-label="Edit ${esc(txRow.merchant||txRow.category)}" title="Quick edit category / purpose">${icon('edit')}</button><button type="button" class="row-menu" data-action="quickRemove" data-id="${esc(txRow.id)}" aria-label="Remove ${esc(txRow.merchant||txRow.category)}" title="Remove transaction">✕</button></div></div>`;
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
    const netDay=list.reduce((s,x)=>s+(['income','refund','reimbursement'].includes(x.kind)?x.amount:['expense','shared'].includes(x.kind)?-x.amount:0),0);
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
    if((txRow.kind==='expense'||txRow.kind==='shared')&&txRow.date.startsWith(ymPrefix)){
      const spAmt=expense(txRow);
      const prev=spendMap.get(txRow.date)||0;
      const next=prev+spAmt;
      spendMap.set(txRow.date,next);
      monthTotal+=spAmt;
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
  return `<details class="card" style="margin-bottom:14px" open><summary style="display:flex;justify-content:space-between;align-items:center"><span>${esc(tr('Spending Calendar Heatmap'))} · ${esc(ymLabel)}</span><span class="money muted" style="font-size:12px">${money(monthTotal)}</span></summary><div class="flex between" style="margin-top:8px;flex-wrap:wrap;gap:8px"><span class="note">${esc(tr('Tap any day to filter records for that date.'))}</span><div style="flex-shrink:0;white-space:nowrap">${cyclePicker()}</div></div><div class="cal-dow-row">${dows.map(d=>`<span>${d}</span>`).join('')}</div><div class="cal-grid">${cells}</div></details>`;
}

function filteredTransactions(){
  const qRaw=query.trim(),q=qRaw.toLowerCase();
  if(qRaw&&searchState.query===qRaw&&Array.isArray(searchState.ids)){
    const byId=new Map(validTx().map(t=>[t.id,t]));
    const out=[];
    for(const tid of searchState.ids){const row=byId.get(tid);if(row)out.push(row);}
    return out;
  }
  return validTx().filter(txRow=>{
    if(txRow.kind==='opening')return false;
    if(filter==='splits'){if(!['reimbursement','shared'].includes(txRow.kind))return false;}
    else if(filter==='anomaly'){if(!(txRow.anomaly&&!txRow.anomalyDismissed))return false;}
    else if(filter!=='all'&&txRow.kind!==filter)return false;
    return !q||[txRow.merchant,txRow.category,txRow.subcategory,account(txRow.account).name,txRow.note,txRow.person].join(' ').toLowerCase().includes(q);
  }).sort((a,b)=>b.date.localeCompare(a.date)||(b.created||0)-(a.created||0));
}

function renderSearchChips(){
  const qRaw=query.trim();
  if(!qRaw||searchState.query!==qRaw||!searchState.parsedFilters)return '';
  const pf=searchState.parsedFilters,chips=[];
  if(pf.minMinor>=0&&pf.maxMinor>=0)chips.push(`RM ${(pf.minMinor/100).toFixed(0)}–${(pf.maxMinor/100).toFixed(0)}`);
  else if(pf.minMinor>=0)chips.push(`≥ RM ${(pf.minMinor/100).toFixed(2).replace(/\.00$/,'')}`);
  else if(pf.maxMinor>=0)chips.push(`≤ RM ${(pf.maxMinor/100).toFixed(2).replace(/\.00$/,'')}`);
  if(pf.fromDate||pf.untilDate)chips.push(`${pf.fromDate||''}${pf.untilDate&&pf.untilDate!==pf.fromDate?' → '+pf.untilDate:''}`);
  if(pf.accountHint)chips.push(`Acct: ${pf.accountHint}`);
  if(pf.hardKind||pf.kindBoost)chips.push(tr(pf.hardKind||pf.kindBoost));
  if(pf.taxReliefQuery)chips.push(tr('Tax relief'));
  if(searchState.semanticUsed)chips.push(`✦ ${tr('Offline semantic search')}`);
  if(!chips.length)return '';
  return `<div class="search-filter-chips">${chips.map(c=>`<span class="tx-pill semantic-hit">${esc(c)}</span>`).join('')}</div>`;
}

function renderTxResults(rows){
  let outSum=0,inSum=0,refSum=0;
  for(const r of rows){
    if(r.kind==='expense'||r.kind==='shared')outSum+=expense(r);
    else if(r.kind==='income'||r.kind==='reimbursement')inSum+=r.amount||0;
    else if(r.kind==='refund')refSum+=r.amount||0;
  }
  const summaryBar=`<div class="tx-summary-bar"><span><strong>${rows.length}</strong> ${esc(tr(rows.length===1?'record':'records'))}</span><span>${esc(tr('Out'))}: <strong class="money">${money(outSum)}</strong></span><span>${esc(tr('In'))}: <strong class="money">${money(inSum+refSum)}</strong></span></div>`;
  return `${renderSearchChips()}${summaryBar}<section class="card">${transactionList(rows,true)}</section><p class="note" style="padding-top:12px">${esc(tr(rows.length===1?'{n} confirmed record · Grouped by day with daily net subtotals · Tap ✕ for 1-tap removal':'{n} confirmed records · Grouped by day with daily net subtotals · Tap ✕ for 1-tap removal',{n:rows.length}))}</p>`;
}

function transactions(){
  const rows=filteredTransactions();
  const catCounts=new Map();let needNoteCnt=0;
  for(const txRow of rows){catCounts.set(txRow.category,(catCounts.get(txRow.category)||0)+1);if(txRow.needsNote)needNoteCnt++;}
  const topCats=activeCategories().slice(0,10).map(c=>c[0]);
  const catChips=`<div class="cat-filter-strip"><button type="button" class="chip ${!advancedFilters.category&&!advancedFilters.needsNote?'active':''}" data-action="filterCat" data-cat="">${esc(tr('All Categories'))} <span class="chip-count">${rows.length}</span></button><button type="button" class="chip ${advancedFilters.needsNote?'active':''}" data-action="filterNeedsNote">${esc(tr('Needs Purpose / Note'))}${needNoteCnt?` <span class="chip-count">${needNoteCnt}</span>`:''}</button>${topCats.map(c=>{const cnt=catCounts.get(c)||0;const cm=catMeta(c);return`<button type="button" class="chip ${advancedFilters.category===c?'active':''}" data-action="filterCat" data-cat="${esc(c)}">${cm.emoji} ${esc(tr(c))}${cnt?` <span class="chip-count">${cnt}</span>`:''}</button>`;}).join('')}</div>`;
  const kindChips=[['all',tr('All')],['expense',tr('Expenses')],['income',tr('Income')],['transfer',tr('Transfers')],['refund',tr('Refunds')],['splits',tr('Splits')],['anomaly',tr('Anomalies')]].map(([id,l])=>`<button class="chip ${filter===id?'active':''}" data-filter="${id}">${esc(l)}</button>`).join('');
  return `<div class="tools-grid" style="margin-bottom:12px">${actionButton(tr('Filter records'),'filters')}${actionButton(tr('Import CSV'),'csvPick')}${actionButton(tr('Insights'),'insights')}</div>${spendingCalendarCard()}${catChips}${Object.keys(advancedFilters).length?`<p class="note" style="margin-bottom:10px">${esc(tr('Active filter'))}: ${esc(advancedFilters.exactDate||tr(advancedFilters.category)||(advancedFilters.needsNote?tr('Needs Note'):tr('Custom')))} ${actionButton(tr('Clear'),'clearFilters','','ghost')}</p>`:''}<div class="search">${icon('search')}<input id="search" type="search" placeholder="${esc(tr('Search merchant, concept (kopi, 咖啡, tax relief) or >50'))}" value="${esc(query)}" aria-label="Search transactions"></div><div class="flex between" style="margin-bottom:18px"><div class="chips tx-kind-chips" style="flex:1;min-width:0">${kindChips}</div><button class="round desktop-add" data-action="add" aria-label="${esc(tr('Add transaction'))}">${icon('plus')}</button></div><div id="tx-results">${renderTxResults(rows)}</div>`;
}

function inbox(){
  const autoOn=state.settings.autoRecord!==false;
  const autoRows=validTx().filter(txRow=>txRow.autoRecorded).sort((a,b)=>b.created-a.created||b.date.localeCompare(a.date)).slice(0,30);
  const rows=state.drafts.filter(d=>d.state==='review');
  const dupes=state.drafts.filter(d=>d.duplicateOf).slice(-10).reverse();
  const dupHtml=dupes.length?`<section class="card"><h2>${esc(tr('Merged duplicates'))}</h2><p class="note">${esc(tr("These alerts had the same amount as a payment another app reported within 3 minutes, so they weren't recorded twice."))}</p>${dupes.map(d=>`<div class="plan-row"><div class="flex between">${brandBadge(d.source)}<span class="money">${money(d.amount)}</span></div><p class="note" style="margin-top:6px">${esc(d.merchant)} · ${dateLabel(d.date)}</p>${d.reason?`<p class="note" style="margin-top:4px;font-size:11px">${esc(d.reason)}</p>`:''}<div class="actions"><button class="btn ghost small" data-action="unmergeDuplicate" data-id="${esc(d.id)}">${esc(tr('Not a duplicate — record it'))}</button></div></div>`).join('')}</section>`:'';
  return `<div class="stack"><div class="tools-grid">${actionButton(tr('Scan receipt'),'scan')}${actionButton(tr('Paste text'),'paste')}${actionButton(tr('Category Alerts & Rules'),'catNotifLibrary')}${actionButton(tr('Review together'),'batch')}</div><div class="hint" style="margin:0">${icon('shield')}${esc(autoOn?tr('Auto-record is active — detected payments are added to your balance immediately. Tap Remove on any item to undo in 1 tap.'):tr('Auto-record is paused — review suggestions below to record them.'))}</div><section class="card"><div class="section-head"><h2>${esc(tr('Recently Auto-Recorded Payments'))}</h2><button class="btn secondary small" data-action="testNotification">${icon('bell')}${esc(tr('Send test notification'))}</button></div>${autoRows.length?autoRows.map(txRow=>{const cm=catMeta(txRow.category),origTx=txRow.originalId?state.transactions.find(x=>x.id===txRow.originalId):null;return`<div class="plan-row"><div class="flex between">${brandBadge(txRow.source||'Auto-captured')}<span class="muted" style="font-size:11px">${dateLabel(txRow.date)} · ${esc(account(txRow.account).name)}</span></div><div class="flex between" style="margin-top:10px"><div><h3>${esc(txRow.merchant||tr(txRow.category))}</h3><div class="tx-meta-row"><span class="tx-pill cat-tint tone-${cm.tone}">${cm.emoji} ${esc(tr(txRow.category))}${txRow.subcategory?' › '+esc(txRow.subcategory):''}</span>${txRow.originalId?`<span class="tx-pill refund-link">↩ ${esc(tr('Linked refund'))}${origTx?.merchant?' · '+esc(origTx.merchant):''}</span>`:''}${txRow.kind==='reimbursement'?`<span class="tx-pill split-link">🤝 ${esc(tr(txRow.autoReconciledSplit?'Auto-matched split':'Split repayment'))}</span>`:''}${(txRow.anomaly&&!txRow.anomalyDismissed)?`<span class="tx-pill anomaly-warn">⚠ ${esc(txRow.anomalyRatio||'High')} ${esc(tr('usual'))}</span>`:''}${txRow.alsoSeenFrom?`<span class="tx-pill">Also from ${esc(txRow.alsoSeenFrom)}</span>`:''}${txRow.note?`<span class="tx-pill">${esc(txRow.note)}</span>`:txRow.needsNote?`<span class="tx-pill auto">${esc(tr('Tap Edit to add purpose'))}</span>`:''}</div></div><strong class="money" style="font-size:18px">${['expense','shared'].includes(txRow.kind)?'−':''}${money(txRow.amount)}</strong></div><div class="actions" style="display:flex;gap:8px;margin-top:12px"><button class="btn secondary small" style="flex:1" data-action="quickNote" data-id="${esc(txRow.id)}">${icon('edit')}${esc(tr('Edit Category / Purpose'))}</button><button class="btn danger small" data-action="quickRemove" data-id="${esc(txRow.id)}">${icon('trash')}${esc(tr('Remove'))}</button>${(txRow.anomaly&&!txRow.anomalyDismissed)?`<button class="btn ghost small" data-action="dismissAnomaly" data-id="${esc(txRow.id)}">${esc(tr('Dismiss alert'))}</button>`:''}${txRow.billId?`<button class="btn ghost small" data-action="unlinkBill" data-id="${esc(txRow.id)}" title="Keep the payment but unlink it from ${esc((state.bills.find(b=>b.id===txRow.billId)||{}).name||'the bill')}">${esc(tr('Undo bill link'))}</button>`:''}</div></div>`;}).join(''):`<p class="note">${esc(tr('No auto-recorded payments yet. Tap "Send test notification" above or make a payment with TNG eWallet / bank app to see it auto-record here.'))}</p>`}</section>${rows.length?`<section class="card"><h2>${esc(tr('Unconfirmed items'))}</h2>${rows.slice().reverse().map(d=>`<div class="plan-row"><div class="flex between">${brandBadge(d.source)}<span class="muted" style="font-size:11px">${dateLabel(d.date)}</span></div><div class="draft-title money">${d.amount?money(d.amount):esc(tr('Check amount'))}</div><h3>${esc(d.merchant)}</h3>${d.alsoSeenFrom?`<div class="tx-meta-row"><span class="tx-pill">Also from ${esc(d.alsoSeenFrom)}</span></div>`:''}<p class="note" style="margin-top:10px">${esc(d.reason)}</p><div class="actions"><button class="btn" data-action="review" data-id="${esc(d.id)}">${esc(tr('Review & record'))} ${icon('arrow')}</button><button class="btn ghost" data-action="ignore" data-id="${esc(d.id)}">${esc(tr('Ignore'))}</button>${actionButton(tr('Already recorded?'),'linkDraft',d.id,'ghost')}</div></div>`).join('')}</section>`:''}${dupHtml}<div class="flex"><button class="btn ghost full" data-action="sample">${esc(tr('Try a labelled sample'))}</button></div></div>`;
}
