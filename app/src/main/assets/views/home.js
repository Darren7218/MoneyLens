'use strict';
function forecastSparkline(daily=[]){
  if(!daily.length)return '';
  const vals=daily.map(d=>d.balance);
  const min=Math.min(0,...vals),max=Math.max(...vals,min+1);
  const span=Math.max(1,max-min),w=280,h=68,pad=6;
  const x=i=>pad+(i/Math.max(1,daily.length-1))*(w-pad*2);
  const y=v=>h-pad-((v-min)/span)*(h-pad*2);
  const pts=daily.map((d,i)=>`${x(i).toFixed(1)},${y(d.balance).toFixed(1)}`).join(' ');
  const zeroY=min<0&&max>0?`<line x1="${pad}" y1="${y(0).toFixed(1)}" x2="${w-pad}" y2="${y(0).toFixed(1)}" stroke="var(--danger)" stroke-dasharray="3 3" stroke-width="1"/>`:'';
  const stroke=min<0?'var(--danger)':'var(--accent)';
  return `<svg class="sparkline" viewBox="0 0 ${w} ${h}" role="img" aria-label="30-day balance trajectory" style="width:100%;height:72px;margin:8px 0">${zeroY}<polyline fill="none" stroke="${stroke}" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" points="${pts}"/></svg>`;
}

function forecastCard(r){
  if(!r)return '';
  const fc=r.forecast;
  const cand=(r.recurringIncome||[]).findIndex(x=>!x.isCurrentPayday);
  const payBanner=cand>=0?`<div class="soft-card" style="margin:0 0 10px"><div class="flex between"><div><strong>${esc(tr('Payday detected: {name}',{name:r.recurringIncome[cand].name}))}</strong><p class="note">${money(r.recurringIncome[cand].amount)} · ${esc(tr(r.recurringIncome[cand].frequency))} (${currentLang()==='ms'?'hari':'day'} ${r.recurringIncome[cand].dayOfMonth})</p></div>${actionButton(tr('Make this my payday'),'setPayday',String(cand))}</div></div>`:'';
  const hike=(r.priceHikes||[])[0];
  const hikeBanner=hike?`<div class="soft-card" style="margin:0 0 10px"><div class="flex between"><div><strong>${esc(tr('Price hike detected: {name}',{name:hike.merchant}))}</strong><p class="note">${money(hike.previousAvg)} → ${money(hike.latestAmount)} (+${hike.deltaPct}%)</p></div>${actionButton(tr('Review'),'openBnplPlan','')}</div></div>`:'';
  if(!fc)return payBanner+hikeBanner;
  const warn=fc.hasNegativeWarning?`<div class="hint" style="margin:0 0 10px">${icon('bell')}${esc(tr('Cash shortfall alert: projected balance hits'))} <strong>${signedMoney(fc.lowestBalance)}</strong> ${esc(tr('on {date}.',{date:dateLabel(fc.lowestDate)}))}</div>`:'';
  const evHtml=(fc.events||[]).length?`<details style="margin-top:8px"><summary>${esc(tr('Scheduled inflows & outflows ({n})',{n:fc.events.length}))}</summary>${fc.events.map(e=>`<div class="detail-line"><span>${dateLabel(e.date)} · ${esc(e.label)}${e.overdue?' (Overdue)':''}</span><span class="money">${e.kind==='inflow'?'+':'−'}${money(e.amount)} → ${signedMoney(e.balanceAfter)}</span></div>`).join('')}</details>`:`<p class="note">${esc(tr('No unpaid bills or configured payday scheduled in the next 30 days.'))}</p>`;
  return `<section class="card"><div class="section-head"><div><p class="eyebrow muted">${esc(tr('30-DAY CASH-FLOW FORECAST'))}</p><h2 class="money">${signedMoney(fc.endingBalance)} <small class="muted" style="font-size:12px;font-weight:500">${esc(tr('projected in 30 days'))}</small></h2></div>${fc.hasConfiguredPayday?actionButton(tr('Clear payday'),'clearPayday','','ghost'):''}</div>${payBanner}${hikeBanner}${warn}${forecastSparkline(fc.daily)}<div class="detail-line"><span>${esc(tr('Starting liquid cash'))}</span><strong class="money">${signedMoney(fc.startingBalance)}</strong></div><div class="detail-line"><span>${esc(tr('Expected inflows'))}</span><strong class="money">+${money(fc.totalInflows)}</strong></div><div class="detail-line"><span>${esc(tr('Scheduled bills'))}</span><strong class="money">−${money(fc.totalOutflows)}</strong></div><div class="detail-line"><span>${esc(tr('Lowest projected point ({date})',{date:dateLabel(fc.lowestDate)}))}</span><strong class="money">${signedMoney(fc.lowestBalance)}</strong></div>${evHtml}</section>`;
}

function sevenDayPulse(pacingArg){
  const p=pacingArg||reportData?.dailyPacing||computeLocalDailyPacing(state,reportData);
  const series=p?.dailySeries||[];
  const days=series.length?series:Array.from({length:7},(_,idx)=>{
    const i=6-idx,d=new Date();d.setDate(d.getDate()-i);
    const iso=`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
    const sp=validTx().filter(t=>t.date===iso&&t.kind==='expense').reduce((s,t)=>s+t.amount,0);
    return{date:iso,spent:sp,isToday:i===0};
  });
  const safe=Number(p?.safeDaily||0),avg=Number(p?.dailyAvg||0),todaySpent=Number(p?.todaySpent||days[days.length-1]?.spent||0);
  const maxVal=Math.max(1,safe,...days.map(x=>x.spent));
  const w=280,h=64,pad=6,gap=3,slot=(w-pad*2)/days.length,barW=Math.max(6,slot-gap);
  const bars=days.map((d,i)=>{
    const bh=d.spent>0?Math.max(4,Math.round((d.spent/maxVal)*(h-14))):3;
    const x=(pad+i*slot).toFixed(1),y=(h-4-bh).toFixed(1);
    const fill=d.spent>safe&&safe>0?'var(--red)':d.isToday?'var(--green)':'var(--lime)';
    return `<rect x="${x}" y="${y}" width="${barW.toFixed(1)}" height="${bh}" rx="3" fill="${fill}" opacity="${d.isToday?1:0.85}"><title>${esc(d.date)}: ${esc(money(d.spent))}</title></rect>`;
  }).join('');
  const safeY=safe>0?Math.max(6,Math.min(h-6,Math.round(h-4-(safe/maxVal)*(h-14)))):null;
  const safeLine=safeY!==null?`<line x1="${pad}" y1="${safeY}" x2="${w-pad}" y2="${safeY}" stroke="var(--muted)" stroke-width="1" stroke-dasharray="3,3" opacity="0.65"/>`:'';
  const st=p?.paceStatus||'good';
  const stLbl=st==='over'?tr('Over daily pace'):st==='watch'?tr('Watch pace'):tr('On pace');
  return `<div class="pacing-card"><div class="flex between"><span class="eyebrow muted">${esc(tr('SAFE TO SPEND TODAY'))}</span><span class="pace-chip ${esc(st)}">${esc(stLbl)}</span></div><div class="flex between" style="margin:4px 0 8px"><strong class="money" style="font-size:20px">${safe>0?money(p?.todayRemaining??safe):money(avg)}</strong><span class="muted" style="font-size:11px">${esc(tr('Today'))}: <span class="money">${money(todaySpent)}</span> · ${esc(tr('Avg'))}: <span class="money">${money(avg)}</span>/d</span></div><svg class="trend-svg" viewBox="0 0 ${w} ${h}" role="img" aria-label="${esc(tr('14-day daily spending trend'))}">${safeLine}${bars}</svg></div>`;
}

function spendingChart(){
  const pacing=reportData?.dailyPacing||computeLocalDailyPacing(state,reportData);
  let groups=categories.map(c=>({name:c[0],ico:c[1],tone:c[2],n:Math.max(0,spendFor(c[0]))})).filter(c=>c.n>0).sort((a,b)=>b.n-a.n),total=groups.reduce((s,g)=>s+g.n,0);
  if(!total)return sevenDayPulse(pacing)+empty('target',tr('Find your spending rhythm'),tr('Your category breakdown will appear after you record a purchase.'));
  let pos=0,gradient=groups.map((g,i)=>{let from=pos;pos+=g.n/total*360;return `${colors[i%colors.length]} ${from}deg ${pos}deg`}).join(',');
  const txCycle=currentTx().filter(t=>['expense','refund'].includes(t.kind));
  const threshold=state.settings.threshold||80;
  const budgetRows=reportData?.budgets||[];
  const deltas=pacing?.categoryDeltas||[];
  const breakdown=groups.slice(0,6).map((g,i)=>{
    const pct=Math.round(g.n/total*100);
    const cm=catMeta(g.name);
    const bRow=budgetRows.find(r=>r.category===g.name);
    const bPct=bRow&&bRow.available>0?Math.round(bRow.spent*100/bRow.available):0;
    const bAlert=bPct>100?`<span class="budget-alert-pill over">${bPct}% · ${esc(tr('Over limit'))}</span>`:bPct>=threshold?`<span class="budget-alert-pill warn">${bPct}% · ${esc(tr('Near limit'))}</span>`:'';
    const dRow=deltas.find(d=>d.category===g.name);
    const dBadge=(dRow&&dRow.previous>0&&dRow.delta!==0)?`<span class="delta-badge ${dRow.delta>0?'up':'down'}">${dRow.delta>0?'↑':'↓'} ${Math.abs(dRow.deltaPct)}%</span>`:'';
    const subMap=new Map();
    for(const t of txCycle){
      if(t.category===g.name){
        const sub=t.subcategory||'General';
        subMap.set(sub,(subMap.get(sub)||0)+(t.kind==='refund'?-t.amount:t.amount));
      }
    }
    const subRows=[...subMap.entries()].filter(x=>x[1]>0).sort((a,b)=>b[1]-a[1]);
    return `<details class="cat-breakdown-item" style="padding:10px 0;border-bottom:1px solid var(--line)"><summary style="list-style:none;cursor:pointer"><div class="cat-breakdown-head"><div class="cat-breakdown-title"><span class="cat-mini-pill tone-${cm.tone}">${cm.emoji}</span><span>${esc(tr(g.name))}</span>${bAlert}${dBadge}</div><span class="money" style="font-size:12px;font-weight:700">${money(g.n)} <span class="muted">(${pct}%)</span></span></div><div class="cat-bar"><span style="width:${Math.max(4,pct)}%;background:${colors[i%colors.length]}"></span></div></summary>${subRows.length?`<div style="padding:8px 0 2px 18px;display:grid;gap:4px">${subRows.map(([sName,sAmt])=>`<div class="flex between" style="font-size:11px"><span class="muted">↳ ${esc(sName)}</span><strong class="money">${money(sAmt)}</strong></div>`).join('')}</div>`:''}</details>`;
  }).join('');
  return `${sevenDayPulse(pacing)}<div class="donut-wrap"><div class="donut" style="background:conic-gradient(${gradient})"><div class="donut-label">${esc(tr('Total spent'))}<strong class="money">${money(total)}</strong></div></div><div class="legend">${groups.slice(0,4).map((g,i)=>`<div class="legend-row"><i class="dot" style="background:${colors[i%colors.length]}"></i><span>${esc(tr(g.name).split(' &')[0])}</span><strong>${Math.round(g.n/total*100)}%</strong></div>`).join('')}</div></div><div class="cat-breakdown-list">${breakdown}</div><div class="hint">${esc(tr('{cat} is your largest category this cycle. Tap any category bar above to inspect its subcategories.',{cat:tr(groups[0].name)}))}</div>`;
}

function smartInsightsStrip(t,r){
  const tx=currentTx(),autoCount=tx.filter(x=>x.autoRecorded).length;
  const taxClaimable=tx.filter(x=>x.kind==='expense'&&x.lhdnRelief).reduce((s,x)=>s+expense(x),0);
  const monthly=isMonthlyMode();
  const netFlow=t.income-t.spent,rate=t.income>0?Math.round(netFlow/t.income*100):0;
  const allowance=Number(r&&r.dailyAllowance||0),prevSpent=Number(r&&r.previousComparable||0);
  const flowCls=netFlow<0?'alert':'';
  const flowSub=t.income>0?tr('{pct}% savings rate',{pct:rate}):tr('Log income to track rate');
  const paceVal=allowance>0?money(allowance):money(Math.round(t.spent/Math.max(1,(new Date()).getDate())));
  const paceLbl=allowance>0?tr('Daily safe spend'):tr(monthly?'Monthly daily avg':'Cycle daily avg');
  const paceSub=prevSpent>0?`${t.spent<=prevSpent?'↓':'↑'} ${money(Math.abs(t.spent-prevSpent))} ${tr(monthly?'vs last month':'vs last cycle')}`:tr(monthly?'Tracked this month':'Tracked this cycle');
  const thr=state.settings.threshold||80;
  const alertHits=(r?.budgets||[]).filter(b=>b.available>0&&b.spent*100>=b.available*thr);
  const autoAlertChip=alertHits.length?`<button type="button" class="insight-chip warm actionable" data-action="catNotifLibrary"><div class="insight-lbl">${icon('bell')}<span>${esc(tr('Category alerts'))}</span></div><div class="insight-val money">${alertHits.length} ${esc(alertHits.some(b=>b.spent>b.available)?tr('over'):tr('near'))}</div><div class="insight-sub">${esc(tr(alertHits[0].category))} · ${esc(tr('Tap library'))}</div></button>`:`<button type="button" class="insight-chip actionable" data-action="catNotifLibrary"><div class="insight-lbl">${icon('shield')}<span>${esc(tr('Auto & Tax YTD'))}</span></div><div class="insight-val money">${autoCount} ${esc(tr('auto'))}</div><div class="insight-sub">${taxClaimable>0?`${esc(tr('LHDN'))}: ${money(taxClaimable)}`:esc(tr('Category alerts ready'))}</div></button>`;
  return `<section class="insights-grid" aria-label="Smart insights"><div class="insight-chip ${flowCls}"><div class="insight-lbl">${icon('target')}<span>${esc(tr(monthly?'Monthly cashflow':'Cycle cashflow'))}</span></div><div class="insight-val money">${netFlow>=0?'+':'−'}${money(Math.abs(netFlow))}</div><div class="insight-sub">${esc(flowSub)}</div></div><div class="insight-chip ${allowance>0?'':'warm'}"><div class="insight-lbl">${icon('bolt')}<span>${esc(paceLbl)}</span></div><div class="insight-val money">${paceVal}</div><div class="insight-sub">${esc(paceSub)}</div></div>${autoAlertChip}</section>`;
}

function home(){
  const t=totals(),r=reportData;
  const autoRows=validTx().filter(x=>x.autoRecorded).sort((a,b)=>b.created-a.created);
  const pendingCount=state.drafts.filter(d=>d.state==='review').length;
  const budgetsList=state.budgets.map(b=>({...b,limit:(r&&monthOffset===0)?(r.budgets?.find(x=>x.id===b.id)?.available??b.limit):b.limit}));
  const hasRoot=new Set(budgetsList.filter(b=>!b.subcategory).map(b=>b.category));
  const checkInBudgets=budgetsList.filter(b=>!b.subcategory||!hasRoot.has(b.category));
  const limit=checkInBudgets.reduce((s,b)=>s+b.limit,0);
  const spent=checkInBudgets.reduce((s,b)=>s+Math.max(0,spendFor(b.category,b.subcategory||'')),0);
  const pct=limit?Math.min(100,spent/limit*100):0;
  const heroDelta=t.income>0||t.spent>0?`<span class="hero-delta-pill ${t.income<t.spent?'warn':''}">${t.income>=t.spent?'+':'−'}${money(Math.abs(t.income-t.spent))}${t.income>0?` · ${Math.round((t.income-t.spent)/t.income*100)}%`:''}</span>`:'';
  const smartInsights=validTx().length?smartInsightsStrip(t,r):'';
  const quick=`<div class="action-dock" aria-label="Quick actions">${dockTile(tr('+ Expense'),'add','plus','sage')}${dockTile(tr('Scan Receipt'),'scan','scan','blue')}${dockTile(tr('Paste Alert'),'paste','paste','peach')}${dockTile(tr('Category Alerts'),'catNotifLibrary','bell','amber')}${dockTile(tr('BNPL & Due'),'openBnplPlan','calendar','purple')}${dockTile(tr('LHDN Tax'),'openTaxPlan','shield','sage')}</div>${state.favorites.length?`<div class="favorite-strip">${state.favorites.map(f=>actionButton(esc(f.name)+' · '+money(Math.round(Number(f.amount)*100)),'useFavorite',f.id,'ghost')).join('')}</div>`:''}`;
  const acctCarousel=`<div class="acct-strip" aria-label="Accounts quick strip">${state.accounts.map(a=>{let b=balance(a.id);return `<button type="button" class="acct-pill" data-action="accountDetail" data-id="${esc(a.id)}"><div class="icon-bg ${a.color||'sage'}">${icon(a.type==='cash'?'cash':a.type==='wallet'?'wallet':'bag')}</div><div><small>${esc(a.name)}</small><strong class="money">${a.type!=='card'&&b<0?'−':''}${money(b)}</strong></div></button>`}).join('')}<button type="button" class="acct-pill" data-action="addAccount"><div class="icon-bg">${icon('plus')}</div><div><small>${esc(tr('New'))}</small><strong>${esc(tr('Add account'))}</strong></div></button></div>`;
  const acctSubtitle=tr(state.accounts.length===1?'Across {n} account · includes card debt':'Across {n} accounts · includes card debt',{n:state.accounts.length});
  const bannerTitle=autoRows.length?tr(autoRows.length===1?'{n} auto-recorded payment':'{n} auto-recorded payments',{n:autoRows.length}):pendingCount?tr(pendingCount===1?'{n} payment in inbox':'{n} payments in inbox',{n:pendingCount}):tr('Auto-capture is active');
  const bannerSub=autoRows.length?tr('Already added to your balance. Tap to edit purpose or 1-tap remove.'):pendingCount?tr('Open Auto-Captured & Inbox to review or remove.'):tr('Notifications from TNG & bank apps record automatically.');
  const msList=(r?.months||[]);
  const mMax=Math.max(1,...msList.flatMap(m=>[m.income||0,m.spent||0]));
  const cashflowCard=msList.length>=2?`<section class="card"><div class="section-head"><div><p class="eyebrow muted">${esc(tr('6-MONTH CASHFLOW'))}</p><h3>${esc(tr('Income vs Spending'))}</h3></div><div class="flex" style="gap:10px;font-size:11px"><span class="legend-dot in"></span>${esc(tr('Income'))}<span class="legend-dot out"></span>${esc(tr('Spent'))}</div></div><svg class="cashflow-svg" viewBox="0 0 280 86" role="img" aria-label="${esc(tr('6-month income versus spending chart'))}">${msList.map((m,idx)=>{const slot=268/msList.length,x0=6+idx*slot,ih=m.income>0?Math.max(4,Math.round(m.income/mMax*56)):2,sh=m.spent>0?Math.max(4,Math.round(m.spent/mMax*56)):2,bw=Math.max(7,(slot-10)/2);return `<rect x="${x0.toFixed(1)}" y="${(64-ih).toFixed(1)}" width="${bw.toFixed(1)}" height="${ih}" rx="3" fill="var(--green)" opacity="0.88"><title>${esc(m.start)} Income: ${esc(money(m.income))}</title></rect><rect x="${(x0+bw+2).toFixed(1)}" y="${(64-sh).toFixed(1)}" width="${bw.toFixed(1)}" height="${sh}" rx="3" fill="var(--red)" opacity="0.78"><title>${esc(m.start)} Spent: ${esc(money(m.spent))}</title></rect><text x="${(x0+bw+1).toFixed(1)}" y="79" text-anchor="middle" font-size="9" fill="var(--muted)">${esc(m.start.slice(5,7))}</text>`;}).join('')}</svg></section>`:'';
  const insightGrid=r?`<div class="insight-grid"><section class="card"><p class="eyebrow muted">${esc(tr('AVAILABLE ESTIMATE'))}</p><h2 class="money metric">${money(r.availableToSpend)}</h2><p class="note">${esc(tr('After card debt, upcoming bills and earmarked goals.'))}</p>${actionButton(tr('See calculation'),'insights')}</section><section class="card"><p class="eyebrow muted">${esc(tr('DAILY BUDGET ALLOWANCE'))}</p><h2 class="money metric">${money(r.dailyAllowance)}</h2><p class="note">${esc(tr('Remaining category budgets divided by days left in this cycle.'))}</p><span class="tag">${esc(tr('{amt} change vs same elapsed days',{amt:signedMoney(r.spent-r.previousComparable)}))}</span></section></div>${cashflowCard}${forecastCard(r)}`:'';
  const splitOwed=Number(r?.sharedSummary?.totalOwed||r?.owed||0);
  const splitBanner=splitOwed>0?`<div class="inbox-banner"><div class="icon-bg peach">${icon('wallet')}</div><div class="grow"><h3>${esc(tr('Split bills owed: {amt}',{amt:money(splitOwed)}))}</h3><p>${esc(tr('{n} friend(s) · Tap to send DuitNow summary or settle up',{n:r?.sharedSummary?.friendCount||r?.receivables?.length||1}))}</p></div>${actionButton(tr('Split Tabs'),'openSplitsPlan')}</div>`:'';
  const cycleEndWin=r?.recap?.isCycleEndWindow&&monthOffset===0;
  const cycleEndBanner=cycleEndWin?`<div class="cycle-end-banner"><div class="icon-bg sage">${icon('target')}</div><div class="grow"><h3>${esc(tr('Cycle Review Ready'))}</h3><p>${esc(tr('{n} day(s) left before payday cycle resets · Net {amt}',{n:r.recap.daysLeft||0,amt:signedMoney(r.recap.netSaved||0)}))}</p></div>${actionButton(tr('Cycle Review'),'openCycleReview')}</div>`:'';
  const bn=r?.backupNudge||computeLocalBackupNudge(state,status);
  const backupNudgeCard=bn?.shouldShow?`<section class="backup-nudge-card" aria-label="${esc(tr('Encrypted backup reminder'))}"><div class="flex" style="align-items:flex-start;gap:12px"><div class="icon-bg amber">${icon('shield')}</div><div class="grow"><h3>${esc(bn.hasBackup?tr('Time to refresh your encrypted backup'):tr('Protect {n} transactions with an encrypted backup',{n:bn.activeTxCount}))}</h3><p class="note">${esc(tr('100% offline · Save a password-encrypted archive or local snapshot anytime (optional).'))}</p></div></div><div class="tools-grid" style="margin-top:10px">${actionButton(tr('Export backup'),'backup','','primary')}${actionButton(tr('Local snapshot'),'snapshotNow','','secondary')}${actionButton(tr('Remind in 7d'),'snoozeBackupNudge','','ghost')}${actionButton(tr('Dismiss'),'dismissBackupNudge','','ghost')}</div></section>`:'';
  const monthly=isMonthlyMode();
  return `<div class="stack">${quick}${!validTx().length?`<div class="welcome"><h2>${esc(tr('A fresh start for your finances.'))}</h2><p>${esc(tr('Add an account with its opening balance, then record your first transaction. Everything stays on this device.'))}</p><div class="flex"><button class="btn small" data-action="addAccount">${icon('plus')}${esc(tr('Add account'))}</button><button class="text-btn" data-action="add">${esc(tr('Record an expense'))} ${icon('arrow')}</button></div></div>`:''}<div class="section-head" style="margin:0"><div class="privacy-pill"><span class="status-dot"></span>${esc(tr('On-device ledger'))}</div>${cyclePicker()}</div><div class="overview-grid"><section class="card hero"><div class="flex between"><span class="eyebrow">${esc(tr('RECORDED NET BALANCE'))}</span><div class="hero-actions-row">${heroDelta}<button type="button" class="hero-pill-btn" data-action="toggleBlur">${icon(state.settings.privacyBlur?'eyeOff':'eye')}${esc(tr(state.settings.privacyBlur?'Show':'Privacy'))}</button></div></div><div class="balance money"><span>RM</span>${t.net<0?'−':''}${(Math.abs(t.net)/100).toLocaleString(appLocale(),{minimumFractionDigits:2,maximumFractionDigits:2})}</div><small>${esc(acctSubtitle)}</small><div class="hero-foot"><div class="hero-stat-box"><div class="label">${icon('down')}${esc(tr(monthly?'Income this month':'Income this cycle'))}</div><strong class="money">${money(t.income)}</strong></div><div class="hero-stat-box"><div class="label">${icon('up')}${esc(tr(monthly?'Spent this month':'Spent this cycle'))}</div><strong class="money">${t.spent<0?'−':''}${money(t.spent)}</strong></div></div></section><section class="card"><div class="section-head"><h3>${esc(tr('Budget check-in'))}</h3><button class="text-btn" data-page="plan">${esc(tr('Manage'))}</button></div><div class="metric-line"><strong class="money">${money(Math.max(0,limit-spent))}</strong><span class="muted" style="font-size:11px">${esc(tr(limit?'left to spend':'set a budget'))}</span></div><div class="progress ${spent>limit&&limit?'over':''}"><span style="width:${pct}%"></span></div><div class="budget-foot"><span>${esc(tr('{amt} used',{amt:money(spent)}))}</span><span>${esc(tr('of {amt}',{amt:money(limit)}))}</span></div><div class="hint">${icon('target')}${esc(limit?(spent>limit?tr('You are over your category budgets. Review the details in Plan.'):tr('{pct}% of your category budgets is still available.',{pct:Math.round(100-pct)})):tr('Give your spending a little direction. Start with a budget for food or shopping.'))}</div></section></div>${smartInsights}${acctCarousel}${cycleEndBanner}${backupNudgeCard}${splitBanner}<div class="inbox-banner"><div class="icon-bg">${icon('inbox')}</div><div class="grow"><h3>${esc(bannerTitle)}</h3><p>${esc(bannerSub)}</p></div><button class="round" data-page="${autoRows.length||pendingCount?'inbox':'settings'}" aria-label="Open Auto-Captured & Inbox">${icon('arrow')}</button></div><div class="main-grid"><section class="card"><div class="section-head"><h2>${esc(tr('Recent activity'))}</h2><button class="text-btn" data-page="transactions">${esc(tr('View all'))}</button></div>${transactionList(validTx().filter(x=>x.kind!=='opening').sort((a,b)=>b.date.localeCompare(a.date)||b.created-a.created).slice(0,6),false)}</section><section class="card"><div class="section-head"><h2>${esc(tr('Where it went'))}</h2><span class="tag">${esc(tr(monthly?'This month':'This cycle'))}</span></div>${spendingChart()}</section></div><button class="btn full" data-action="add">${icon('plus')}${esc(tr('Add transaction'))}</button><p class="note" style="text-align:center">${esc(tr('Balances reflect your records. Check them against your actual accounts.'))}</p>${insightGrid}</div>`;
}

function accounts(){
  const net=totals().net;
  const unverified=state.accounts.filter(a=>!a.balanceVerified).length;
  const acctSub=tr(state.accounts.length===1?'Across {n} account':'Across {n} accounts',{n:state.accounts.length});
  return `<div class="stack"><section class="card hero"><div class="flex between"><span class="eyebrow">${esc(tr('RECORDED NET BALANCE'))}</span><button type="button" class="hero-pill-btn" data-action="toggleBlur">${icon(state.settings.privacyBlur?'eyeOff':'eye')}${esc(tr(state.settings.privacyBlur?'Show':'Privacy'))}</button></div><div class="balance money"><span>RM</span>${net<0?'−':''}${(Math.abs(net)/100).toLocaleString(appLocale(),{minimumFractionDigits:2,maximumFractionDigits:2})}</div><small>${esc(acctSub)}</small></section><div class="section-head" style="margin-bottom:0"><h2>${esc(tr('Your accounts'))}</h2><button class="text-btn" data-action="addAccount">${icon('plus')}${esc(tr('Add account'))}</button></div><div class="account-grid">${state.accounts.map(a=>{let b=balance(a.id);return `<button class="account-card" data-action="accountDetail" data-id="${esc(a.id)}"><div class="flex between"><div class="icon-bg ${a.color||'sage'}">${icon(a.type==='cash'?'cash':a.type==='wallet'?'wallet':'bag')}</div><span class="tag">${esc(a.type)}</span></div><h3>${esc(a.name)}</h3><strong class="money">${a.type!=='card'&&b<0?'−':''}${money(b)}</strong><small>${esc(tr(a.type==='card'?(b<0?'Amount owed':'Credit balance'):'Recorded balance'))}</small></button>`}).join('')}</div><button class="btn secondary" data-action="transfer">${icon('transfer')}${esc(tr('Transfer between accounts'))}</button><div class="hint">${icon('shield')}${esc(tr('These balances come from your opening balances and saved transactions. Compare them with your bank or wallet regularly.'))}</div><p class="note">${esc(tr('{n} account(s) have not had an explicit opening balance or statement check. Balances may be incomplete.',{n:unverified}))}</p><section class="card section-space"><div class="section-head"><h2>${esc(tr('Check & settle'))}</h2><div class="flex">${actionButton(tr('Split Tabs'),'openSplitsPlan','','ghost')}${actionButton(tr('Reconcile an account'),'reconcile')}</div></div>${reportData?.receivables.length?reportData.receivables.map(r=>`<div class="setting-row"><div class="grow"><h3>${esc(r.person)}</h3><p>${esc(r.merchant)} · ${esc(tr('{amt} still owed',{amt:money(r.remaining)}))}</p></div>${actionButton(tr('Record repayment'),'repay',r.id)}</div>`).join(''):`<p class="note">${esc(tr('Shared expenses and repayments appear here.'))}</p>`}<div class="tools-grid">${actionButton(tr('Shared expense'),'shared')}${actionButton(tr('Installment purchase'),'installment')}</div><details><summary>${esc(tr('Reconciliation history'))}</summary>${(state.reconciliations||[]).slice(-10).reverse().map(r=>`<p class="note">${esc(r.date)} · ${esc(account(r.account).name)} · ${esc(tr('difference'))} ${signedMoney(r.difference)}${r.adjusted?' · '+esc(tr('adjusted')):''}</p>`).join('')||`<p class="note">${esc(tr('No balance checks yet.'))}</p>`}</details></section></div>`;
}
