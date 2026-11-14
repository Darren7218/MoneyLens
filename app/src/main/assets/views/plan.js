'use strict';
function plan(){
  const tabs=[['budgets',tr('Budgets')],['bills',tr('BNPL & Bills')],['debts',tr('Debts')],['goals',tr('Goals')],['tax',tr('LHDN Tax')]];
  const body=planTab==='budgets'?budgets():planTab==='bills'?bills():planTab==='debts'?debtView():planTab==='tax'?taxView():goals();
  return `<div class="stack"><div class="segmented">${tabs.map(([id,lbl])=>`<button class="${planTab===id?'active':''}" data-plan="${id}">${esc(lbl)}</button>`).join('')}</div>${body}</div>`;
}

function budgets(){
  const list=reportData?.budgets||state.budgets.map(b=>{
    const sp=spendFor(b.category,b.subcategory||'');
    return {...b,available:b.limit,spent:sp,carry:0,remaining:b.limit-sp,pacePct:50,spentPct:b.limit?Math.round(sp/b.limit*100):0,paceStatus:sp>b.limit?'over':'on_track'};
  });
  return `<section class="card"><div class="section-head"><h2>${esc(tr('Room to spend'))}</h2>${actionButton(tr('Add budget'),'addBudget')}</div><p class="note">${currentLang()==='ms'?`Kitaran semasa · hari ${state.settings.cycleDay||1}. Penanda menunjukkan kemajuan hari dalam kitaran.`:`Current cycle · day ${state.settings.cycleDay||1}. Tick marks show how far into the cycle you are.`}</p>${list.map(b=>{
    const paceTag=b.paceStatus==='over'
      ? `<span class="tag pace-over">${esc(tr('Over budget'))}</span>`
      : b.paceStatus==='fast'
        ? `<span class="tag pace-fast">${esc(tr('Ahead of pace'))} (${b.spentPct}% at ${b.pacePct}% cycle)</span>`
        : `<span class="tag">${esc(tr('On pace'))} (${b.pacePct??50}% cycle)</span>`;
    return `<div class="plan-row"><div class="flex between"><div><h3>${esc(tr(b.category))}${b.subcategory?` <span class="tx-pill">${esc(b.subcategory)}</span>`:''}</h3><p>${esc(tr('{amt} used',{amt:money(b.spent)}))} · ${esc(tr('{amt} available',{amt:money(b.available)}))}</p></div>${actionButton(tr('Edit'),'editBudget',b.id)}</div><div class="progress ${b.remaining<0?'over':''}"><span style="width:${Math.max(0,Math.min(100,b.spent/Math.max(1,b.available)*100))}%"></span><i class="pace-tick" style="left:${Math.max(2,Math.min(98,b.pacePct??50))}%" title="${esc(tr('Expected cycle pace ({pct}%)',{pct:b.pacePct??50}))}"></i></div><div class="budget-foot"><span>${b.carry?esc(tr('{amt} carried forward',{amt:money(b.carry)})):esc(tr('Base {amt}',{amt:money(b.limit)}))} · ${paceTag}</span><span>${esc(tr('{amt} left',{amt:signedMoney(b.remaining)}))}</span></div></div>`;
  }).join('')||empty('target',tr('Give your money a plan'),tr('Start with your everyday categories or a specific subcategory.'),tr('Add budget'),'addBudget')}</section>`;
}

function freqLabel(b){
  const f=b.frequency||(b.monthly?'monthly':'once');
  if(f==='everyXDays')return currentLang()==='ms'?`setiap ${b.intervalDays||30} hari`:`every ${b.intervalDays||30} days`;
  if(currentLang()==='ms')return ({monthly:'bulanan',weekly:'mingguan',daily:'harian',yearly:'tahunan',once:'sekali'})[f]||f;
  return f;
}

function daysUntilDue(dateStr){
  const a=new Date(today()+'T00:00:00'),b=new Date(dateStr+'T00:00:00');
  return Math.round((b-a)/86400000);
}

function bills(){
  const rows=state.bills.filter(b=>!b.paid&&!b.cancelled).sort((a,b)=>a.date.localeCompare(b.date));
  const schedActive=state.settings.alwaysOnScheduler!==false;
  return `<section class="card"><div class="section-head"><h2>${esc(tr('BNPL, PTPTN & Bill Scheduler'))}</h2>${actionButton(tr('＋ Custom Reminder'),'addBill')}</div><div class="hint" style="margin-top:0">${icon('bell')}${esc(tr(schedActive?'Always-On Task Scheduler is active — reminders fire at your configured timeslot before due date and auto-restart on phone reboot.':'Task scheduler is paused. Enable Always-On Task Scheduler in Settings.'))}</div><p class="note" style="margin:10px 0 8px"><strong>${esc(tr('1-Tap Quick Templates (Pay Before Due Date):'))}</strong></p><div class="tools-grid" style="margin-bottom:14px">${actionButton(tr('＋ PTPTN Loan'),'presetReminder','ptptn')}${actionButton('＋ SPayLater','presetReminder','spaylater')}${actionButton('＋ GrabPayLater','presetReminder','grabpaylater')}${actionButton('＋ Atome BNPL','presetReminder','atome')}${actionButton('＋ Boost PayFlex','presetReminder','payflex')}${actionButton('＋ LazPayLater','presetReminder','lazpaylater')}${actionButton(tr('＋ Credit Card'),'presetReminder','creditcard')}${actionButton(tr('Test Alert Now'),'testReminder','')}</div>${rows.map(b=>{
    const rType=b.reminderType||'bill';
    const badge=tr(rType==='bnpl'?'BNPL':rType==='ptptn'?'PTPTN':rType==='credit'?'Credit Card':'Bill');
    const diff=daysUntilDue(b.date);
    const overdue=diff<0;
    const urgClass=overdue?'overdue':diff<=3?'soon':'ok';
    const urgText=overdue?tr('OVERDUE · {n}d ago',{n:Math.abs(diff)}):diff===0?tr('Due Today · {time}',{time:b.remindTime||'09:00'}):tr('Due in {n}d · {time}',{n:diff,time:b.remindTime||'09:00'});
    return `<div class="bnpl-card type-${esc(rType)}"><div class="flex between"><span class="tag">${esc(badge)} · ${esc(freqLabel(b))}</span><span class="urgency-pill ${urgClass}">${esc(urgText)}</span></div><div class="flex between" style="margin-top:10px"><div><h3>${esc(b.name)}</h3><p>${esc(tr('Due {date} · Remind {d}d before at {time}',{date:b.date,d:b.remindDaysBefore??3,time:b.remindTime||'09:00'}))}</p><div class="tx-meta-row"><span class="tx-pill">${esc(tr(b.category))}${b.subcategory?' › '+esc(b.subcategory):''}</span>${b.variable?`<span class="tx-pill">${esc(tr('Variable'))}</span>`:''}</div>${b.cancelDate?`<p>${esc(tr('Cancel by {date}',{date:b.cancelDate}))}</p>`:''}</div><strong class="money" style="font-size:18px">${money(b.amount)}</strong></div><div class="tools-grid" style="margin-top:12px">${actionButton(tr('Record payment'),'payBill',b.id)}${actionButton(tr('Test alert'),'testReminder',b.id,'ghost')}${actionButton(tr('Edit'),'editBill',b.id,'ghost')}${actionButton(tr('Remove'),'deleteBillExtra',b.id,'danger')}</div></div>`;
  }).join('')||empty('plan',tr('Never miss a BNPL or PTPTN due date'),tr('Tap a template above (PTPTN, SPayLater, GrabPayLater, Atome) or add a custom schedule to get notified before your due date.'),tr('Add reminder'),'addBill')}</section>${reportData?.priceHikes?.length?`<section class="card"><div class="section-head"><h2>${esc(tr('Subscription & Bill Price-Hike Detector'))}</h2><span class="tag pace-over">${reportData.priceHikes.length}</span></div><p class="note">${esc(tr('Recurring bills or subscriptions whose latest charge increased compared to your prior payments.'))}</p>${reportData.priceHikes.map((h,i)=>`<div class="plan-row"><div class="flex between"><div><h3>${esc(h.merchant)}</h3><p>${money(h.previousAvg)} → <strong class="money">${money(h.latestAmount)}</strong> · <span class="tag pace-over">+${money(h.deltaAmount)} (+${h.deltaPct}%)</span></p></div>${actionButton(tr(h.billId?'Update bill':'Track bill'),'updateBillHike',String(i))}</div></div>`).join('')}</section>`:''}${reportData?.subscriptions.length?`<section class="card"><h2>${esc(tr('Possible subscriptions'))}</h2><p class="note">${esc(tr('Three similar, regularly spaced payments. Confirm before creating a plan.'))}</p>${reportData.subscriptions.map((s,i)=>`<div class="setting-row"><div class="grow"><h3>${esc(s.name)}</h3><p>${money(s.amount)} · ${esc(tr(s.frequency))}</p></div>${actionButton(tr('Review'),'subscription',String(i))}</div>`).join('')}</section>`:''}${state.bills.some(b=>b.cancelled)?`<details class="card"><summary>${esc(tr('Paused plans'))}</summary>${state.bills.filter(b=>b.cancelled).map(b=>`<div class="setting-row"><span class="grow">${esc(b.name)}</span>${actionButton(tr('Edit'),'editBill',b.id)}</div>`).join('')}</details>`:''}`;
}

function goals(){
  return `<section class="card"><div class="section-head"><h2>${esc(tr('Something to look forward to'))}</h2><button class="round" data-action="addGoal" aria-label="${esc(tr('Add savings goal'))}">${icon('plus')}</button></div>${state.goals.length?state.goals.map(g=>{
    const rem=Math.max(0,g.target-g.saved);
    const pct=g.target>0?Math.min(100,g.saved/g.target*100):0;
    let paceHtml='';
    if(g.targetDate){
      const daysLeft=Math.max(1,Math.round((new Date(g.targetDate+'T00:00:00')-new Date(today()+'T00:00:00'))/86400000));
      const moNeeded=Math.round(rem/Math.max(1,daysLeft/30.4375));
      paceHtml=`<div class="tx-meta-row" style="margin-top:6px"><span class="tx-pill">${esc(tr('Target'))}: ${dateLabel(g.targetDate)} · ${rem>0?esc(tr('{amt}/mo needed',{amt:money(moNeeded)})):esc(tr('Goal reached!'))}</span></div>`;
    }
    return `<div class="plan-row goal-pot"><div class="goal-pot-top"><div><h3>${esc(g.name)}</h3><p>${esc(tr('{saved} saved of {target}',{saved:money(g.saved),target:money(g.target)}))}</p>${paceHtml}</div><div class="flex"><span class="goal-pot-pill money">${esc(tr('{pct}% there',{pct:Math.round(pct)}))}</span><button class="row-menu" data-action="editGoal" data-id="${esc(g.id)}" aria-label="${esc(tr('Edit goal'))}">${icon('edit')}</button></div></div><div class="goal-pot-track"><div class="goal-pot-fill" style="width:${pct}%"></div></div><div class="budget-foot"><span>${esc(tr('{pct}% there',{pct:Math.round(pct)}))}</span><span>${esc(tr('{amt} to go',{amt:money(rem)}))}</span></div><div class="tools-grid" style="margin-top:4px"><button type="button" class="btn secondary small" data-action="goalDelta" data-id="${esc(g.id)}" data-delta="5000">+ RM 50</button><button type="button" class="btn secondary small" data-action="goalDelta" data-id="${esc(g.id)}" data-delta="10000">+ RM 100</button>${g.saved>0?`<button type="button" class="btn ghost small" data-action="goalDelta" data-id="${esc(g.id)}" data-delta="-5000">− RM 50</button>`:''}</div></div>`;
  }).join(''):empty('target',tr('Make space for your next thing'),tr('A new PC, a trip, an emergency fund. Give your savings a purpose.'),tr('Create a goal'),'addGoal')}</section><p class="note">${esc(tr('Goals are virtual earmarks. Updating saved progress does not move money or create transactions.'))}</p>`;
}

function debtView(){
  const dp=reportData?.debtPlan;
  const rows=dp?.debts||(state.debts||[]);
  const extra=dp?.extraMonthly??(state.settings?.debtExtraMonthly||0);
  const strat=debtStrategy==='snowball'?'snowball':'avalanche';
  const av=dp?.avalanche||{months:0,payoffDate:today(),totalInterest:0,schedule:[]};
  const sb=dp?.snowball||{months:0,payoffDate:today(),totalInterest:0,schedule:[]};
  const pl=strat==='snowball'?sb:av;
  const totalBal=dp?.totalBalance??rows.reduce((s,d)=>s+(d.balance||0),0);
  const totalMin=dp?.totalMinPayment??rows.reduce((s,d)=>s+(d.balance>0?(d.minPayment||0):0),0);
  const typeTag=t=>t==='ptptn'?'PTPTN':t==='bnpl'?'BNPL':t==='credit'?tr('Card'):tr('Loan');
  const rateTag=d=>!d.aprBps?'0% APR':`${(d.aprBps/100).toFixed(2)}% ${d.rateType==='flat'?tr('Flat'):'APR'}`;
  return `<div class="stack"><section class="card hero"><div class="flex between"><span class="eyebrow">${esc(tr('DEBT PAYOFF PLANNER'))} (${strat.toUpperCase()})</span>${actionButton(tr('＋ Add Debt'),'addDebt','','secondary')}</div><div class="balance money"><span>RM</span>${(totalBal/100).toLocaleString(appLocale(),{minimumFractionDigits:2,maximumFractionDigits:2})}</div><small>${pl.months?esc(tr('Debt-free in {m} mo · Est. {date}',{m:pl.months,date:dateLabel(pl.payoffDate)})):esc(tr('Add PTPTN, BNPL or card balances to map your debt-free date.'))}</small><div class="hero-foot"><div class="hero-stat-box"><div class="label">${esc(tr('Monthly Pool'))}</div><strong class="money">${money(totalMin+extra)}</strong></div><div class="hero-stat-box"><div class="label">${esc(tr('Total Interest'))}</div><strong class="money">${money(pl.totalInterest||0)}</strong></div></div></section><section class="card"><div class="section-head"><h2>${esc(tr('Strategy & Extra Boost'))}</h2></div><div class="segmented" style="margin-bottom:10px"><button type="button" class="${strat==='avalanche'?'active':''}" data-action="debtStrategy" data-id="avalanche">${esc(tr('Avalanche (Lowest Interest)'))}</button><button type="button" class="${strat==='snowball'?'active':''}" data-action="debtStrategy" data-id="snowball">${esc(tr('Snowball (Quickest Wins)'))}</button></div>${dp?.activeCount>0?`<div class="soft-card" style="margin:0 0 12px"><div class="detail-line"><span>${esc(tr('Avalanche payoff'))}</span><strong>${av.months} mo (${dateLabel(av.payoffDate)}) · ${money(av.totalInterest)} int.</strong></div><div class="detail-line"><span>${esc(tr('Snowball payoff'))}</span><strong>${sb.months} mo (${dateLabel(sb.payoffDate)}) · ${money(sb.totalInterest)} int.</strong></div><p class="note" style="margin-top:6px">${dp.interestSavedByAvalanche>0?esc(tr('Avalanche saves {amt} in total interest.',{amt:money(dp.interestSavedByAvalanche)})):esc(tr('Both strategies cost the same interest for these debts — choose Snowball for faster early wins!'))}</p></div>`:''}<form data-extra="debtExtra"><div class="form-grid" style="align-items:end">${field(tr('Extra monthly payment (RM)'),'extraMonthly',amount(extra),'text','required inputmode="decimal" placeholder="0.00"')}<button type="submit" class="btn secondary">${esc(tr('Update payoff plan'))}</button></div></form><p class="note" style="margin:12px 0 8px"><strong>${esc(tr('Quick Presets:'))}</strong></p><div class="tools-grid">${actionButton('＋ PTPTN 1% Ujrah','presetDebt','ptptn')}${actionButton('＋ SPayLater 0%','presetDebt','spaylater')}${actionButton(tr('＋ Credit Card 18%'),'presetDebt','creditcard')}${actionButton(tr('＋ Custom Debt'),'addDebt')}</div></section><section class="card"><div class="section-head"><h2>${esc(tr('Your Debts'))} (${rows.length})</h2></div>${pl.stalled?`<div class="hint" style="margin-top:0">${esc(tr('Minimum payments alone may not cover interest on one or more debts. Add an extra monthly boost above.'))}</div>`:''}${rows.length?rows.map(d=>{const pInfo=d[strat];const linkedBill=d.billId&&state.bills.find(b=>b.id===d.billId);return `<div class="plan-row"><div class="flex between"><div><h3>${esc(d.name)}</h3><div class="tx-meta-row"><span class="tx-pill">${esc(typeTag(d.type))}</span><span class="tx-pill">${esc(rateTag(d))}</span>${pInfo?.payoffMonth?`<span class="tx-pill">${esc(tr('Clear in {m} mo ({date})',{m:pInfo.payoffMonth,date:dateLabel(pInfo.payoffDate)}))}</span>`:''}</div></div><strong class="money">${money(d.balance)}</strong></div><div class="budget-foot" style="margin-top:8px"><span>${esc(tr('Min {amt}/mo · Due day {d}',{amt:money(d.minPayment),d:d.dueDay||1}))}</span><span>${pInfo?esc(tr('{amt} est. interest',{amt:money(pInfo.interest)})):esc(tr('Paid off'))}</span></div><div class="tools-grid" style="margin-top:10px">${actionButton(tr('Edit'),'editDebt',d.id)}${actionButton(tr(linkedBill?'Update reminder':'Create reminder'),'syncDebtReminder',d.id,'ghost')}</div></div>`;}).join(''):empty('target',tr('Track PTPTN, BNPL & Cards'),tr('Compare Avalanche vs Snowball payoff dates without leaving your phone.'),tr('Add a debt'),'addDebt')}</section>${pl.schedule?.length?`<section class="card"><details><summary>${esc(tr('Monthly Payoff Schedule ({n})',{n:pl.schedule.length}))}</summary>${pl.schedule.map(m=>`<div class="detail-line"><span>${esc(tr('Month {m}',{m:m.month}))} (${esc(m.date)})${m.paidOff?.length?` · <strong>${esc(tr('Paid off'))}: ${esc(m.paidOff.join(', '))}</strong>`:''}</span><span class="money">${money(m.paid)} → ${esc(tr('{amt} left',{amt:money(m.remaining)}))}</span></div>`).join('')}</details></section>`:''}</div>`;
}

function computeLocalTax(prof){
  const base=Number(prof.monthlyBaseSalary||0),bonus=Number(prof.annualBonus||0),other=Number(prof.otherIncome||0),epfRate=Number(prof.epfRate??11),pcb=Number(prof.monthlyPcb||0);
  const grossSen=Math.round((base*12+bonus+other)*100);
  const autoEpf=Math.min(400000,Math.round(grossSen*(epfRate/100)));
  const ind=900000;
  const epf=Math.min(400000,prof.epfRelief!==undefined&&prof.epfRelief!==''?Math.round(Number(prof.epfRelief)*100):autoEpf);
  const life=Math.min(300000,Math.round(Number(prof.lifeInsurance||0)*100));
  const medIns=Math.min(300000,Math.round(Number(prof.medicalInsurance||0)*100));
  const socso=Math.min(35000,Math.round(Number(prof.socso??(base>0?350:0))*100));
  const lifeSt=Math.min(250000,Math.round(Number(prof.lifestyle||0)*100));
  const sports=Math.min(100000,Math.round(Number(prof.sports||0)*100));
  const med=Math.min(1000000,Math.round(Number(prof.medical||0)*100));
  const edu=Math.min(700000,Math.round(Number(prof.education||0)*100));
  const sspn=Math.min(800000,Math.round(Number(prof.sspnPrs||0)*100));
  const fam=Math.min(2000000,Math.round(Number(prof.familyRelief||0)*100));
  const reliefs=ind+epf+life+medIns+socso+lifeSt+sports+med+edu+sspn+fam;
  const chargeable=Math.max(0,grossSen-reliefs);
  const tiers=[[0,500000,0],[500000,2000000,1],[2000000,3500000,3],[3500000,5000000,6],[5000000,7000000,11],[7000000,10000000,19],[10000000,40000000,25],[40000000,60000000,26],[60000000,200000000,28],[200000000,Infinity,30]];
  let grossTax=0,marginalRate=0;const brackets=[];
  for(const [from,to,rate] of tiers){
    if(chargeable<=from)break;
    const tx=Math.min(chargeable,to)-from;
    const tAmt=Math.round(tx*(rate/100));
    grossTax+=tAmt;
    marginalRate=rate;
    brackets.push({from,to:to===Infinity?-1:to,rate,taxable:tx,tax:tAmt});
  }
  const statReb=(chargeable>0&&chargeable<=3500000)?40000:0;
  const zakat=Math.round(Number(prof.zakat||0)*100);
  const netTax=Math.max(0,grossTax-statReb-zakat);
  const netTaxAt=ch=>{
    let g=0;
    for(const [from,to,rate] of tiers){if(ch<=from)break;g+=Math.round((Math.min(ch,to)-from)*(rate/100));}
    const sr=(ch>0&&ch<=3500000)?40000:0;
    return Math.max(0,g-sr-zakat);
  };
  const optDefs=[
    ['lifestyle','Lifestyle (Books / Tech / WiFi)',lifeSt,250000],
    ['sports','Sports Equipment & Gym',sports,100000],
    ['sspnPrs','SSPN / PRS / EV Charging',sspn,800000],
    ['medical','Medical & Health Checkup',med,1000000],
    ['education','Self Education & Upskilling',edu,700000],
    ['lifeInsurance','Life Insurance / Family Takaful',life,300000]
  ];
  const reliefOptimizer=optDefs.filter(([, ,used,cap])=>cap>used).map(([key,bucket,used,cap])=>{
    const unused=cap-used;
    const potentialTaxSaved=Math.max(0,netTax-netTaxAt(Math.max(0,chargeable-unused)));
    return {key,bucket,used,cap,unused,potentialTaxSaved};
  });
  const prioUnused=Math.max(0,250000-lifeSt)+Math.max(0,100000-sports)+Math.max(0,800000-sspn);
  const maxAdditionalTaxSavings=Math.max(0,netTax-netTaxAt(Math.max(0,chargeable-prioUnused)));
  const annualPcb=Math.round(pcb*12*100);
  const mGross=Math.round(base*100),mEpf=Math.round(mGross*(epfRate/100)),mSocso=Math.round(socso/12),mPcb=pcb>0?Math.round(pcb*100):Math.round(netTax/12);
  const mTakeHome=Math.max(0,mGross-mEpf-mSocso-mPcb);
  return {year:new Date().getFullYear(),annualGross:grossSen,totalReliefs:reliefs,chargeableIncome:chargeable,grossTax,statutoryRebate:statReb,zakatRebate:zakat,netTaxPayable:netTax,monthlyTaxEquivalent:Math.round(netTax/12),annualPcbPaid:annualPcb,pcbDifference:netTax-annualPcb,effectiveRate:grossSen>0?(netTax*100/grossSen).toFixed(2):'0.00',marginalRate,brackets,reliefOptimizer,maxAdditionalTaxSavings,ledgerDetected:reportData?.taxEstimate?.ledgerDetected||{},claimableItems:reportData?.taxEstimate?.claimableItems||[],monthlyPaycheck:{grossMonthly:mGross,epfMonthly:mEpf,socsoMonthly:mSocso,pcbMonthly:mPcb,netTakeHomeMonthly:mTakeHome}};
}

function reliefMeter(val,cap){
  const num=Math.max(0,Number(val)||0);
  const pct=Math.min(100,Math.round(num/cap*100));
  return `<div class="relief-meter"><span style="width:${pct}%"></span></div>`;
}

function taxView(){
  const p=state.settings.taxProfile||{};
  const est=reportData?.taxEstimate||computeLocalTax(p);
  const det=est.ledgerDetected||{};
  const mp=est.monthlyPaycheck||computeLocalTax(p).monthlyPaycheck;
  const claimable=est.claimableItems||[];
  const refund=est.pcbDifference<0;
  const gross=Math.max(1,est.annualGross);
  const shielded=Math.min(est.annualGross,est.totalReliefs);
  const netTax=Math.min(est.annualGross,est.netTaxPayable);
  const takeHome=Math.max(0,est.annualGross-netTax);
  const pShield=Math.round(shielded/gross*100),pTax=Math.max(est.netTaxPayable>0?2:0,Math.round(netTax/gross*100)),pHome=Math.max(0,100-pShield-pTax);
  const vLife=p.lifestyle??(det.lifestyle?(det.lifestyle/100).toFixed(2):'');
  const vSports=p.sports??(det.sports?(det.sports/100).toFixed(2):'');
  const vMed=p.medical??(det.medical?(det.medical/100).toFixed(2):'');
  const vLifeIns=p.lifeInsurance??(det.lifeInsurance?(det.lifeInsurance/100).toFixed(2):'');
  const vMedIns=p.medicalInsurance??(det.medicalInsurance?(det.medicalInsurance/100).toFixed(2):'');
  const vEdu=p.education??(det.education?(det.education/100).toFixed(2):'');
  const vSspn=p.sspnPrs??(det.sspnPrs?(det.sspnPrs/100).toFixed(2):'');
  return `<div class="stack"><section class="card hero"><div class="flex between"><span class="eyebrow">${esc(tr('MALAYSIA LHDN TAX ESTIMATOR'))} (YA ${est.year||new Date().getFullYear()})</span>${icon('shield')}</div><div class="balance money"><span>RM</span>${(est.netTaxPayable/100).toLocaleString(appLocale(),{minimumFractionDigits:2,maximumFractionDigits:2})}</div><small>${esc(tr('Estimated Annual Net Tax Payable · Effective rate {rate}% · Monthly eq. {amt}',{rate:est.effectiveRate,amt:money(est.monthlyTaxEquivalent)}))}</small><div class="hero-foot"><div class="hero-stat-box"><div class="label">${esc(tr('Chargeable Income'))}</div><strong class="money">${money(est.chargeableIncome)}</strong></div><div class="hero-stat-box"><div class="label">${esc(tr(refund?'Estimated PCB Refund':'PCB Top-Up Needed'))}</div><strong class="money">${refund?'+':''}${money(Math.abs(est.pcbDifference))}</strong></div></div></section><section class="card"><div class="section-head"><h2>${esc(tr('Monthly Paycheck & Net Take-Home'))}</h2><button type="button" class="btn secondary small" data-action="recordMonthlySalary">${icon('plus')}${esc(tr("Record This Month's Salary"))}</button></div><p class="note">${esc(tr('Estimated monthly take-home pay after statutory EPF, SOCSO/EIS, and PCB deductions.'))}</p><div class="paycheck-grid"><div class="soft-card" style="margin:0"><span class="muted" style="font-size:11px">${esc(tr('Gross Monthly Base'))}</span><strong class="money" style="display:block;font-size:18px;margin-top:4px">${money(mp.grossMonthly)}</strong></div><div class="soft-card" style="margin:0"><span class="muted" style="font-size:11px">${esc(tr('Estimated Monthly Net Take-Home'))}</span><strong class="money" style="display:block;font-size:18px;margin-top:4px;color:var(--accent)">${money(mp.netTakeHomeMonthly)}</strong></div></div><div class="detail-line"><span>${esc(tr('Less: Employee EPF (KWSP)'))}</span><strong>−${money(mp.epfMonthly)}</strong></div><div class="detail-line"><span>${esc(tr('Less: SOCSO & EIS'))}</span><strong>−${money(mp.socsoMonthly)}</strong></div><div class="detail-line"><span>${esc(tr('Less: Monthly PCB / Tax'))}</span><strong>−${money(mp.pcbMonthly)}</strong></div></section><section class="card"><h2>${esc(tr('Income Allocation & Relief Shield'))}</h2><p class="note">${esc(tr('Visualizing how your LHDN tax reliefs shield your gross annual income ({amt}).',{amt:money(est.annualGross)}))}</p><div class="tax-stack-bar" role="img" aria-label="Income allocation bar"><div class="seg-home" style="width:${pHome}%" title="Taxable Take-Home (${pHome}%)"></div><div class="seg-relief" style="width:${pShield}%" title="Shielded by Reliefs (${pShield}%)"></div><div class="seg-tax" style="width:${pTax}%" title="Net Tax (${pTax}%)"></div></div><div class="flex between" style="font-size:11px;flex-wrap:wrap"><span class="flex"><i class="dot" style="background:var(--accent);width:9px;height:9px;border-radius:3px;display:inline-block"></i>${esc(tr('Take-home'))} ${money(takeHome)}</span><span class="flex"><i class="dot" style="background:var(--accent-soft);width:9px;height:9px;border-radius:3px;display:inline-block"></i>${esc(tr('Relief shield'))} ${money(est.totalReliefs)}</span><span class="flex"><i class="dot" style="background:var(--danger);width:9px;height:9px;border-radius:3px;display:inline-block"></i>${esc(tr('Net tax'))} ${money(est.netTaxPayable)}</span></div></section>${(est.reliefOptimizer||[]).length?`<section class="card"><div class="section-head"><h2>${esc(tr('LHDN Year-End Relief Optimizer'))}</h2><span class="tag">${esc(tr('Marginal rate {rate}%',{rate:est.marginalRate||0}))}</span></div><p class="note">${est.maxAdditionalTaxSavings>0?esc(tr('You can save up to {amt} more in tax by utilizing your remaining priority relief caps before 31 Dec.',{amt:money(est.maxAdditionalTaxSavings)})):esc(tr('Remaining LHDN relief caps available for this assessment year.'))}</p>${est.reliefOptimizer.map(o=>`<div class="detail-line"><span><strong>${esc(tr(o.bucket))}</strong><br><small class="muted">${esc(tr('{used} used of {cap} · {rem} remaining',{used:money(o.used),cap:money(o.cap),rem:money(o.unused)}))}</small></span><strong class="money" style="color:var(--accent)">${o.potentialTaxSaved>0?esc(tr('Save up to {amt}',{amt:money(o.potentialTaxSaved)})):esc(tr('{rem} cap left',{rem:money(o.unused)}))}</strong></div>`).join('')}</section>`:''}<section class="card"><div class="section-head"><h2>${esc(tr('Income, Reliefs & Rebates'))}</h2><button type="button" class="btn secondary small" data-action="autoFillTaxReliefs">${esc(tr('Auto-fill from recorded expenses'))}</button></div><p class="note">${esc(tr('Enter your base salary, bonus, and LHDN reliefs below. Everything is calculated and saved privately on your device.'))}</p><form data-extra="taxProfile" id="tax-profile-form"><h3>${esc(tr('1. Employment & Other Income'))}</h3><div class="form-grid">${field('Monthly Base Salary (RM)','monthlyBaseSalary',p.monthlyBaseSalary??'','number','step="0.01" min="0" placeholder="e.g. 5500"')}${field('Annual Bonus / Commission (RM)','annualBonus',p.annualBonus??'','number','step="0.01" min="0" placeholder="e.g. 5500"')}</div><div class="form-grid">${field('Other Annual Taxable Income (RM)','otherIncome',p.otherIncome??'','number','step="0.01" min="0" placeholder="0.00"')}${field('Employee EPF (KWSP) Rate (%)','epfRate',p.epfRate??11,'number','step="0.5" min="0" max="20"')}</div>${field('Monthly PCB / MTD Deducted (RM)','monthlyPcb',p.monthlyPcb??'','number','step="0.01" min="0" placeholder="e.g. 120.00"')}<h3>${esc(tr('2. LHDN Tax Reliefs (Annual RM)'))}</h3><p class="note">Individual & dependent statutory relief of <strong>RM 9,000</strong> is applied automatically. Progress bars show cap utilization.</p><div class="form-grid"><div>${field('EPF / KWSP (Cap RM 4,000)','epfRelief',p.epfRelief??'','number','step="0.01" min="0" max="4000" placeholder="Auto from EPF %"')}${reliefMeter(p.epfRelief||4000,4000)}</div><div>${field('SOCSO / PERKESO & EIS (Cap RM 350)','socso',p.socso??350,'number','step="0.01" min="0" max="350"')}${reliefMeter(p.socso??350,350)}</div></div><div class="form-grid"><div>${field(`Lifestyle: Books, PC, Phone, WiFi (Cap RM 2,500 · Ledger: ${money(det.lifestyle||0)})`,'lifestyle',vLife,'number','step="0.01" min="0" max="2500"')}${reliefMeter(vLife,2500)}</div><div>${field(`Sports Equipment & Gym (Cap RM 1,000 · Ledger: ${money(det.sports||0)})`,'sports',vSports,'number','step="0.01" min="0" max="1000"')}${reliefMeter(vSports,1000)}</div></div><div class="form-grid"><div>${field(`Medical, Checkup & Parents (Cap RM 10,000 · Ledger: ${money(det.medical||0)})`,'medical',vMed,'number','step="0.01" min="0" max="10000"')}${reliefMeter(vMed,10000)}</div><div>${field(`Life Insurance / Family Takaful (Cap RM 3,000 · Ledger: ${money(det.lifeInsurance||0)})`,'lifeInsurance',vLifeIns,'number','step="0.01" min="0" max="3000"')}${reliefMeter(vLifeIns,3000)}</div></div><div class="form-grid"><div>${field(`Medical & Education Insurance (Cap RM 3,000 · Ledger: ${money(det.medicalInsurance||0)})`,'medicalInsurance',vMedIns,'number','step="0.01" min="0" max="3000"')}${reliefMeter(vMedIns,3000)}</div><div>${field(`Self Education / Upskilling (Cap RM 7,000 · Ledger: ${money(det.education||0)})`,'education',vEdu,'number','step="0.01" min="0" max="7000"')}${reliefMeter(vEdu,7000)}</div></div><div class="form-grid"><div>${field(`SSPN / PRS / EV Charging (Cap RM 8,000 · Ledger: ${money(det.sspnPrs||0)})`,'sspnPrs',vSspn,'number','step="0.01" min="0" max="8000"')}${reliefMeter(vSspn,8000)}</div><div>${field('Spouse / Child / OKU Reliefs (RM)','familyRelief',p.familyRelief??'','number','step="0.01" min="0" placeholder="0.00"')}${reliefMeter(p.familyRelief||0,20000)}</div></div><h3>${esc(tr('3. Tax Rebates (Direct Offset)'))}</h3>${field(`Zakat / Fitrah Paid (RM · 100% rebate · Ledger: ${money(det.zakat||0)})`,'zakat',p.zakat??(det.zakat?(det.zakat/100).toFixed(2):''),'number','step="0.01" min="0" placeholder="0.00"')}<p class="note">Statutory RM 400 individual rebate is applied automatically if Chargeable Income ≤ RM 35,000.</p>${formFooter(tr('Calculate & Save Tax Estimate'))}</form></section><section class="card"><h2>${esc(tr('LHDN Calculation Breakdown'))}</h2>${[[tr('Annual Gross Income'),money(est.annualGross)],[tr('Less: Total Tax Reliefs (incl. RM 9,000 Individual)'),'−'+money(est.totalReliefs)],[tr('Chargeable Income'),money(est.chargeableIncome)],[tr('Gross Tax Before Rebates'),money(est.grossTax)],...(est.statutoryRebate?[[tr('Less: Statutory Rebate (Chargeable ≤ RM 35k)'),'−'+money(est.statutoryRebate)]]:[]),...(est.zakatRebate?[[tr('Less: Zakat / Fitrah Rebate'),'−'+money(est.zakatRebate)]]:[]),[tr('Net Tax Payable'),money(est.netTaxPayable)],[tr('Monthly Tax Equivalent'),money(est.monthlyTaxEquivalent)],[tr('Annual PCB Deducted'),money(est.annualPcbPaid)],[tr(refund?'Estimated LHDN Tax Refund':'Estimated Balance Tax to Pay'),(refund?tr('Refund '):tr('Pay '))+money(Math.abs(est.pcbDifference))]].map(([l,v])=>`<div class="detail-line"><span>${esc(l)}</span><strong>${esc(v)}</strong></div>`).join('')}${est.brackets?.length?`<h3 style="margin-top:16px">${esc(tr('Progressive Tax Tiers Applied'))}</h3>${est.brackets.map(b=>`<div class="detail-line"><span>${money(b.from)} – ${b.to<0?tr('Above'):money(b.to)} (${b.rate}%)</span><span>${esc(tr('Taxable'))} ${money(b.taxable)} → <strong>${money(b.tax)}</strong></span></div>`).join('')}`:''}</section><section class="card"><details open><summary>${esc(tr('LHDN Claimable Ledger Transactions'))} (${claimable.length})</summary><div class="tools-grid" style="margin:8px 0">${actionButton(tr('Export PDF Recap & LHDN List'),'exportPdf')}</div><p class="note" style="margin-bottom:10px">${esc(tr('Expenses recorded in {year} that match LHDN relief buckets. Tap any row to view or attach a receipt.',{year:est.year||new Date().getFullYear()}))}</p>${claimable.length?claimable.map(c=>`<div class="detail-line" style="cursor:pointer" data-action="detail" data-id="${esc(c.id)}"><span><strong>${esc(c.merchant)}</strong> · <span class="tx-pill">${esc(c.bucket)}</span><br><small class="muted">${esc(c.date)} · ${esc(tr(c.category))}${c.subcategory?' › '+esc(c.subcategory):''}</small></span><strong class="money">${money(c.amount)}</strong></div>`).join(''):`<p class="note">${esc(tr('No tax-deductible expenses recorded yet for this assessment year.'))}</p>`}</details></section></div>`;
}

function computeLocalRecap(){
  const p=period(),endInc=new Date(new Date(p.end+'T00:00:00').getTime()-86400000).toISOString().slice(0,10);
  const rows=currentTx();
  let totalIncome=0,totalSpent=0;const cats={},merchs={};
  for(const txRow of rows){
    if(txRow.kind==='income')totalIncome+=txRow.amount;
    for(const pt of txRow.postings||[]){
      if(pt.account.startsWith('expense:')){
        const c=pt.account.slice(8);totalSpent+=pt.amount;cats[c]=(cats[c]||0)+pt.amount;
        const m=txRow.merchant||c;merchs[m]=merchs[m]||{amount:0,count:0};merchs[m].amount+=pt.amount;merchs[m].count++;
      }
    }
  }
  const catSum=Math.max(1,Object.values(cats).filter(v=>v>0).reduce((a,b)=>a+b,0));
  return {
    start:p.start,end:endInc,totalIncome,totalSpent,previousSpent:0,spentDelta:totalSpent,netSaved:totalIncome-totalSpent,
    savingsRatePct:totalIncome>0?Math.round((totalIncome-totalSpent)*100/totalIncome):0,
    topCategories:Object.entries(cats).filter(([,v])=>v>0).sort((a,b)=>b[1]-a[1]).slice(0,10).map(([category,amount])=>({category,amount,sharePct:Math.round(amount*100/catSum)})),
    topMerchants:Object.entries(merchs).filter(([,v])=>v.amount>0).sort((a,b)=>b[1].amount-a[1].amount).slice(0,8).map(([merchant,v])=>({merchant,amount:v.amount,count:v.count})),
    underBudgetCount:0,overBudgetCount:0,claimableCount:reportData?.taxEstimate?.claimableItems?.length||0
  };
}

function recapMarkup(rc){
  const r=rc||reportData?.recap||computeLocalRecap();
  const deltaSign=r.spentDelta>0?'+':'';
  return `<p class="eyebrow muted">${esc(r.start)} – ${esc(r.end)}</p><div class="paycheck-grid"><div class="soft-card" style="margin:0"><span class="muted" style="font-size:11px">${esc(tr('Total Income'))}</span><strong class="money" style="display:block;font-size:18px;margin-top:4px">${money(r.totalIncome)}</strong></div><div class="soft-card" style="margin:0"><span class="muted" style="font-size:11px">${esc(tr('Total Spending'))}</span><strong class="money" style="display:block;font-size:18px;margin-top:4px">${money(r.totalSpent)}</strong></div></div><div class="detail-line"><span>${esc(tr('Net Saved'))} (${r.savingsRatePct}%)</span><strong class="money">${signedMoney(r.netSaved)}</strong></div><div class="detail-line"><span>${esc(tr('Vs previous cycle'))}</span><span>${deltaSign}${signedMoney(r.spentDelta)}</span></div><div class="detail-line"><span>${esc(tr('Budgets'))}</span><span>${esc(tr('{u} on track · {o} over',{u:r.underBudgetCount,o:r.overBudgetCount}))}</span></div><div class="detail-line"><span>${esc(tr('LHDN YA Claimable items'))}</span><strong>${esc(tr('{n} recorded',{n:r.claimableCount||0}))}</strong></div><h3>${esc(tr('Top Categories'))}</h3>${(r.topCategories||[]).map(c=>`<div class="detail-line"><span>${esc(tr(c.category))} (${c.sharePct}%)</span><strong class="money">${money(c.amount)}</strong></div>`).join('')||`<p class="note">${esc(tr('No expenses in this cycle.'))}</p>`}<h3>${esc(tr('Top Merchants'))}</h3>${(r.topMerchants||[]).map(m=>`<div class="detail-line"><span>${esc(m.merchant)} (${m.count}×)</span><strong class="money">${money(m.amount)}</strong></div>`).join('')||`<p class="note">${esc(tr('No merchant spending in this cycle.'))}</p>`}<div class="form-footer" style="margin-top:16px">${actionButton(tr('Export PDF Report'),'exportPdf')}<button type="button" class="btn ghost" data-action="close">${esc(tr('Close'))}</button></div>`;
}
