'use strict';
let sourceGroup='all';
function inferSourceGroup(a){
  if(a.group&&a.group!=='other')return a.group;
  const hay=(String(a.name||'')+' '+String(a.package||'')).toLowerCase();
  if(/spaylater|shopeepay|atome|payflex|lazpaylater|tiktok|lazada|grab|boost/.test(hay)&&!/boostbank|boost bank/.test(hay))return 'bnpl';
  if(/tng|touch|setel|bigpay|fave|shopback/.test(hay))return 'wallet';
  if(/foodpanda/.test(hay))return 'delivery';
  if(/maybank|mae|cimb|octo|mypb|public bank|rhb|hong leong|hlb|ambank|amonline|bank islam|bimb|uob|ocbc|hsbc|sc\.mobile|bsn|gxbank|aeonbank|aeon bank|rytbank|ryt bank|boostbank|boost bank/.test(hay))return 'bank';
  return a.group||'other';
}
function modal(title,body){
  $('#modal-root').innerHTML=`<div class="modal-shade"><section class="modal" tabindex="-1" role="dialog" aria-modal="true" aria-labelledby="dialog-title"><div class="sheet-handle"></div><header><h2 id="dialog-title">${esc(title)}</h2><button class="close" data-action="close" aria-label="Close">${icon('close')}</button></header>${body}</section></div>`;
  const m=$('.modal');
  if(m){m.scrollTop=0;setTimeout(()=>{m.scrollTop=0;try{m.focus({preventScroll:true});}catch(_){}},40);}
}
function close(){if(busy)return;if(modalContext.isRestorePreview&&window.MoneyLensNative)call('cancelRestore').catch(()=>{});$('#modal-root').innerHTML='';modalContext={}}

function txForm(data={}){
  const merged={account:data.draftId?'':state.settings.lastAccount,category:state.settings.lastCategory||'Miscellaneous',...data};
  const kind=merged.kind||'expense';
  modalContext={...merged,kind,key:merged.key||cryptoId()};
  const acctRows=state.accounts.map(a=>[a.id,a.name]);
  const catRows=kind==='income'?[['Salary',tr('Salary')],['Other income',tr('Other income')]]:activeCategories().map(c=>[c[0],tr(c[0])]);
  const selCat=merged.category||catRows[0][0];
  const prevCount=merged.editId&&merged.merchant?sameMerchantCount(merged.merchant,merged.editId):0;
  const chips=kind==='reimbursement'
    ? `<span class="chip active">${esc(tr('Repayment received'))}</span>`
    : `${['expense','income','transfer','reload','refund'].map(k=>`<button type="button" class="chip ${kind===k?'active':''}" data-kind="${k}">${esc(tr(k[0].toUpperCase()+k.slice(1)))}</button>`).join('')}${kind==='shared'?`<span class="chip active">${esc(tr('Shared expense'))}</span>`:''}`;
  const evidence=merged.amountCandidates?.length>1?`<p class="note">${esc(tr('Amounts found: {list}. Choose the actual payment amount above.',{list:merged.amountCandidates.map(money).join(' · ')}))}</p>`:'';
  const thumb=merged.receiptId?`<img class="receipt-thumb" src="/receipt/${esc(merged.receiptId)}" alt="Attached receipt">`:'';
  const isNeutralMove=kind==='transfer'||kind==='reload';
  const subRow=(!kind||['expense','refund','shared'].includes(kind))?subcategorySelect(selCat,merged.subcategory||''):'';
  const sharedRows=kind==='shared'?`${field(tr('Your own share (RM)'),'ownAmount',merged.ownAmount||'0','text','required inputmode="decimal"')}${field(tr('Who owes the rest?'),'person',merged.person||'','text','required maxlength="80"')}`:'';
  const tagsField=field(tr('Tags · comma separated'),'tags',Array.isArray(merged.tags)?merged.tags.join(', '):merged.tags||'','text','maxlength="200" placeholder="work, holiday"');
  const splitsBox=(!kind||['expense','refund'].includes(kind))?`<details ${merged.splits?.length?'open':''}><summary>${esc(tr('Split across categories'))}</summary><div id="split-rows">${(Array.isArray(merged.splits)?merged.splits:[]).map(splitRow).join('')}</div>${actionButton(tr('Add category split'),'addSplit')}<p class="note">${esc(tr('Splits must add up to the full payment above.'))}</p></details>`:'';
  const fxRates=[['SGD','3.30'],['USD','4.45'],['THB','0.13'],['JPY','0.029'],['EUR','4.80'],['GBP','5.65'],['AUD','2.90'],['CNY','0.62']];
  const fxBox=`<details ${merged.originalCurrency?'open':''}><summary>${esc(tr('Foreign currency details'))}</summary><div class="chips" style="margin:8px 0">${fxRates.map(([c,rt])=>`<button type="button" class="chip" data-action="fxPreset" data-id="${c}" data-rate="${rt}">${c} (~${rt})</button>`).join('')}</div><div class="form-grid">${field(tr('Original currency'),'originalCurrency',merged.originalCurrency||'','text','maxlength="3" placeholder="USD"')}${field(tr('Original amount'),'originalAmount',merged.originalAmount||'','text','inputmode="decimal"')}</div>${field(tr('Included conversion fees (MYR)'),'foreignFee',merged.foreignFee||'0','text','inputmode="decimal"')}<p class="note">${esc(tr('Enter the actual settled MYR amount at the top, including any fees. The implied rate is saved with this record.'))}</p></details>`;
  const historyCheck=prevCount>0?check(`Update ${prevCount} previous "${esc(merged.merchant)}" transaction${prevCount===1?'':'s'} to this category & remember for future`,'applyHistory',merged.category==='Miscellaneous'||merged.category==='Other'):'';
  const ruleCheck=check(tr('Remember merchant category and account for next time'),'rememberRule',!!merged.editId&&(merged.category==='Miscellaneous'||merged.category==='Other'));
  const title=merged.draftId?tr('Review payment'):merged.editId?tr('Edit transaction'):merged.billId?tr('Record bill payment'):tr('Add transaction');
  const quickBar=(!merged.editId&&!merged.draftId)?`<div class="quick-parse-bar"><input type="text" id="quick-parse-input" placeholder="${esc(tr('Try: Nasi Lemak 12.50 cash or 昨晚喝咖啡 15块 tng'))}" aria-label="${esc(tr('Smart quick entry'))}"><button type="button" class="btn secondary small" data-action="quickParseFill">${esc(tr('Auto-fill'))}</button></div>`:'';
  const destRows=kind==='reload'?[['',tr('Same account (no balance change)')],...acctRows]:acctRows;
  const destSelected=kind==='reload'?(merged.to||''):(merged.to||state.accounts[1]?.id||'');
  const kindHint=kind==='reload'?'A reload records top-up history without counting as an expense or reducing your balance.':kind==='transfer'?'A transfer moves money without adding an expense.':kind==='refund'?'A refund reduces spending in the selected category.':'Only confirmed records affect your balance.';
  modal(title,`<form id="transaction-form">${quickBar}<div class="chips">${chips}</div><div class="amount-input"><span>RM</span><input name="amount" id="f-amount" inputmode="decimal" aria-label="Amount in MYR" placeholder="0.00" value="${merged.amount?esc(typeof merged.amount==='number'?amount(merged.amount):merged.amount):''}" required pattern="[0-9]+([.][0-9]{1,2})?" autocomplete="off"></div>${merged.reason?`<div class="hint" style="margin:0">${esc(merged.reason)}</div>`:''}<div class="form-grid">${select(tr(kind==='transfer'?'From account':'Account'),'account',merged.draftId?[['',tr('Choose account')],...acctRows]:acctRows,merged.account||(merged.draftId?'':state.accounts[0].id))}${isNeutralMove?select(tr('To account'),'to',destRows,destSelected):select(tr('Category'),'category',catRows,selCat)}</div>${field(tr(isNeutralMove?'Description':'Merchant / description'),'merchant',merged.merchant||'','text','maxlength="120"')}${field(tr('Date'),'date',merged.date||today(),'date','required')}<div><label for="f-note">${esc(tr('Note'))} <span class="muted">· ${esc(tr('optional'))}</span></label><textarea id="f-note" name="note" maxlength="500" placeholder="${esc(tr('Anything worth remembering?'))}">${esc(merged.note||'')}</textarea></div>${evidence}${thumb}${subRow}${sharedRows}${tagsField}${splitsBox}${fxBox}${historyCheck}${ruleCheck}<div class="error" role="alert"></div><div class="form-footer"><button class="btn" type="submit">${icon('check')}${esc(tr(merged.editId?'Save changes':'Record transaction'))}</button></div><p class="note">${esc(tr(kindHint))}</p></form>`);
  if(data.focusQuick)setTimeout(()=>{const q=$('#quick-parse-input');if(q){try{q.focus({preventScroll:true});}catch(_){}}},60);
  else if(data.focusSubmit)setTimeout(()=>{const btn=$('#transaction-form button[type="submit"]');if(btn){try{btn.focus({preventScroll:true});}catch(_){}}},60);
}

function splitRow(s={}){
  return `<div class="split-row">${select('Split category','splitCategory',activeCategories().map(c=>[c[0],tr(c[0])]),s.category||'Miscellaneous')}${field('RM','splitAmount',s.amount||'','text','inputmode="decimal" required')}<button type="button" class="row-menu" data-action="removeSplit" aria-label="Remove split">${icon('close')}</button></div>`;
}

function enrichTransaction(args,form){
  args.rememberRule=!!form.elements.rememberRule?.checked;
  args.applyHistory=!!form.elements.applyHistory?.checked;
  args.tags=(args.tags||'').split(',').map(x=>x.trim()).filter(Boolean);
  const rows=[...form.querySelectorAll('.split-row')];
  if(rows.length)args.splits=rows.map(row=>({category:row.querySelector('select').value,amount:row.querySelector('input').value}));
  delete args.splitCategory;
  delete args.splitAmount;
  if(!args.originalCurrency&&!args.originalAmount){
    delete args.originalCurrency;delete args.originalAmount;delete args.foreignFee;
  }else{
    if(!args.originalCurrency||!Number.isFinite(Number(args.originalAmount))||Number(args.originalAmount)<=0)throw new Error('Enter both the original currency and a positive amount.');
    if(!/^\d+([.]\d{1,2})?$/.test(args.foreignFee)||Number(args.foreignFee)>Number(args.amount))throw new Error('Included fees must be between zero and the MYR total.');
    args.exchangeRate=((Number(args.amount)-Number(args.foreignFee))/Number(args.originalAmount)).toFixed(8);
  }
}

function quickNoteForm(id){
  const txRow=state.transactions.find(x=>x.id===id);
  if(!txRow)return;
  modalContext={id:txRow.id,merchant:txRow.merchant||'',oldCategory:txRow.category,account:txRow.account};
  const otherCount=sameMerchantCount(txRow.merchant,txRow.id);
  const quickCats=[
    ['Hawker & Mamak','Food & drinks','Hawker & Mamak'],
    ['Coffee & Tea','Food & drinks','Coffee, Tea & Drinks'],
    ['Food Delivery','Food & drinks','Food Delivery (GrabFood / Foodpanda)'],
    ['Groceries','Groceries','Supermarket & Hypermarket'],
    ['99 Speedmart / KK','Groceries','Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)'],
    ['BNPL (SPayLater / Atome)','Loans, BNPL & Credit','BNPL (SPayLater / GrabPayLater / Atome)'],
    ['Petrol / EV','Transport','Petrol & EV Charging'],
    ['Toll / Transit','Transport','Toll (TNG / RFID / PayDirect)'],
    ['Online Shopping','Shopping','Online Marketplace (Shopee / Lazada / TikTok)']
  ];
  const chipsHtml=['expense','refund'].includes(txRow.kind)&&!txRow.splits?`<div class="field-group"><label>${esc(tr('Quick category fix'))}</label><div class="chips quick-cat-chips">${quickCats.map(([lbl,c,sub])=>`<button type="button" class="chip ${txRow.subcategory===sub?'active':''}" data-action="quickCatPick" data-cat="${esc(c)}" data-sub="${esc(sub)}">${esc(tr(lbl))}</button>`).join('')}</div></div>`:'';
  const anomalyBanner=(txRow.anomaly&&!txRow.anomalyDismissed)?`<div class="anomaly-banner"><span>⚠ ${esc(tr('Unusual spend'))}: ${esc(txRow.anomalyRatio||'High')} ${esc(tr('usual'))}${txRow.anomalyMedian?' ('+money(txRow.anomalyMedian)+')':''}</span>${check(tr('Dismiss unusual spend warning'),'dismissAnomaly',true)}</div>`:'';
  modal('Edit purpose & category',`<form data-extra="quickNote"><div class="soft-card" style="margin-top:0"><strong>${esc(txRow.merchant||txRow.category)} · ${money(txRow.amount)}</strong><p class="note">${esc(txRow.date)} · ${esc(account(txRow.account).name)}</p></div>${anomalyBanner}${field('Purpose / note','note',txRow.note||'','text','maxlength="500" placeholder="e.g. Toll to office, Lunch with client"')}${chipsHtml}${['expense','refund'].includes(txRow.kind)&&!txRow.splits?`<div class="form-grid">${categorySelect(txRow.category)}${subcategorySelect(txRow.category,txRow.subcategory||'')}</div>`:''}${otherCount>0&&txRow.merchant?check(`Also update ${otherCount} other transaction${otherCount===1?'':'s'} for "${esc(txRow.merchant)}" & remember category for future`,'applyHistory',txRow.category==='Miscellaneous'||txRow.category==='Other'):check('Remember category for future notifications from this merchant','applyHistory',txRow.category==='Miscellaneous'||txRow.category==='Other')}<div class="form-footer">${formFooter('Save purpose & category')}<button type="button" class="btn danger" data-action="quickRemove" data-id="${esc(txRow.id)}">${icon('trash')}Remove</button></div></form>`);
}

function accountForm(){
  modalContext={};
  modal('Add an account',`<form id="account-form">${field('Account name','name','','text','required maxlength="60" placeholder="e.g. Maybank or TNG"')}${select('Account type','type',[['bank','Bank account'],['wallet','E-wallet'],['cash','Cash'],['card','Credit card']],'bank')}${field('Opening balance / card amount owed (RM)','opening','','text','inputmode="decimal" placeholder="0.00"')}<p class="note">For a credit card, enter the amount you owe. Opening balances are not income or spending.</p><div class="error" role="alert"></div><button class="btn">Create account</button></form>`);
}

function budgetForm(b={}){
  modalContext=b;
  const catName=b.category||categories[0][0];
  modal(b.id?'Edit budget':'Create a budget',`<form id="budget-form">${select('Category','category',activeCategories().map(c=>[c[0],tr(c[0])]),catName)}${subcategorySelect(catName,b.subcategory||'')}${field('Amount per cycle (RM)','amount',b.limit?amount(b.limit):'','text','required inputmode="decimal" placeholder="500.00"')}${check('Carry unused budget into the next cycle','rollover',!!b.rollover)}<div class="error" role="alert"></div><button class="btn">Save budget</button>${b.id?`<button type="button" class="btn ghost" data-action="deletePlan" data-table="budgets" data-id="${esc(b.id)}">Remove budget</button>`:''}</form>`);
}

function billForm(b={}){
  modalContext=b;
  const catVal=b.category||'Loans, BNPL & Credit';
  const freqVal=b.frequency||(b.monthly===false?'once':'monthly');
  modal(b.id?'Edit reminder / bill':'Schedule BNPL, PTPTN or bill reminder',`<form id="bill-form">${field('Reminder / bill name','name',b.name||'','text','required maxlength="80" placeholder="e.g. SPayLater, PTPTN Study Loan, Unifi"')}${select('Reminder type','reminderType',[['bnpl','BNPL (SPayLater / GrabPayLater / Atome)'],['ptptn','PTPTN Study Loan'],['credit','Credit Card Statement'],['bill','Regular Bill / Utility / Subscription']],b.reminderType||'bill')}<div class="form-grid">${field('Amount (RM)','amount',b.amount?amount(b.amount):'','text','required inputmode="decimal" placeholder="150.00"')}${field('Next due date','date',b.date||today(),'date','required')}</div><div class="form-grid">${acctSelect(b.account)}${categorySelect(catVal)}</div>${subcategorySelect(catVal,b.subcategory||'')}<div class="form-grid">${select('Repeat schedule','frequency',[['monthly','Monthly (same day each month)'],['everyXDays','Every X days (custom interval)'],['weekly','Weekly'],['daily','Daily'],['yearly','Yearly'],['once','One time only']],freqVal)}${field('Custom interval (days, for Every X days)','intervalDays',b.intervalDays||30,'number','min="1" max="365"')}</div><div class="form-grid">${field('Pay before due date: remind me (days before)','remindDaysBefore',b.remindDaysBefore??3,'number','min="0" max="30" required')}${field('Notification timeslot (24h)','remindTime',b.remindTime||'09:00','time','required')}</div>${check('Amount varies — confirm actual payment later','variable',b.variable)}${check('This is a subscription','subscription',b.subscription)}${field('Cancellation reminder date · optional','cancelDate',b.cancelDate||'','date')}${formFooter('Save reminder schedule')}${b.id?`<div class="tools-grid" style="margin-top:10px">${actionButton('Test alert now','testReminder',b.id)}${actionButton(b.cancelled?'Resume schedule':'Pause reminders','cancelBill',b.id,'ghost')}${actionButton('Delete reminder','deleteBillExtra',b.id,'danger')}</div>`:''}</form>`);
}

function goalForm(g={}){
  modalContext=g;
  modal(tr(g.id?'Update your goal':'Create a savings goal'),`<form id="goal-form">${field(tr('What are you saving for?'),'name',g.name||'','text','required maxlength="80" placeholder="e.g. New desktop PC"')}<div class="form-grid">${field(tr('Target (RM)'),'target',g.target?amount(g.target):'','text','required inputmode="decimal"')}${field(tr('Already earmarked (RM)'),'saved',g.saved?amount(g.saved):'0','text','inputmode="decimal"')}</div>${field(tr('Target date (optional)'),'targetDate',g.targetDate||'','date','')}<p class="note">${esc(tr('This tracks progress only. It does not move or spend money.'))}</p><div class="error" role="alert"></div><button class="btn">${esc(tr('Save goal'))}</button>${g.id?`<button type="button" class="btn ghost" data-action="deletePlan" data-table="goals" data-id="${esc(g.id)}">${esc(tr('Remove goal'))}</button>`:''}</form>`);
}

function debtForm(d={}){
  modalContext=d;
  const aprVal=d.aprBps!==undefined?(d.aprBps/100).toFixed(2):(d.apr??'0');
  modal(d.id?'Edit debt':'Add BNPL, PTPTN or loan',`<form data-extra="debt">${field('Debt name','name',d.name||'','text','required maxlength="80" placeholder="e.g. PTPTN Ujrah, SPayLater, Maybank Card"')}<div class="form-grid">${select('Debt type','type',[['ptptn','PTPTN Study Loan'],['bnpl','BNPL (SPayLater / Atome / Grab)'],['credit','Credit Card Balance'],['loan','Car / Personal Loan']],d.type||'ptptn')}${field('Due day of month (1–31)','dueDay',d.dueDay??1,'number','min="1" max="31" required')}</div><div class="form-grid">${field('Remaining balance (RM)','balance',d.balance!==undefined?amount(d.balance):'','text','required inputmode="decimal" placeholder="1500.00"')}${field('Monthly minimum (RM)','minPayment',d.minPayment?amount(d.minPayment):'','text','required inputmode="decimal" placeholder="150.00"')}</div><div class="form-grid">${field('Annual rate / Ujrah (%)','apr',aprVal,'number','step="0.01" min="0" max="100"')}${select('Rate method','rateType',[['reducing','Reducing balance (Cards / Standard)'],['flat','Flat / Fixed principal (PTPTN Ujrah)']],d.rateType||'reducing')}</div><div class="form-grid">${field('Original principal (RM · optional for flat rate)','principal',d.principal?amount(d.principal):'','text','inputmode="decimal"')}${acctSelect(d.account)}</div>${check('Create or update monthly bill reminder','createReminder',!!d.createReminder||!!d.billId)}${formFooter('Save debt')}${d.id?`<button type="button" class="btn ghost" data-action="deletePlan" data-table="debts" data-id="${esc(d.id)}">Remove debt</button>`:''}</form>`);
}

function detail(id){
  const txRow=state.transactions.find(x=>x.id===id);
  if(!txRow)return;
  modalContext=txRow;
  const origTx=txRow.originalId?state.transactions.find(x=>x.id===txRow.originalId):null;
  const anomalyAlert=(txRow.anomaly&&!txRow.anomalyDismissed)?`<div class="anomaly-banner"><div><strong>⚠ ${esc(tr('Unusual spend'))}: ${esc(txRow.anomalyRatio||'High')} ${esc(tr('usual'))}</strong><p class="note" style="margin:2px 0 0">${txRow.anomalyMedian?esc(tr('Median at this merchant'))+': '+money(txRow.anomalyMedian):''}</p></div><button class="btn secondary small" data-action="dismissAnomaly" data-id="${esc(txRow.id)}">${esc(tr('Dismiss alert'))}</button></div>`:'';
  const refundNote=txRow.originalId?[['Linked refund',(origTx?.merchant||tr(origTx?.category||'Original expense'))+(origTx?' · '+money(origTx.amount)+' ('+dateLabel(origTx.date)+')':'')]]:[];
  const footer=['opening','adjustment'].includes(txRow.kind)
    ? actionButton('Remove adjustment','quickRemove',id,'danger')
    : `<button class="btn" data-action="editTx" data-id="${esc(txRow.id)}">${icon('edit')}Edit</button><button class="btn secondary" data-action="quickNote" data-id="${esc(txRow.id)}">Edit purpose / category</button><button class="btn danger" data-action="quickRemove" data-id="${esc(txRow.id)}">${icon('trash')}Remove</button>`;
  modal('Transaction details',`<div class="detail-total money">${txRow.kind==='expense'?'−':''}${money(txRow.amount)}</div>${anomalyAlert}${[['Description',txRow.merchant||txRow.category],['Type',txRow.kind],['Account',account(txRow.account).name],...(txRow.kind==='transfer'?[['To',account(txRow.to).name]]:[['Category',tr(txRow.category)+(txRow.subcategory?' › '+txRow.subcategory:'')]]),...refundNote,['Date',dateLabel(txRow.date)],['Note',txRow.note||'—']].map(([l,v])=>`<div class="detail-line"><span>${esc(l)}</span><span>${esc(v)}</span></div>`).join('')}<div class="form-footer">${footer}</div>${txRow.kind==='expense'?`<button class="btn ghost full" style="margin-top:12px" data-action="refundTx" data-id="${esc(txRow.id)}">Record a refund</button>`:''}<div class="tools-grid section-space">${actionButton('Repeat','repeat',id)}${actionButton('Make favorite','favoriteFrom',id)}${actionButton('Correction history','history',id)}</div>${txRow.receiptId?`<img class="receipt-full" src="/receipt/${esc(txRow.receiptId)}" alt="Saved receipt">`:''}${txRow.splits?`<details open><summary>Category split</summary>${txRow.splits.map(s=>`<p>${esc(s.category)} · RM ${esc(s.amount)}</p>`).join('')}</details>`:''}${txRow.originalCurrency?`<p class="note">${esc(txRow.originalCurrency)} ${esc(txRow.originalAmount)} · settled ${money(txRow.amount)} · included fee RM ${esc(txRow.foreignFee||'0')} · implied MYR rate ${esc(txRow.exchangeRate||'—')}</p>`:''}${txRow.person?`<p class="note">Shared with ${esc(txRow.person)} · your share RM ${esc(txRow.ownAmount)}</p>`:''}${txRow.tags?.length?`<p class="note">${esc(Array.isArray(txRow.tags)?txRow.tags.join(' · '):txRow.tags)}</p>`:''}`);
}

async function sources(){
  sourceGroup='all';
  modal('Select payment apps','<p class="note">Loading apps…</p>');
  try{apps=await call('apps');renderSources('')}catch(e){modal('Select payment apps',`<p class="error">${esc(e.message)}</p>`)}
}

function renderSources(q){
  const map=new Map(state.sources.map(s=>[s.package,s]));
  const tabs=[['all','All apps'],['bnpl','BNPL'],['wallet','E-Wallets'],['bank','Banks'],['delivery','Delivery']];
  const needle=String(q||'').toLowerCase();
  const filtered=apps.filter(a=>{
    const grp=inferSourceGroup(a);
    if(sourceGroup!=='all'&&grp!==sourceGroup)return false;
    return !needle||(a.name+' '+a.package).toLowerCase().includes(needle);
  });
  modal('Select payment apps',`<p class="note" style="margin-bottom:10px">Select payment apps, e-wallets, BNPL &amp; banks, and optionally link each app to a default MoneyLens account.</p><div class="chips" style="margin-bottom:10px">${tabs.map(([id,lbl])=>`<button type="button" class="chip ${sourceGroup===id?'active':''}" data-action="sourceGroup" data-id="${id}">${esc(tr(lbl))}</button>`).join('')}</div><div class="search">${icon('search')}<input id="source-search" placeholder="Find a bank, wallet or BNPL app" value="${esc(q)}" aria-label="Search apps"></div><div class="source-list">${filtered.map(a=>{const src=map.get(a.package),grp=inferSourceGroup(a),grpLabel=grp==='bnpl'?'BNPL':grp==='wallet'?'E-Wallet':grp==='bank'?'Bank':grp==='delivery'?'Delivery':'App';return `<div class="source-row"><div class="grow"><div class="flex" style="gap:6px;flex-wrap:wrap"><strong>${esc(a.name)}</strong>${grp!=='other'?`<span class="source-badge">${esc(grpLabel)}</span>`:''}${a.installed===false?`<span class="source-badge muted">${esc(tr('Not installed'))}</span>`:''}</div><small>${esc(a.package)}</small>${src?`<select class="source-account" data-package="${esc(a.package)}" data-name="${esc(a.name)}" style="margin-top:6px;padding:6px 10px;min-height:34px;font-size:11px"><option value="">Auto-match account</option>${options(state.accounts.map(ac=>[ac.id,ac.name]),src.account||'')}</select>`:''}</div><input class="switch source-toggle" type="checkbox" data-package="${esc(a.package)}" data-name="${esc(a.name)}" aria-label="Capture ${esc(a.name)} notifications" ${src?'checked':''}></div>`}).join('')||'<p class="note">No matching apps.</p>'}</div><button class="btn full" style="margin-top:20px" data-action="close">${esc(tr('Done'))}</button>`);
}

function categoriesModal(){
  const active=activeCategories();
  modal('Categories & Subcategories',`<p class="note">Add new categories or subcategories, or tap ✕ on any category or subcategory chip to remove it.</p><div class="stack" style="gap:12px"><form data-extra="addSubcategory" class="soft-card" style="margin:0"><h3>Add a subcategory</h3><div class="form-grid">${select('Under category','category',active.map(c=>[c[0],tr(c[0])]),active[0]?.[0]||'Food & drinks')}${field('New subcategory name','subcategory','','text','required maxlength="80" placeholder="e.g. Bubble Tea, Tol PLUS"')}</div>${formFooter('Add subcategory')}</form><form data-extra="category" class="soft-card" style="margin:0"><h3>Add a main category</h3><div class="form-grid">${field('New category name','name','','text','required maxlength="60" placeholder="e.g. Pets & Vet"')}${select('Icon','icon',[['wallet','Wallet'],['heart','Heart'],['food','Food'],['car','Car'],['target','Goal'],['bag','Shopping'],['bolt','Utilities'],['shield','Shield']],'wallet')}</div>${select('Color','color',[['sage','Sage'],['peach','Peach'],['purple','Lavender'],['blue','Blue']],'sage')}${formFooter('Add category')}</form><div class="review-list" style="max-height:340px">${active.map(c=>{
    const subs=subcategoriesFor(c[0]);
    return `<div class="plan-row" style="padding:10px 0"><div class="flex between"><strong>${esc(tr(c[0]))}</strong><button type="button" class="row-menu" data-action="removeCategory" data-name="${esc(c[0])}" title="Remove category ${esc(c[0])}">✕</button></div><div class="category-chips" style="margin-top:6px;display:flex;flex-wrap:wrap;gap:6px">${subs.map(s=>`<span class="tag" style="display:inline-flex;align-items:center;gap:4px">${esc(s)}<button type="button" data-action="removeSubcategory" data-category="${esc(c[0])}" data-subcategory="${esc(s)}" style="border:0;background:transparent;padding:0 2px;cursor:pointer;font-size:11px;line-height:1" title="Remove subcategory">✕</button></span>`).join('')||'<span class="muted" style="font-size:11px">No subcategories</span>'}</div></div>`;
  }).join('')}</div></div>`);
}

function scanDialog(){modal('Scan a receipt',`<p class="note">Choose a clear image or take a photo. Recognition runs on your device. Check the suggested total, date and merchant before recording.</p>${select('Text model','ocrLanguage',[['latin','English / Bahasa Melayu'],['chinese','Chinese + Latin']],'latin')}<div class="tools-grid">${actionButton('Take photo','receiptCamera')}${actionButton('Choose image','receiptPick')}</div>`)}
function showRecap(rc){modal(tr('Cycle Recap & Report'),recapMarkup(rc));}
function insights(){
  const r=reportData;if(!r){toast('Open the Android app to calculate reports.');return}
  const max=Math.max(1,...r.months.map(m=>m.spent));
  modal('Your money, in perspective',`<div class="tools-grid" style="margin-bottom:10px">${actionButton(tr('Cycle Recap'),'openRecap')}${actionButton(tr('Export PDF Report'),'exportPdf','','ghost')}</div><p class="eyebrow muted">${esc(r.start)} — ${esc(r.end)} · END EXCLUSIVE</p><div class="insight-grid"><div class="soft-card"><p>Available estimate</p><strong class="money metric">${money(r.availableToSpend)}</strong></div><div class="soft-card"><p>Spent to today</p><strong class="money metric">${signedMoney(r.spent)}</strong></div></div><h3>Six budget cycles</h3><div class="bar-chart" role="img" aria-label="Spending in six cycles">${r.months.map(m=>`<div><span>${signedMoney(m.spent)}</span><i style="height:${Math.max(2,Math.max(0,m.spent)/max*110)}px"></i><small>${esc(dateLabel(m.start))}</small></div>`).join('')}</div><p class="note">Current cycle is incomplete. Comparable prior-cycle spending through the same number of days: ${signedMoney(r.previousComparable)}.</p><h3>Cash flow by cycle</h3>${r.months.map(m=>`<div class="detail-line"><span>${esc(dateLabel(m.start))}</span><span>In ${money(m.income)} · out ${signedMoney(m.spent)}<br>Net ${signedMoney((m.income||0)-m.spent)}</span></div>`).join('')}<h3>What is reserved?</h3>${[['Liquid account balances',r.liquid],['Card and installment debt',-r.cardReserve],['Unpaid bills before cycle end',-r.billsReserve],['Earmarked goals',-r.goalReserve]].map(([label,n])=>`<div class="detail-line"><span>${label}</span><span>${signedMoney(n)}</span></div>`).join('')}<p class="note">Estimate from recorded balances only. Card repayment plans are excluded from bill reserves because the card debt is already reserved. Check missing opening balances and reconcile regularly.</p><h3>Category spending this cycle</h3>${categories.map(c=>[c[0],spendFor(c[0])]).filter(c=>c[1]).map(c=>`<div class="detail-line"><span>${esc(tr(c[0]))}</span><span>${signedMoney(c[1])}</span></div>`).join('')}`);
}
function favoriteForm(t={}){modalContext=t;modal('Quick favorite',`<form data-extra="favorite">${field('Favorite name','name',t.name||t.merchant||'','text','required maxlength="80"')}${field('Amount (RM)','amount',typeof t.amount==='number'?amount(t.amount):t.amount||'','text','required inputmode="decimal"')}${acctSelect(t.account)}${categorySelect(t.category)}${field('Merchant','merchant',t.merchant||'','text','maxlength="120"')}${formFooter('Save favorite')}</form>`)}
function showFavorites(){modal('Quick favorites',`${state.favorites.map(f=>`<div class="setting-row"><div class="grow"><h3>${esc(f.name)}</h3><p>RM ${esc(f.amount)} · ${esc(tr(f.category))} · ${esc(account(f.account).name)}</p></div>${actionButton('Use','useFavorite',f.id)}${actionButton('Edit','editFavorite',f.id,'ghost')}${actionButton('Remove','deleteFavorite',f.id,'ghost')}</div>`).join('')||'<p class="note">Save common purchases for one-tap entry on Home and the Android app icon.</p>'}${actionButton('Create favorite','editFavorite')}`)}
function ruleForm(r={}){modalContext=r;modal('Merchant suggestion rule',`<form data-extra="rule">${field('Merchant contains','match',r.match||'','text','required maxlength="120" placeholder="e.g. Grab"')}${categorySelect(r.category)}${subcategorySelect(r.category||'Miscellaneous',r.subcategory||'')}${select('Suggest account','account',[['','Leave for me to choose'],...state.accounts.map(a=>[a.id,a.name])],r.account||'')}${select('Limit to source','source',[['','Any source'],...state.sources.map(s=>[s.package,s.name])],r.source||'')}${field('Priority (0–100)','priority',r.priority??10,'number','min="0" max="100"')}${check('Enabled','enabled',r.enabled!==false)}<p class="note">Preview: future merchants containing “${esc(r.match||'your text')}” get these suggestions. Saved transactions stay as recorded.</p><div id="rule-preview" class="note"></div>${formFooter('Save rule')}</form>`)}
function automationForm(){const s=state.settings;modal('Reminders & privacy',`<form data-extra="automation">${check('Automatically record notifications using history / Miscellaneous','autoRecord',s.autoRecord!==false)}${check('Auto-reconcile incoming DuitNow split transfers','splitAutoMatch',s.splitAutoMatch!==false)}${check('Read payment notifications from all bank & wallet apps','captureAllSources',s.captureAllSources!==false)}${check('Include category badge & budget progress in notifications','categoryAlerts',s.categoryAlerts!==false)}${check('Spend anomaly & high-purchase guard (≥ RM 50 & ≥ 2.5× median)','anomalyGuard',s.anomalyGuard!==false)}${check('Low-power Eco notification engine (saves RAM & battery)','ecoCapture',s.ecoCapture!==false)}${check('Always-on persistent task scheduler (auto-restarts unless Force Stopped)','alwaysOnScheduler',s.alwaysOnScheduler!==false)}${check('Auto-open MoneyLens when phone restarts','autoOpenOnBoot',s.autoOpenOnBoot!==false)}${check('BNPL, PTPTN, bill and cancellation reminders','reminders',s.reminders!==false)}${check('Budget threshold alerts','budgetAlerts',s.budgetAlerts!==false)}${check('Weekly budget pace nudge (respects quiet hours)','weeklyBudgetNudge',!!s.weeklyBudgetNudge)}${field('Budget alert threshold (%)','threshold',s.threshold||80,'number','min="1" max="100" required')}${check('One daily Inbox digest instead of individual prompts','digest',s.digest)}${field('Digest hour (0–23)','digestHour',s.digestHour??19,'number','min="0" max="23" required')}${check('Quiet hours','quietEnabled',s.quietEnabled)}<div class="form-grid">${field('Quiet from hour','quietStart',s.quietStart??22,'number','min="0" max="23" required')}${field('Quiet until hour','quietEnd',s.quietEnd??8,'number','min="0" max="23" required')}</div>${check('Show available estimate on home-screen widget','widgetAmounts',s.widgetAmounts)}${check('Capture while MoneyLens is closed or locked','captureWhileLocked',s.captureWhileLocked!==false)}<p class="note">Always-on task scheduler keeps auto-capture and BNPL/PTPTN due reminders active continuously across restarts.</p>${formFooter('Save preferences')}</form>`)}
function categoryNotificationLibrary(){
  const s=state.settings,thr=s.threshold||80;
  const rows=CAT_NOTIFICATION_PRESETS.map(p=>{
    const cm=catMeta(p.category);
    return`<div class="lib-card"><div class="lib-card-head"><div class="icon-bg cat-ico-badge tone-${cm.tone}"><span class="cat-emoji">${cm.emoji}</span></div><div class="grow"><strong>${esc(tr(p.category))} › ${esc(p.sub)}</strong><small class="muted">${esc(p.source)}</small></div></div><p class="lib-sample">${esc(p.sample)}</p><div class="lib-actions"><button type="button" class="btn secondary small" data-action="testPreset" data-preset="${esc(p.key)}">${icon('bell')}${esc(tr('Send Test'))}</button><button type="button" class="btn ghost small" data-action="testCategoryAlert" data-cat="${esc(p.category)}">${esc(tr('Test Alert'))}</button></div></div>`;
  }).join('');
  modal(tr('Category Notification Library'),`<p class="note">${esc(tr('Test category-aware payment capture and budget alerts ({thr}% warning / >100% over limit).',{thr}))}</p><div class="flex between" style="margin:10px 0 14px"><span class="tag">${esc(tr(s.categoryAlerts!==false?'Rich category badges ON':'Rich category badges OFF'))} · ${thr}%</span>${actionButton(tr('Alert Preferences'),'automation','','ghost')}</div><div class="lib-grid">${rows}</div>`);
}
function diffMarkup(preview,snapId=''){
  const d=preview?.diff||preview||{};
  const c=d.current||{},n=d.candidate||{};
  const deltaBadge=(v,isMoney=false)=>{
    if(!v)return '<span class="muted">0</span>';
    const cls=v>0?'diff-pos':'diff-neg';
    const txt=isMoney?(v>0?'+':'−')+money(Math.abs(v)):(v>0?'+'+v:String(v));
    return `<strong class="${cls} money">${esc(txt)}</strong>`;
  };
  const rows=[
    [tr('Active transactions'),c.txCount||0,n.txCount||0,deltaBadge(d.txDelta||0)],
    [tr('Accounts'),c.accountCount||0,n.accountCount||0,deltaBadge(d.accountDelta||0)],
    [tr('Budgets'),c.budgetCount||0,n.budgetCount||0,deltaBadge(d.budgetDelta||0)],
    [tr('Active bills'),c.billCount||0,n.billCount||0,deltaBadge(d.billDelta||0)],
    [tr('Saved receipts'),c.receiptCount||0,n.receiptCount||0,deltaBadge(preview?.receiptCount!==undefined?(preview.receiptCount-(c.receiptCount||0)):(d.receiptDelta||0))],
    [tr('Recorded net balance'),signedMoney(c.netBalance||0),signedMoney(n.netBalance||0),deltaBadge(d.netBalanceDelta||0,true)]
  ];
  const warn=d.wouldLoseData?`<div class="anomaly-banner"><strong>⚠ ${esc(tr('Warning: Backup contains fewer records than your current ledger.'))}</strong></div>`:'';
  const confirmAct=snapId?actionButton(tr('Restore this snapshot'),'restoreSnapshot',snapId,'danger'):actionButton(tr('Confirm & Restore Backup'),'confirmRestore','','danger');
  const cancelAct=snapId?actionButton(tr('Back'),'snapshotHistory','','ghost'):actionButton(tr('Cancel'),'cancelRestore','','ghost');
  return `${warn}<p class="note">${esc(tr('Review changes before replacing your device ledger. Your current state is automatically retained locally as a safety rollback copy.'))}</p><div class="diff-grid"><div class="diff-head"><span>${esc(tr('Metric'))}</span><span>${esc(tr('Current'))}</span><span>${esc(tr('Backup'))}</span><span>${esc(tr('Delta'))}</span></div>${rows.map(([l,cv,nv,dv])=>`<div class="diff-row"><span>${esc(l)}</span><span class="money">${esc(cv)}</span><strong class="money">${esc(nv)}</strong><span>${dv}</span></div>`).join('')}</div>${n.latestDate?`<p class="note">${esc(tr('Latest transaction in backup'))}: <strong>${esc(dateLabel(n.latestDate))}</strong></p>`:''}<div class="form-footer" style="margin-top:16px">${confirmAct}${cancelAct}</div>`;
}
window.restorePreviewLoaded=preview=>{
  modalContext={isRestorePreview:true};
  modal(tr('Restore Diff Preview'),diffMarkup(preview));
};
function computeLocalHealthCheck(){
  const txs=validTx(),unverified=state.accounts.filter(a=>!a.balanceVerified).length;
  const uncat=txs.filter(t=>t.kind==='expense'&&(t.category==='Miscellaneous'||t.needsNote)).length;
  const pending=state.drafts.filter(d=>d.state==='review').length;
  const bakOk=!!(status?.backupSuccess||status?.lastSnapshotAt);
  const lockOk=!!state.settings?.appLock;
  const score=(25)+(uncat===0?20:12)+(unverified===0?15:8)+(pending<=2?10:5)+(bakOk?20:0)+(lockOk?10:4);
  return {
    score,status:score>=80?'healthy':score>=50?'attention':'action',
    checks:[
      {id:'integrity',title:'Double-entry ledger & key integrity',ok:true,points:25,maxPoints:25,detail:'All recorded postings balance.',action:''},
      {id:'backup',title:'Encrypted backup & local snapshot freshness',ok:bakOk,points:bakOk?20:0,maxPoints:20,detail:bakOk?'Recent encrypted copy recorded.':'No encrypted backup or local snapshot recorded yet.',action:'snapshotNow'},
      {id:'categories',title:'90-day categorization hygiene',ok:uncat===0,points:uncat===0?20:12,maxPoints:20,detail:uncat===0?'No uncategorized transactions.':`${uncat} expense(s) need category/note review.`,action:'openNeedsNote'},
      {id:'accounts',title:'Verified account balances',ok:unverified===0,points:unverified===0?15:8,maxPoints:15,detail:unverified===0?'All accounts verified.':`${unverified} account(s) need balance verification.`,action:'openAccounts'},
      {id:'inbox',title:'Capture inbox backlog',ok:pending<=2,points:pending<=2?10:5,maxPoints:10,detail:`${pending} pending capture review(s).`,action:'openInbox'},
      {id:'security',title:'Biometric & screen lock protection',ok:lockOk,points:lockOk?10:4,maxPoints:10,detail:lockOk?'App lock enabled.':'App lock is off.',action:'enableLock'}
    ]
  };
}
function renderHealthModal(h){
  const tone=h.status==='healthy'?'healthy':h.status==='attention'?'attention':'action';
  const lbl=h.status==='healthy'?tr('Healthy'):h.status==='attention'?tr('Needs attention'):tr('Action recommended');
  const actionMap={restore:tr('Restore backup'),snapshotNow:tr('Snapshot now'),openNeedsNote:tr('Review items'),openAccounts:tr('Verify accounts'),openInbox:tr('Open inbox'),enableLock:tr('Enable lock')};
  modal(tr('Vault Health Check'),`<div class="health-head"><div class="health-score-ring ${tone}"><strong>${h.score}</strong><span>/100</span></div><div class="grow"><h3>${esc(tr('Overall Vault Health'))}: ${esc(lbl)}</h3><p class="note">${esc(tr('Offline security, backup freshness & double-entry integrity diagnostics.'))}</p></div></div><div class="stack" style="gap:8px;margin-top:12px">${(h.checks||[]).map(c=>`<div class="health-row"><div class="icon-bg ${c.ok?'sage':'peach'}">${icon(c.ok?'check':'bell')}</div><div class="grow"><strong>${esc(tr(c.title))} <span class="tag">${c.points}/${c.maxPoints}</span></strong><p class="note">${esc(c.detail)}</p></div>${(!c.ok&&c.action&&actionMap[c.action])?actionButton(actionMap[c.action],c.action,'','ghost'):''}</div>`).join('')}</div><div class="form-footer" style="margin-top:14px"><button type="button" class="btn secondary small" data-action="healthCheck">${esc(tr('Refresh score'))}</button></div>`);
}
function renderSnapshotsModal(list){
  const rows=Array.isArray(list)?list:[];
  modal(tr('Encrypted Local Snapshots (5-Slot Ring)'),`<p class="note">${esc(tr('MoneyLens stores up to 5 encrypted local snapshots automatically inside protected storage for instant 1-tap rollback.'))}</p><div class="tools-grid" style="margin:10px 0 14px">${actionButton(tr('Save Encrypted Snapshot Now'),'snapshotNow')}</div>${rows.length?rows.map(s=>`<div class="plan-row"><div class="flex between"><div><strong>${esc(new Date(s.created||Date.now()).toLocaleString(appLocale()))}</strong> <span class="tag">${esc(tr(s.reason==='auto'?'Auto':'Manual'))}</span><p class="note">${esc(tr('{n} txns · {amt}',{n:s.txCount||0,amt:signedMoney(s.netBalance||0)}))} · ${Math.max(1,Math.round((s.sizeBytes||0)/1024))} KB</p></div>${actionButton(tr('Preview Diff'),'previewSnapshot',s.id,'ghost')}</div></div>`).join(''):`<p class="note">${esc(tr('No local encrypted snapshots yet. Tap "Save Encrypted Snapshot Now" above.'))}</p>`}`);
}

window.csvLoaded=info=>{importInfo=info;const am=info.autoMap||{};const rows=[[-1,tr('Not mapped')],...info.headers.map((h,i)=>[i,h||'Column '+(i+1)])];const pick=(k,fb=k)=>{if(typeof am[k]==='number'&&am[k]>=0)return am[k];return info.headers.findIndex(h=>h.toLowerCase().includes(fb));};const cols=[['date',tr('Date column'),'date'],['amount',tr('Amount column (single)'),'amount'],['debit',tr('Debit / Money Out column'),'debit'],['credit',tr('Credit / Money In column'),'credit'],['merchant',tr('Merchant / Description column'),'merchant'],['category',tr('Category column'),'category'],['kind',tr('Type column'),'kind'],['note',tr('Note column'),'note']];const presetHtml=am.preset?`<div class="soft-card" style="margin:0 0 12px"><span class="muted">${esc(tr('Detected bank format'))}:</span> <strong>${esc(am.preset)}</strong></div>`:'';modal(tr('Map your statement columns'),`<form data-extra="csvMap">${presetHtml}<p class="note">${esc(tr('{n} rows. Choose how your file maps to MoneyLens, then review before saving.',{n:info.count}))}</p>${cols.map(([k,lbl,fb])=>select(lbl,k,rows,pick(k,fb))).join('')}${acctSelect()}${select(tr('Date format'),'dateFormat',[['iso','YYYY-MM-DD'],['dmy','DD/MM/YYYY'],['dmmmy','DD MMM YYYY'],['mdy','MM/DD/YYYY']],am.dateFormat||'iso')}${select(tr('Default transaction type'),'defaultKind',[['expense',tr('Expense')],['income',tr('Income')],['refund',tr('Refund')]],'expense')}${check(tr('Signed amounts: negative expenses, positive income'),'signed',!!am.signed)}<details><summary>${esc(tr('First rows'))}</summary><pre class="csv-sample">${esc(info.sample.map(r=>r.join(' | ')).join('\n'))}</pre></details>${formFooter(tr('Preview import'))}</form>`)};
function csvReview(rows){importPreview=rows;modal('Review CSV import',`<form data-extra="csvCommit"><p class="note">Possible duplicates start unchecked. Re-imported rows are blocked. Transfers need manual entry.</p><div class="review-list">${rows.map((r,i)=>`<label class="import-row"><input type="checkbox" name="row" value="${i}" ${r.error||r.duplicate?'disabled':r.possibleDuplicate?'':'checked'}><span><strong>${esc(r.merchant||'Row '+r.row)} ${r.amount?'· RM '+esc(r.amount):''}</strong><small>${esc(r.error||(r.date+' · '+r.kind+' · '+r.category))}</small>${r.duplicate?'<b>Already imported</b>':r.possibleDuplicate?'<b>Possible duplicate — check manually</b>':''}</span></label>`).join('')}</div>${formFooter('Import selected rows')}</form>`)}
function isSafeBatchDraft(d){
  if(!d||!(d.amount>0)||d.batchError)return false;
  const k=d.kind||'expense';
  if(k==='reimbursement'&&!d.reimbursementFor)return false;
  if(k==='transfer'&&!d.reload&&!d.to&&!d.transferHint)return false;
  return true;
}
function batchForm(){
  const ds=state.drafts.filter(d=>d.state==='review').slice(0,100);
  const defAcct=state.settings.lastAccount||state.accounts[0]?.id||'cash';
  const acctOpts=state.accounts.map(a=>[a.id,a.name]);
  const catOpts=activeCategories().map(c=>[c[0],tr(c[0])]);
  const kindOpts=[['expense',tr('Expense')],['income',tr('Income')],['transfer',tr('Transfer')],['reload',tr('Reload')],['refund',tr('Refund')]];
  const rowsHtml=ds.map(d=>{
    const rk=d.reload&&d.kind==='transfer'?'reload':(d.kind||'expense');
    const rAcct=d.account||defAcct;
    const isMove=rk==='transfer'||rk==='reload';
    const destRows=rk==='reload'?[['',tr('Same account (no balance change)')],...acctOpts]:[['',tr('Choose To account')],...acctOpts];
    const rCat=d.category||(rk==='income'?'Other income':'Miscellaneous');
    const errHtml=d.batchError?`<p class="error" style="display:block;margin:4px 0 0;font-size:11px">⚠ ${esc(d.batchError)}</p>`:'';
    return `<div class="import-row" data-batch-row="${esc(d.id)}" style="flex-direction:column;align-items:stretch;gap:6px"><label style="display:flex;gap:8px;align-items:flex-start"><input type="checkbox" name="draft" value="${esc(d.id)}" data-safe="${isSafeBatchDraft(d)?'1':'0'}" ${d.amount>0?(d.batchError?'checked':''):'disabled'}><span><strong>${esc(d.merchant||d.category||'Payment')} · ${money(d.amount)}</strong> <small class="muted">[${esc(rk)}]</small>${d.transferHint?` <small class="tag">to ${esc(d.transferHint)}</small>`:''}<br><small class="muted">${esc(d.date||today())} · ${esc(d.reason||'')}</small></span></label>${errHtml}<div class="form-grid" style="gap:6px"><select data-batch-field="account" aria-label="Row account" style="min-height:34px;font-size:12px">${options(acctOpts,rAcct)}</select><select data-batch-field="kind" aria-label="Row type" style="min-height:34px;font-size:12px">${options(kindOpts,rk)}</select></div><div data-batch-dest><select data-batch-field="${isMove?'to':'category'}" aria-label="${isMove?'Row destination':'Row category'}" style="min-height:34px;font-size:12px">${options(isMove?destRows:catOpts,isMove?(d.to||''):rCat)}</select></div></div>`;
  }).join('');
  modal(tr('Review selected payments'),`<form data-extra="batch"><p class="note">${esc(tr('Edit any row account, type or category below. Valid rows save even if another row needs attention.'))}</p><div class="flex" style="gap:8px;align-items:end;flex-wrap:wrap;margin-bottom:8px"><div style="flex:1;min-width:150px">${acctSelect(defAcct)}</div>${actionButton(tr('Apply to all'),'applyBatchAcct','','ghost')}${actionButton(tr('Select all safe'),'selectSafeBatch','','secondary')}</div><div class="review-list">${rowsHtml}</div>${formFooter(tr('Record selected payments'))}${actionButton(tr('Ignore selected'),'ignoreSelected','','ghost')}</form>`);
}
function linkForm(id,bill=false){modalContext={id};modal(bill?'Match an existing payment':'Already recorded?',`<form data-extra="${bill?'matchBill':'linkDraft'}"><p class="note">Choose the same payment already in your ledger. This links the record without creating another expense.</p>${select('Recorded transaction','transactionId',validTx().filter(t=>!['opening','adjustment'].includes(t.kind)).sort((a,b)=>b.date.localeCompare(a.date)).slice(0,200).map(t=>[t.id,t.date+' · '+(t.merchant||t.category)+' · '+money(t.amount)]),'')}${formFooter('Link payment')}</form>`)}

document.addEventListener('click',async e=>{
  const b=e.target.closest('button,[data-action="detail"]');
  if(!b||b.disabled)return;
  if(b.dataset.page){page=b.dataset.page;render();window.scrollTo(0,0);return}
  if(b.dataset.filter){filter=b.dataset.filter;searchState.ids=null;render();if(query.trim().length>=2)scheduleNativeSearch();return}
  if(b.dataset.plan){planTab=b.dataset.plan;render();return}
  if(b.dataset.kind){const data=Object.fromEntries(new FormData($('#transaction-form')).entries());txForm({...modalContext,...data,kind:b.dataset.kind});return}
  const action=b.dataset.action,id=b.dataset.id;if(!action)return;
  try{
    switch(action){
      case 'retry':await window.nativeRefresh();break;
      case 'close':close();break;
      case 'add':txForm();break;
      case 'setAccent':{const acc=b.dataset.accent||'emerald';state.settings.accent=acc;render();if(window.MoneyLensNative)await mutate('settings',{accent:acc},`Theme set to ${acc}.`);break;}
      case 'toggleBlur':{const next=!state.settings.privacyBlur;state.settings.privacyBlur=next;render();if(window.MoneyLensNative)await mutate('settings',{privacyBlur:next},next?'Balances hidden for privacy.':'Balances visible.');break;}
      case 'lockNow':if(window.MoneyLensNative)await call('lockNow');else modal(tr('MoneyLens Vault Locked'),`<div style="text-align:center;padding:12px 0"><div class="icon-bg" style="margin:0 auto 12px">${icon('lock')}</div><p class="note">${esc(tr('Require phone fingerprint, face or screen lock when opening MoneyLens'))}</p><button class="btn" data-action="close">${icon('shield')}${esc(tr('Unlock with Fingerprint'))}</button></div>`);break;
      case 'transfer':if(state.accounts.length<2){toast('Add a second account to make a transfer.');accountForm();}else txForm({kind:'transfer'});break;
      case 'prevMonth':monthOffset--;render();break;
      case 'nextMonth':monthOffset++;render();break;
      case 'selectCalDay':{const d=b.dataset.date;if(advancedFilters.exactDate===d)delete advancedFilters.exactDate;else advancedFilters.exactDate=d;searchState.ids=null;render();if(query.trim().length>=2)scheduleNativeSearch();break;}
      case 'filterCat':{const c=b.dataset.cat;delete advancedFilters.needsNote;if(!c||advancedFilters.category===c)delete advancedFilters.category;else advancedFilters.category=c;searchState.ids=null;render();if(query.trim().length>=2)scheduleNativeSearch();break;}
      case 'filterNeedsNote':{delete advancedFilters.category;if(advancedFilters.needsNote)delete advancedFilters.needsNote;else advancedFilters.needsNote=true;searchState.ids=null;render();if(query.trim().length>=2)scheduleNativeSearch();break;}
      case 'clearFilters':advancedFilters={};searchState.ids=null;render();if(query.trim().length>=2)scheduleNativeSearch();break;
      case 'addAccount':accountForm();break;
      case 'addBudget':budgetForm();break;
      case 'editBudget':budgetForm(state.budgets.find(x=>x.id===id));break;
      case 'addBill':billForm();break;
      case 'editBill':billForm(state.bills.find(x=>x.id===id));break;
      case 'addGoal':goalForm();break;
      case 'editGoal':goalForm(state.goals.find(x=>x.id===id));break;
      case 'goalDelta':await mutate('adjustGoal',{id,delta:Number(b.dataset.delta||0)},Number(b.dataset.delta)>=0?tr('Goal savings updated.'):tr('Withdrew from goal.'));break;
      case 'addDebt':debtForm();break;
      case 'editDebt':debtForm((state.debts||[]).find(x=>x.id===id)||{});break;
      case 'debtStrategy':debtStrategy=id==='snowball'?'snowball':'avalanche';render();break;
      case 'syncDebtReminder':await mutate('syncDebtReminder',{id},'Monthly bill reminder synced.');break;
      case 'presetDebt':{
        const presets={ptptn:{name:'PTPTN Study Loan',type:'ptptn',balance:1500000,principal:1500000,minPayment:20000,aprBps:100,rateType:'flat',dueDay:25},spaylater:{name:'SPayLater BNPL',type:'bnpl',balance:60000,minPayment:15000,aprBps:0,rateType:'reducing',dueDay:10},payflex:{name:'Boost PayFlex',type:'bnpl',balance:40000,minPayment:10000,aprBps:0,rateType:'reducing',dueDay:1},creditcard:{name:'Credit Card Balance',type:'credit',balance:250000,minPayment:12500,aprBps:1800,rateType:'reducing',dueDay:15}};
        debtForm(presets[id]||{});break;
      }
      case 'presetReminder':{
        const presets={ptptn:{name:'PTPTN Study Loan Repayment',reminderType:'ptptn',category:'Loans, BNPL & Credit',subcategory:'PTPTN Study Loan',amount:15000,frequency:'monthly',remindDaysBefore:3,remindTime:'09:00'},spaylater:{name:'SPayLater (Shopee BNPL)',reminderType:'bnpl',category:'Loans, BNPL & Credit',subcategory:'BNPL (SPayLater / GrabPayLater / Atome)',amount:5000,frequency:'monthly',remindDaysBefore:3,remindTime:'10:00'},grabpaylater:{name:'GrabPayLater Monthly Bill',reminderType:'bnpl',category:'Loans, BNPL & Credit',subcategory:'BNPL (SPayLater / GrabPayLater / Atome)',amount:5000,frequency:'monthly',remindDaysBefore:3,remindTime:'10:00'},atome:{name:'Atome BNPL Installment',reminderType:'bnpl',category:'Loans, BNPL & Credit',subcategory:'BNPL (SPayLater / GrabPayLater / Atome)',amount:5000,frequency:'everyXDays',intervalDays:30,remindDaysBefore:3,remindTime:'10:00'},payflex:{name:'Boost PayFlex Monthly',reminderType:'bnpl',category:'Loans, BNPL & Credit',subcategory:'BNPL (SPayLater / GrabPayLater / Atome)',amount:5000,frequency:'monthly',remindDaysBefore:3,remindTime:'10:00'},lazpaylater:{name:'LazPayLater Monthly',reminderType:'bnpl',category:'Loans, BNPL & Credit',subcategory:'BNPL (SPayLater / GrabPayLater / Atome)',amount:5000,frequency:'monthly',remindDaysBefore:3,remindTime:'10:00'},creditcard:{name:'Credit Card Statement Due',reminderType:'credit',category:'Loans, BNPL & Credit',subcategory:'Credit Card Bill',amount:20000,frequency:'monthly',remindDaysBefore:3,remindTime:'09:00'}};
        billForm(presets[id]||{});break;
      }
      case 'quickCatPick':{
        const f=$('.modal form[data-extra="quickNote"]');
        if(f&&f.elements.category){
          const catName=b.dataset.cat||'',subName=b.dataset.sub||'';
          f.elements.category.value=catName;
          if($('#f-subcategory')){
            $('#f-subcategory').innerHTML=subcatOptions(catName,subName);
            $('#f-subcategory').value=subName;
          }
          [...f.querySelectorAll('.quick-cat-chips .chip')].forEach(c=>c.classList.toggle('active',c===b));
        }
        break;
      }
      case 'sourceGroup':{sourceGroup=id||'all';renderSources($('#source-search')?.value||'');break;}
      case 'testReminder':await mutate('testReminder',{id},'Test reminder notification posted!');break;
      case 'detail':detail(id);break;
      case 'editTx':{const txRow=state.transactions.find(x=>x.id===id);txForm({...txRow,key:cryptoId(),editId:txRow.id});break;}
      case 'refundTx':{const txRow=state.transactions.find(x=>x.id===id);txForm({kind:'refund',amount:txRow.amount,merchant:'Refund · '+(txRow.merchant||txRow.category),account:txRow.account,category:txRow.category,subcategory:txRow.subcategory,originalId:txRow.id});break;}
      case 'quickNote':quickNoteForm(id);break;
      case 'applyHistoryYes':await mutate('updateMerchantHistory',modalContext,'Updated previous records and saved rule for future.');close();break;
      case 'quickRemove':await mutate('void',{id},'Transaction removed.');if($('#modal-root').innerHTML)close();break;
      case 'confirmVoid':modal('Remove this transaction?',`<p class="note">Its effect on balances and budgets will be reversed. The record remains in the correction history.</p><div class="form-footer" style="margin-top:20px"><button class="btn ghost" data-action="close">Keep it</button><button class="btn danger" data-action="void" data-id="${esc(id)}">Remove</button></div>`);break;
      case 'void':await mutate('void',{id});modal('Transaction removed',`<p class="note">Your balances and budgets have been updated.</p><div class="form-footer" style="margin-top:20px"><button class="btn ghost" data-action="undoVoid" data-id="${esc(id)}">Undo removal</button><button class="btn" data-action="close">${esc(tr('Done'))}</button></div>`);break;
      case 'undoVoid':await mutate('void',{id,restore:true},'Transaction restored.');close();break;
      case 'unlinkBill':await mutate('unlinkBill',{id},'Bill link removed. The payment is kept and the bill due date is restored.');break;
      case 'dismissAnomaly':await mutate('dismissAnomaly',{id},tr('Anomaly alert dismissed.'));if($('#modal-root').innerHTML)close();break;
      case 'unmergeDuplicate':await mutate('unmergeDuplicate',{id},'Recorded as a separate payment.');break;
      case 'payBill':{const bill=state.bills.find(x=>x.id===id);txForm({kind:bill.kind||'expense',to:bill.to,merchant:bill.name,amount:bill.amount,account:bill.account,category:bill.category,subcategory:bill.subcategory,date:today(),billId:id,billDate:bill.date});break;}
      case 'cancelBill':{const bill=state.bills.find(x=>x.id===id);await mutate('cancelBill',{id,cancelled:!bill.cancelled},'Plan updated.');close();break;}
      case 'deleteBillExtra':await mutate('deletePlan',{table:'bills',id},'Reminder removed.');close();break;
      case 'deletePlan':await mutate('deletePlan',{table:b.dataset.table,id},'Plan removed.');close();break;
      case 'subscription':billForm({...reportData.subscriptions[Number(id)],subscription:true});break;
      case 'updateBillHike':{
        const h=(reportData?.priceHikes||[])[Number(id)];
        if(h&&h.billId)await mutate('updateBillAmount',{id:h.billId,amountMinor:h.latestAmount},tr('Bill reminder updated to new amount.'));
        else if(h)billForm({name:h.merchant,amount:h.latestAmount,account:h.account,category:h.category,frequency:h.frequency||'monthly',subscription:true});
        break;
      }
      case 'quickParseFill':{
        const qText=($('#quick-parse-input')?.value||'').trim();
        const parsed=window.MoneyLensNative?await call('quickParse',{text:qText}):localQuickParse(qText);
        if(!parsed||!parsed.amountMinor){toast(tr('Enter an amount like "Nasi Lemak 12.50 cash".'));break;}
        txForm({...parsed,key:modalContext.key,focusSubmit:true});
        toast(tr('Smart fields filled from your note.'));
        break;
      }
      case 'fxPreset':{
        const f=$('#transaction-form');
        if(f){
          f.elements.originalCurrency.value=id;
          const rt=Number(b.dataset.rate||1),orig=Number(f.elements.originalAmount.value||0),myr=Number(f.elements.amount.value||0);
          if(orig>0&&!myr)f.elements.amount.value=(orig*rt).toFixed(2);
          else if(myr>0&&!orig)f.elements.originalAmount.value=(myr/rt).toFixed(2);
        }
        break;
      }
      case 'review':{const d=state.drafts.find(x=>x.id===id);txForm({...d,draftId:id,draftRevision:d.revision||1});break;}
      case 'ignore':await mutate('ignore',{id},'Suggestion ignored.');break;
      case 'sample':await mutate('sample',{autoCapture:state.settings.autoRecord!==false},state.settings.autoRecord!==false?'Sample payment auto-recorded.':'Sample suggestion added.');break;
      case 'testNotification':await mutate('testNotification',{},'Test payment notification sent & auto-recorded!');break;
      case 'catNotifLibrary':categoryNotificationLibrary();break;
      case 'testPreset':await mutate('testNotification',{preset:b.dataset.preset||''},'Category sample payment notification sent!');break;
      case 'testCategoryAlert':{const catName=b.dataset.cat||'Food & drinks';if(window.MoneyLensNative){await call('testCategoryAlert',{category:catName});toast(`Sent ${catName} budget alert notification!`);}else toast(`Preview: Budget alert for ${catName} ready on Android.`);break;}
      case 'captureSettings':page='settings';render();break;
      case 'access':await call('notificationAccess');break;
      case 'prompts':await call('promptPermission');break;
      case 'overlayPermission':await call('overlayPermission');break;
      case 'batteryOptimization':await call('batteryOptimization');break;
      case 'sources':await sources();break;
      case 'backup':await call('backup');break;
      case 'restore':await call('restore');break;
      case 'csvExport':await call('csvExport');break;
      case 'exportPdf':await call('exportPdf',{date:period().start});break;
      case 'openRecap':{const rc=(window.MoneyLensNative&&monthOffset!==0)?await call('cycleRecap',{date:period().start}):null;showRecap(rc);break;}
      case 'openCycleReview':{
        page='plan';planTab='review';
        if(window.MoneyLensNative&&monthOffset!==0){modalContext.cycleReviewStart=period().start;modalContext.cycleReviewRecap=await call('cycleRecap',{date:period().start});}
        render();window.scrollTo(0,0);break;
      }
      case 'reviewPrevMonth':{
        monthOffset--;
        if(window.MoneyLensNative&&monthOffset!==0){modalContext.cycleReviewStart=period().start;modalContext.cycleReviewRecap=await call('cycleRecap',{date:period().start});}
        else{modalContext.cycleReviewRecap=null;}
        render();break;
      }
      case 'reviewNextMonth':{
        monthOffset=Math.min(0,monthOffset+1);
        if(window.MoneyLensNative&&monthOffset!==0){modalContext.cycleReviewStart=period().start;modalContext.cycleReviewRecap=await call('cycleRecap',{date:period().start});}
        else{modalContext.cycleReviewRecap=null;}
        render();break;
      }
      case 'snoozeBackupNudge':{
        const until=Date.now()+7*86400000;
        if(window.MoneyLensNative)await mutate('settings',{backupNudgeSnoozeUntil:until},tr('Backup reminder snoozed for 7 days.'));
        else{state.settings.backupNudgeSnoozeUntil=until;render();toast(tr('Backup reminder snoozed for 7 days.'));}
        break;
      }
      case 'dismissBackupNudge':{
        if(window.MoneyLensNative)await mutate('settings',{backupNudgeDismissed:true},tr('Backup reminder hidden. You can re-enable it in Settings.'));
        else{state.settings.backupNudgeDismissed=true;render();toast(tr('Backup reminder hidden. You can re-enable it in Settings.'));}
        break;
      }
      case 'resetBackupNudge':{
        if(window.MoneyLensNative)await mutate('settings',{backupNudgeDismissed:false,backupNudgeSnoozeUntil:0},tr('Backup reminder restored.'));
        else{state.settings.backupNudgeDismissed=false;state.settings.backupNudgeSnoozeUntil=0;render();toast(tr('Backup reminder restored.'));}
        break;
      }
      case 'applyOneSuggestedBudget':{
        const cat=b.dataset.cat||'',amt=b.dataset.amount||'50.00';
        if(!cat)break;
        await mutate('applyBudgetSuggestions',{items:[{category:cat,amount:amt}],rollover:true},tr('Budget created for {cat}.',{cat:tr(cat)}));
        break;
      }
      case 'applyAllSuggestedBudgets':{
        const sug=(monthOffset===0&&Array.isArray(reportData?.budgetSuggestions))?reportData.budgetSuggestions:computeLocalBudgetSuggestions();
        if(!sug.length){toast(tr('No new budget suggestions right now.'));break;}
        const items=sug.map(s=>({category:s.category,amount:(s.suggestedLimit/100).toFixed(2)}));
        await mutate('applyBudgetSuggestions',{items,rollover:true},tr('Applied {n} suggested budgets.',{n:items.length}));
        close();
        page='plan';planTab='budgets';render();
        break;
      }
      case 'planNextCycle':{
        const r=reportData?.recap||computeLocalRecap();
        const sug=(monthOffset===0&&Array.isArray(reportData?.budgetSuggestions))?reportData.budgetSuggestions:computeLocalBudgetSuggestions();
        const overCats=(r.topCategories||[]).filter(c=>(c.delta||0)>0&&(c.previous||0)>0).slice(0,4);
        const goalRows=(state.goals||[]).filter(g=>(g.saved||0)<(g.target||0)).slice(0,3);
        const sugRows=sug.length?sug.map(s=>`<div class="detail-line"><span>${esc(tr(s.category))} <small class="muted">(${esc(tr('Suggested'))})</small></span><strong class="money">${money(s.suggestedLimit)}</strong></div>`).join(''):`<p class="note">${esc(tr('Existing category budgets look balanced.'))}</p>`;
        const riseRows=overCats.length?`<h3 style="margin-top:12px">${esc(tr('Categories that rose this cycle'))}</h3>${overCats.map(c=>`<div class="detail-line"><span>${esc(tr(c.category))}</span><strong class="money">+${signedMoney(c.delta)} (+${c.deltaPct}%)</strong></div>`).join('')}`:'';
        const gRows=goalRows.length?`<h3 style="margin-top:12px">${esc(tr('Active goals to top up'))}</h3>${goalRows.map(g=>`<div class="detail-line"><span>${esc(g.name)}</span><strong class="money">${money(g.saved||0)} / ${money(g.target||0)}</strong></div>`).join('')}`:'';
        modal(tr('Plan Next Cycle'),`<p class="note">${esc(tr('Carry over active budgets cleanly or apply history-based limits for your next payday cycle.'))}</p><h3 style="margin-top:10px">${esc(tr('Suggested Budgets from History'))}</h3>${sugRows}${riseRows}${gRows}<div class="form-footer" style="margin-top:16px">${sug.length?actionButton(tr('Apply Suggested Budgets'),'applyAllSuggestedBudgets','','primary'):''}<button type="button" class="btn ghost" data-action="close">${esc(tr('Close'))}</button></div>`);
        break;
      }
      case 'insights':insights();break;
      case 'openTaxPlan':page='plan';planTab='tax';render();window.scrollTo(0,0);break;
      case 'openDebtPlan':page='plan';planTab='debts';render();window.scrollTo(0,0);break;
      case 'openBnplPlan':page='plan';planTab='bills';render();window.scrollTo(0,0);break;
      case 'openSplitsPlan':page='plan';planTab='splits';render();window.scrollTo(0,0);break;
      case 'healthCheck':{const h=window.MoneyLensNative?await call('healthCheck'):computeLocalHealthCheck();renderHealthModal(h);break;}
      case 'jumpSection':{const tid=b.dataset.target||'';const el=tid?document.getElementById(tid):null;if(el)el.scrollIntoView({behavior:'smooth',block:'start'});[...document.querySelectorAll('.settings-jump .chip')].forEach(c=>c.classList.toggle('active',c===b));break;}
      case 'snapshotHistory':{const list=window.MoneyLensNative?await call('snapshots'):[];renderSnapshotsModal(list);break;}
      case 'snapshotNow':{if(!window.MoneyLensNative){toast('Run on Android to save encrypted device snapshot.');break;}const inHealth=!!$('.health-head');const list=await call('snapshotNow');await window.nativeRefresh();if(inHealth){renderHealthModal(await call('healthCheck'));toast(tr('Encrypted local snapshot saved. Health score updated.'));}else{renderSnapshotsModal(list);toast(tr('Encrypted local snapshot saved.'));}break;}
      case 'previewSnapshot':{const prev=await call('previewSnapshot',{id});modal(tr('Snapshot Diff Preview'),diffMarkup(prev,id));break;}
      case 'restoreSnapshot':{await mutate('restoreSnapshot',{id},tr('Snapshot restored.'));close();break;}
      case 'confirmRestore':{await call('confirmRestore');close();break;}
      case 'cancelRestore':{if(window.MoneyLensNative)await call('cancelRestore');modalContext={};close();break;}
      case 'openNeedsNote':{page='transactions';advancedFilters={needsNote:true};close();render();break;}
      case 'openAccounts':{page='accounts';close();render();break;}
      case 'openInbox':{page='inbox';close();render();break;}
      case 'enableLock':{const inHealth=!!$('.health-head');await mutate('settings',{appLock:true},tr('Fingerprint app lock enabled.'));if(inHealth){renderHealthModal(window.MoneyLensNative?await call('healthCheck'):computeLocalHealthCheck());}else close();break;}
      case 'settlePerson':{
        const person=b.dataset.person||'';
        const amt=Number(b.dataset.amt||0);
        modal(tr('Settle {name}',{name:person}),`<form data-extra="settlePerson"><input type="hidden" name="person" value="${esc(person)}">${acctSelect(state.settings.lastAccount)}${field(tr('Settlement amount (RM)'),'amount',amt>0?amount(amt):'','text','required inputmode="decimal"')}${field(tr('Date'),'date',today(),'date','required')}${field(tr('Note'),'note','','text','maxlength="500" placeholder="e.g. DuitNow received"')}<p class="note">${esc(tr('FIFO-settles oldest open shared expenses for {name}.',{name:person}))}</p>${formFooter(tr('Confirm Settlement'))}</form>`);
        break;
      }
      case 'copySplitMsg':{
        const person=b.dataset.person||'';
        const ssum=reportData?.sharedSummary||computeLocalSharedSummary();
        const entry=(ssum.friends||[]).find(f=>f.person===person);
        const txt=entry?.shareMessage||'';
        if(!txt)break;
        try{if(navigator.clipboard?.writeText){await navigator.clipboard.writeText(txt);toast(tr('DuitNow split summary copied!'));break;}}catch(ignored){}
        modal(tr('DuitNow Request · {name}',{name:person}),`<p class="note">${esc(tr('Copy and paste this offline summary in WhatsApp or chat:'))}</p><textarea rows="6" readonly style="width:100%">${esc(txt)}</textarea><div class="form-footer">${actionButton(tr('Close'),'close')}</div>`);
        break;
      }
      case 'setPayday':{
        const cand=(reportData?.recurringIncome||[])[Number(id)];
        if(cand)await mutate('setPayday',{name:cand.name,amountMinor:cand.amount,account:cand.account,frequency:cand.frequency,dayOfMonth:cand.dayOfMonth,nextDate:cand.nextDate},`Payday & budget cycle synced to day ${cand.dayOfMonth}.`);
        break;
      }
      case 'clearPayday':await mutate('clearPayday',{},'Payday schedule cleared.');break;
      case 'recordMonthlySalary':{
        const p=state.settings.taxProfile||{};
        const est=reportData?.taxEstimate||computeLocalTax(p);
        const mp=est.monthlyPaycheck||computeLocalTax(p).monthlyPaycheck;
        const salSen=mp.netTakeHomeMonthly>0?mp.netTakeHomeMonthly:mp.grossMonthly;
        txForm({kind:'income',category:'Salary',merchant:'Monthly Net Salary (Take-Home)',amount:salSen>0?salSen:undefined,note:`Gross ${money(mp.grossMonthly)} · EPF ${money(mp.epfMonthly)} · PCB ${money(mp.pcbMonthly)}`});
        break;
      }
      case 'autoFillTaxReliefs':{
        const det=reportData?.taxEstimate?.ledgerDetected||{};
        const form=$('#tax-profile-form');
        if(form){
          for(const k of ['lifestyle','sports','medical','lifeInsurance','medicalInsurance','education','sspnPrs','zakat']){
            if(det[k]!==undefined&&form.elements[k])form.elements[k].value=(det[k]/100).toFixed(2);
          }
          toast('Filled reliefs from your recorded expenses. Tap Calculate & Save to apply.');
        }
        break;
      }
      case 'categories':categoriesModal();break;
      case 'removeCategory':await mutate('removeCategory',{name:b.dataset.name},`Removed category "${b.dataset.name}".`);categoriesModal();break;
      case 'removeSubcategory':await mutate('removeSubcategory',{category:b.dataset.category,subcategory:b.dataset.subcategory},`Removed subcategory "${b.dataset.subcategory}".`);categoriesModal();break;
      case 'scan':scanDialog();break;
      case 'receiptPick':case 'receiptCamera':await call(action,{language:$('.modal select').value});close();break;
      case 'paste':modal('Paste payment text',`<form data-extra="paste"><label for="pasteText">Notification or receipt text</label><textarea name="text" id="pasteText" maxlength="4000" required rows="6" placeholder="Paste the payment message here"></textarea><p class="note">Automatically parses and records the payment into your ledger.</p>${formFooter('Record payment')}</form>`);break;
      case 'addSplit':$('#split-rows').insertAdjacentHTML('beforeend',splitRow());break;
      case 'removeSplit':b.closest('.split-row').remove();break;
      case 'shared':txForm({kind:'shared'});break;
      case 'repay':{const r=(reportData?.receivables||[]).find(x=>x.id===id)||(()=>{const t=state.transactions.find(x=>x.id===id);return t?{id,person:t.person||'',remaining:t.amount}:{id,person:'',remaining:undefined};})();txForm({kind:'reimbursement',amount:r.remaining,reimbursementFor:id,merchant:'Repayment · '+r.person,category:'Repayment'});break;}
      case 'repeat':{const txRow=state.transactions.find(x=>x.id===id);if(['reimbursement','opening','adjustment'].includes(txRow.kind)){toast('Use the account reconciliation or repayment workflow for this record.');break}txForm({kind:txRow.kind,amount:txRow.amount,account:txRow.account,to:txRow.to,category:txRow.category,subcategory:txRow.subcategory,merchant:txRow.merchant,tags:txRow.tags,splits:txRow.splits,person:txRow.person,ownAmount:txRow.ownAmount,date:today()});break;}
      case 'favoriteFrom':favoriteForm(state.transactions.find(x=>x.id===id));break;
      case 'useFavorite':{const f=state.favorites.find(x=>x.id===id);txForm({...f,kind:'expense',date:today(),key:cryptoId()});break;}
      case 'favorites':showFavorites();break;
      case 'editFavorite':favoriteForm(state.favorites.find(f=>f.id===id)||{});break;
      case 'deleteFavorite':await mutate('deleteExtra',{table:'favorites',id});close();break;
      case 'rules':modal('Merchant rules',`${state.rules.map(r=>`<div class="setting-row"><div class="grow"><h3>${esc(r.match)}</h3><p>${esc(tr(r.category))}${r.subcategory?' › '+esc(r.subcategory):''} · ${r.enabled?'Enabled':'Paused'} · priority ${r.priority}</p></div>${actionButton('Edit','editRule',r.id)}${actionButton('Delete','deleteRule',r.id,'ghost')}</div>`).join('')||'<p class="note">Teach MoneyLens your own merchant suggestions.</p>'}${actionButton('Add rule','editRule')}${state.ruleHistory?.length?actionButton('Undo last rule change','undoRule','','ghost'):''}`);break;
      case 'editRule':ruleForm(state.rules.find(r=>r.id===id)||{});break;
      case 'undoRule':await mutate('undoRule',{},'Last rule change undone.');close();break;
      case 'deleteRule':await mutate('deleteExtra',{table:'rules',id});close();break;
      case 'automation':automationForm();break;
      case 'autoBackup':case 'backupNow':case 'stopAutoBackup':await call(action);await window.nativeRefresh();break;
      case 'csvPick':await call('csvPick');break;
      case 'imports':modal('Import history',`${state.imports.slice().reverse().map(imp=>`<div class="setting-row"><div class="grow"><h3>${esc(imp.date)} · ${imp.count} rows</h3><p>${imp.undone?'Undone':'Imported'} · ${esc(imp.name)}</p></div>${!imp.undone?actionButton('Undo import','undoImportConfirm',imp.id):''}</div>`).join('')||'<p class="note">No imports yet.</p>'}`);break;
      case 'undoImportConfirm':modal('Undo this import?',`<p class="note">Unedited records in this batch will be voided. Records you have corrected remain. The original file cannot be accidentally imported again.</p>${actionButton('Undo import','undoImport',id,'danger')}`);break;
      case 'undoImport':await mutate('undoImport',{id},'Import reversed. Edited records were kept.');close();break;
      case 'recoverConfirm':modal('Restore previous local copy?',`<p class="note">This replaces your current ledger with the copy retained before your last restore. Export a backup first if you want to keep current changes.</p>${actionButton('Restore local copy','recover','','danger')}`);break;
      case 'recover':await mutate('recover',{},'Previous local copy restored.');close();break;
      case 'diagnostics':{const d=await call('diagnostics');modalContext={diagnostics:JSON.stringify(d,null,2)};modal('Capture diagnostics',`<p class="note">This preview contains no merchants, amounts, account numbers or raw notifications.</p><pre class="csv-sample">${esc(JSON.stringify(d,null,2))}</pre>${actionButton('Export these diagnostics','diagnosticExport')}`);break;}
      case 'diagnosticExport':await call('diagnosticExport',{text:modalContext.diagnostics});break;
      case 'batch':batchForm();break;
      case 'selectSafeBatch':{
        const boxes=[...document.querySelectorAll('form[data-extra="batch"] input[name="draft"]:not(:disabled)')];
        let cnt=0;
        for(const cb of boxes){
          const rowEl=cb.closest('[data-batch-row]');
          const k=rowEl?.querySelector('[data-batch-field="kind"]')?.value||'';
          const dest=rowEl?.querySelector('[data-batch-field="to"]')?.value||'';
          const dObj=state.drafts.find(x=>x.id===cb.value);
          const safe=(cb.dataset.safe==='1')||(k==='reload')||(k==='transfer'&&!!dest);
          cb.checked=!!safe;if(cb.checked)cnt++;
        }
        toast(cnt?tr('{n} safe payment(s) selected.',{n:cnt}):tr('No complete payments ready for batch save.'));
        break;
      }
      case 'applyBatchAcct':{
        const def=$('form[data-extra="batch"] select[name="account"]')?.value;
        if(def)document.querySelectorAll('form[data-extra="batch"] [data-batch-field="account"]').forEach(sel=>{sel.value=def;});
        toast(tr('Account applied to all rows.'));
        break;
      }
      case 'ignoreSelected':{const ids=[...document.querySelectorAll('input[name=draft]:checked')].map(x=>x.value);if(!ids.length){toast('Select at least one payment first.');break;}await mutate('ignoreBatch',{ids},'Selected suggestions ignored.');close();break;}
      case 'linkDraft':linkForm(id);break;
      case 'matchBill':linkForm(id,true);break;
      case 'reconcile':modal('Compare with your statement',`<form data-extra="reconcile">${acctSelect()}${field('As-of date','date',today(),'date','required')}${field('Statement balance (RM)','actual','','text','required inputmode="decimal"')}<p class="note">For a card, enter debt as a negative balance, such as -125.50. You will review the difference before adjusting.</p>${field('Note','note','','text','maxlength="500"')}${formFooter('Compare balances')}</form>`);break;
      case 'saveReconcile':await mutate('reconcile',{...modalContext,adjust:false},'Balance check saved.');close();break;
      case 'adjustReconcile':await mutate('reconcile',{...modalContext,adjust:true},'Explicit balance adjustment saved.');close();break;
      case 'installment':modal('Plan an installment purchase',`<form data-extra="installment">${field('Purchase name','name','','text','required maxlength="80"')}${field('Purchase price (RM)','amount','','text','required inputmode="decimal"')}${field('Total fees or interest (RM)','fee','0','text','required inputmode="decimal"')}${field('Number of monthly payments','months','12','number','min="2" max="60" required')}${field('First payment date','date',today(),'date','required')}${acctSelect()}${categorySelect('Shopping')}<p class="note">The full purchase plus fees is recorded once as spending on a new installment liability account. Monthly payments are transfers that reduce this debt.</p>${formFooter('Create purchase and payment plan')}</form>`);modalContext={key:cryptoId()};break;
      case 'filters':modal('Find the records you need',`<form data-extra="filters"><div class="form-grid">${field('From date','from',advancedFilters.from||'','date')}${field('Through date','until',advancedFilters.until||'','date')}</div>${select('Account','account',[['','All accounts'],...state.accounts.map(a=>[a.id,a.name])],advancedFilters.account||'')}<div class="form-grid">${field('Minimum RM','min',advancedFilters.min||'','number','min="0" step="0.01"')}${field('Maximum RM','max',advancedFilters.max||'','number','min="0" step="0.01"')}</div>${field('Tag contains','tag',advancedFilters.tag||'')}${select('Source','source',[['','All sources'],['manual','Manual entry'],...[...new Set(validTx().map(t=>t.source).filter(Boolean))].map(s=>[s,s])],advancedFilters.source||'')}${formFooter('Apply filters')}</form>`);break;
      case 'history':{let cur=state.transactions.find(x=>x.id===id),rows=[cur];while(cur.replaces){cur=state.transactions.find(x=>x.id===cur.replaces);if(!cur)break;rows.push(cur)}modal('Correction history',rows.map((r,i)=>`<section class="soft-card"><h3>${i===0?'Current record':'Previous version'}</h3><p>${esc(r.date)} · ${esc(r.merchant)} · ${money(r.amount)}</p><p class="note">${esc(r.category)}${r.subcategory?' › '+esc(r.subcategory):''} · ${esc(r.note||'')} · ${r.voided?'Superseded or voided':'Active'}</p></section>`).join(''));break;}
      case 'preferences':modal(tr('Personal preferences'),`<form id="preferences-form">${field(tr('Display name'),'name',state.settings.name||'','text','maxlength="50"')}${select(tr('Budget cycle mode'),'cycleMode',[['monthly',tr('Calendar Monthly (1st to last day)')],['custom',tr('Custom payday cycle day')]],isMonthlyMode()?'monthly':'custom')}${field(tr('Monthly budget start day (1–31)'),'cycleDay',state.settings.cycleDay||1,'number','min="1" max="31" required')}<p class="note">${esc(tr('Calendar Monthly resets every 1st regardless of payday. Custom mode anchors on your chosen day each month.'))}</p><div class="error" role="alert"></div><button class="btn">${esc(tr('Save preferences'))}</button></form>`);break;
      case 'accountDetail':{const a=account(id),n=balance(id);modal(a.name,`<div class="detail-total money">${n<0?'−':''}${money(n)}</div><p class="note">Recorded balance · ${esc(a.type)}<br>For credit cards, a negative balance is money owed.</p><div style="margin-top:25px">${transactionList(validTx().filter(t=>t.kind!=='opening'&&t.postings.some(p=>p.account===id)).sort((a,b)=>b.date.localeCompare(a.date)).slice(0,8),false)}</div>`);break;}
    }
  }catch(err){toast(err.message)}
});

document.addEventListener('submit',async e=>{
  const form=e.target;
  if(!form.matches('form[data-extra], .modal form'))return;
  e.preventDefault();
  if(busy)return;
  busy=true;
  const button=form.querySelector('button[type=submit],button:not([type])');
  if(button)button.disabled=true;
  const args=Object.fromEntries(new FormData(form).entries());
  try{
    let method=form.dataset.extra,stay=false;
    if(method){
      switch(method){
        case 'quickNote':{
          args.id=modalContext.id;args.applyHistory=!!form.elements.applyHistory?.checked;if(form.elements.dismissAnomaly)args.dismissAnomaly=!!form.elements.dismissAnomaly.checked;
          const m=modalContext.merchant,oldCat=modalContext.oldCategory,newCat=args.category||oldCat,diffCount=sameMerchantCount(m,args.id,newCat);
          if(!args.applyHistory&&m&&newCat!==oldCat&&diffCount>0){
            await mutate('quickNote',args,'Saved.');busy=false;
            modalContext={merchant:m,category:newCat,subcategory:args.subcategory||'',account:modalContext.account||''};
            modal('Update previous history too?',`<p class="note">Found <strong>${diffCount}</strong> previous transaction${diffCount===1?'':'s'} for <strong>${esc(m)}</strong> with a different category. Would you like to update their category to <strong>${esc(newCat)}</strong> and remember it for future notifications?</p><div class="form-footer" style="margin-top:20px"><button class="btn ghost" data-action="close">Only this one</button><button class="btn" data-action="applyHistoryYes">Update all & remember</button></div>`);
            return;
          }
          break;
        }
        case 'category':case 'addSubcategory':
          await mutate(method,args,'Category list updated.');busy=false;categoriesModal();return;
        case 'taxProfile':
          await mutate('taxProfile',args,'LHDN tax estimate updated.');busy=false;render();return;
        case 'debt':
          if(modalContext.id)args.id=modalContext.id;
          args.dueDay=Number(args.dueDay||1);
          args.createReminder=!!form.elements.createReminder?.checked;
          break;
        case 'debtExtra':
          await mutate('debtExtra',args,'Debt payoff plan updated.');busy=false;render();return;
        case 'favorite':if(state.favorites.some(f=>f.id===modalContext.id))args.id=modalContext.id;break;
        case 'rule':if(modalContext.id)args.id=modalContext.id;args.priority=Number(args.priority);args.enabled=form.elements.enabled.checked;break;
        case 'automation':method='settings';for(const name of ['quietEnabled','digest','reminders','budgetAlerts','categoryAlerts','anomalyGuard','splitAutoMatch','weeklyBudgetNudge','widgetAmounts','captureWhileLocked','autoRecord','captureAllSources','ecoCapture','autoOpenOnBoot','alwaysOnScheduler'])if(form.elements[name])args[name]=form.elements[name].checked;for(const name of ['quietStart','quietEnd','digestHour','threshold'])args[name]=Number(args[name]);break;
        case 'settlePerson':await mutate('settlePerson',args,tr('Split bills settled.'));busy=false;close();return;
        case 'csvMap':for(const k of ['date','amount','debit','credit','merchant','category','kind','note'])args[k]=Number(args[k]);args.signed=form.elements.signed.checked;csvReview(await call('csvPreview',args));stay=true;break;
        case 'csvCommit':args.indices=[...form.querySelectorAll('input[name=row]:checked')].map(x=>Number(x.value));if(!args.indices.length)throw new Error('Choose at least one valid row.');break;
        case 'batch':{
          const ids=[...form.querySelectorAll('input[name=draft]:checked')].map(x=>x.value);
          if(!ids.length)throw new Error('Select the payments you checked.');
          args.rows=ids.map(did=>{
            const d=state.drafts.find(x=>x.id===did)||{};
            const rowEl=form.querySelector(`[data-batch-row="${CSS?.escape?CSS.escape(did):did}"]`);
            const rowAcct=rowEl?.querySelector('[data-batch-field="account"]')?.value||args.account;
            const rowKind=rowEl?.querySelector('[data-batch-field="kind"]')?.value||d.kind||'expense';
            const rowCat=rowEl?.querySelector('[data-batch-field="category"]')?.value||d.category||(rowKind==='income'?'Other income':'Miscellaneous');
            const rowTo=rowEl?.querySelector('[data-batch-field="to"]')?.value??d.to??'';
            const row={
              key:'draft:'+did,
              draftId:did,
              draftRevision:d.revision||1,
              kind:rowKind,
              amount:amount(d.amount),
              account:rowAcct,
              category:rowCat,
              merchant:d.merchant||'',
              date:d.date||today(),
              note:d.note||''
            };
            if(d.reload)row.reload=true;
            if(d.subcategory)row.subcategory=d.subcategory;
            if(rowTo)row.to=rowTo;
            if(d.transferHint)row.transferHint=d.transferHint;
            if(d.receiptId)row.receiptId=d.receiptId;
            if(d.person)row.person=d.person;
            if(d.ownAmount!==undefined&&d.ownAmount!=='')row.ownAmount=String(d.ownAmount);
            if(d.reimbursementFor)row.reimbursementFor=d.reimbursementFor;
            if(d.originalCurrency&&Number(d.originalAmount)>0){
              row.originalCurrency=d.originalCurrency;
              row.originalAmount=String(d.originalAmount);
              if(d.foreignFee!==undefined&&d.foreignFee!=='')row.foreignFee=String(d.foreignFee);
            }
            return row;
          });
          args.partial=true;
          await mutate('confirmBatch',args,'');
          busy=false;
          const res=state.lastBatchResult||{saved:args.rows.length,failed:[]};
          const failCnt=(res.failed||[]).length,savedCnt=res.saved||0;
          if(failCnt>0){
            toast(tr('Saved {s} payment(s); {f} need attention.',{s:savedCnt,f:failCnt}));
            batchForm();
          }else{
            toast(tr('Saved {s} payment(s) on your device.',{s:savedCnt}));
            close();
          }
          return;
        }
        case 'linkDraft':case 'matchBill':args.id=modalContext.id;break;
        case 'reconcile':{
          const actual=Number(args.actual);
          if(!Number.isFinite(actual)||!/^[-]?\d+([.]\d{1,2})?$/.test(args.actual))throw new Error('Enter a balance with at most two decimal places.');
          const recorded=validTx().filter(t=>t.date<=args.date).reduce((sum,t)=>sum+t.postings.filter(p=>p.account===args.account).reduce((n,p)=>n+p.amount,0),0);
          modalContext=args;
          modal('Review balance difference',`<div class="detail-line"><span>Recorded</span><strong>${signedMoney(recorded)}</strong></div><div class="detail-line"><span>Statement</span><strong>${signedMoney(Math.round(actual*100))}</strong></div><div class="detail-line"><span>Difference</span><strong>${signedMoney(Math.round(actual*100)-recorded)}</strong></div><p class="note">Check missing or duplicate transactions first. Saving a check keeps a comparison record. An adjustment changes the account balance through an explicit equity posting.</p><div class="tools-grid">${actionButton('Save comparison only','saveReconcile')}${actionButton('Record adjustment','adjustReconcile','','ghost')}</div>`);
          stay=true;break;
        }
        case 'installment':args.months=Number(args.months);args.key=modalContext.key;break;
        case 'filters':advancedFilters=Object.fromEntries(Object.entries(args).filter(([,v])=>v));render();stay=true;busy=false;close();break;
      }
      if(!stay){await mutate(method,args,'Saved on your device.');busy=false;close();if(form.dataset.extra==='paste'){page='inbox';render()}}
      busy=false;
    }else{
      switch(form.id){
        case 'transaction-form':
          method='transaction';Object.assign(args,{kind:modalContext.kind||'expense',key:modalContext.key});
          for(const k of ['draftId','editId','billId','billDate','originalId','receiptId','reimbursementFor','draftRevision'])if(modalContext[k])args[k]=modalContext[k];
          if(args.account==='')throw new Error('Choose the account used for this payment.');
          enrichTransaction(args,form);break;
        case 'account-form':method='account';break;
        case 'budget-form':{
          method='budget';args.rollover=!!form.elements.rollover?.checked;
          if(modalContext.id)args.id=modalContext.id;
          else{const old=state.budgets.find(b=>b.category===args.category&&(b.subcategory||'')===(args.subcategory||''));if(old)args.id=old.id;}
          break;
        }
        case 'bill-form':
          method='bill';args.monthly=args.frequency==='monthly';args.variable=!!form.elements.variable?.checked;args.subscription=!!form.elements.subscription?.checked;
          if(modalContext.kind)args.kind=modalContext.kind;if(modalContext.to)args.to=modalContext.to;if(modalContext.id)args.id=modalContext.id;break;
        case 'goal-form':method='goal';if(modalContext.id)args.id=modalContext.id;break;
        case 'preferences-form':method='settings';if(args.cycleMode==='monthly')args.cycleDay=1;else args.cycleDay=Number(args.cycleDay);break;
        default:throw new Error('Unknown form.');
      }
      await mutate(method,args,'Saved on your device.');busy=false;close();
    }
  }catch(err){
    busy=false;
    const box=form.querySelector('.error');
    if(box)box.textContent=err.message;else toast(err.message);
  }finally{
    if(button)button.disabled=false;
  }
});

function scheduleNativeSearch(){
  clearTimeout(searchState.timer);
  const q=query.trim();
  if(!q){searchState={query:'',ids:null,matches:{},parsedFilters:null,semanticUsed:false,seq:searchState.seq+1,timer:null};return;}
  if(!window.MoneyLensNative)return;
  const reqSeq=++searchState.seq;
  searchState.timer=setTimeout(async()=>{
    try{
      const r=await call('searchTransactions',{query:q,filter,advancedFilters});
      if(reqSeq!==searchState.seq||query.trim()!==q)return;
      searchState.query=q;
      searchState.ids=Array.isArray(r.ids)?r.ids:[];
      searchState.matches=r.matches||{};
      searchState.parsedFilters=r.parsedFilters||null;
      searchState.semanticUsed=!!r.semanticUsed;
      const box=$('#tx-results');
      if(box&&page==='transactions')box.innerHTML=renderTxResults(filteredTransactions());
    }catch(_){}
  },120);
}

document.addEventListener('input',e=>{
  if(e.target.id==='search'){
    query=e.target.value;
    if(!query.trim())searchState={query:'',ids:null,matches:{},parsedFilters:null,semanticUsed:false,seq:searchState.seq+1,timer:null};
    const res=$('#tx-results');
    if(res)res.innerHTML=renderTxResults(filteredTransactions());
    if(window.MoneyLensNative&&query.trim().length>=2)scheduleNativeSearch();
  }else if(e.target.id==='source-search'){
    const q=e.target.value;renderSources(q);const input=$('#source-search');if(input){input.focus();input.setSelectionRange(q.length,q.length);}
  }else if(e.target.name==='match'&&$('#rule-preview')){
    const needle=e.target.value.toLowerCase();
    $('#rule-preview').textContent='Matching examples in your ledger: '+[...new Set(validTx().filter(t=>needle&&t.merchant?.toLowerCase().includes(needle)).map(t=>t.merchant))].slice(0,5).join(', ');
  }
});

document.addEventListener('change',async e=>{
  const x=e.target;
  try{
    if(x.dataset?.batchField==='kind'){
      const box=x.closest('[data-batch-row]')?.querySelector('[data-batch-dest]');
      if(box){
        const k=x.value,isMove=k==='transfer'||k==='reload';
        const acctOpts=state.accounts.map(a=>[a.id,a.name]);
        const destRows=k==='reload'?[['',tr('Same account (no balance change)')],...acctOpts]:[['',tr('Choose To account')],...acctOpts];
        const catOpts=activeCategories().map(c=>[c[0],tr(c[0])]);
        box.innerHTML=`<select data-batch-field="${isMove?'to':'category'}" aria-label="${isMove?'Row destination':'Row category'}" style="min-height:34px;font-size:12px">${options(isMove?destRows:catOpts,isMove?'':(k==='income'?'Other income':'Miscellaneous'))}</select>`;
      }
    }
    if(x.id==='cycleModeSelect'){
      const cm=x.value==='monthly'?'monthly':'custom';
      const payload=cm==='monthly'?{cycleMode:'monthly',cycleDay:1}:{cycleMode:'custom'};
      if(window.MoneyLensNative)await mutate('settings',payload,cm==='monthly'?tr('Cycle switched to Calendar Monthly (1st–last day).'):tr('Custom cycle enabled.'));
      else{state.settings.cycleMode=cm;if(cm==='monthly')state.settings.cycleDay=1;render();}
    }
    if(x.name==='category'&&$('#f-subcategory'))$('#f-subcategory').innerHTML=subcatOptions(x.value,'');
    if(x.id==='langToggle'){
      const lg=x.value==='ms'?'ms':'en';
      if(window.MoneyLensNative)await mutate('settings',{lang:lg},lg==='ms'?'Bahasa ditukar ke Bahasa Melayu.':'Language set to English.');
      else{state.settings.lang=lg;render();}
    }
    if(x.id==='darkToggle')await mutate('settings',{theme:x.checked?'dark':'light'});
    if(x.id==='blurToggle')await mutate('settings',{privacyBlur:x.checked});
    if(x.id==='lockToggle')await mutate('settings',{appLock:x.checked},x.checked?tr('Fingerprint app lock enabled.'):tr('App lock disabled.'));
    if(x.id==='lockTimeoutSelect'){if(window.MoneyLensNative)await mutate('settings',{lockTimeoutSec:Number(x.value)},tr('Auto-lock timeout updated.'));else{state.settings.lockTimeoutSec=Number(x.value);render();}}
    if(x.id==='categoryAlertsToggle')await mutate('settings',{categoryAlerts:x.checked},x.checked?'Rich category notification cards enabled.':'Rich category notification cards disabled.');
    if(x.id==='anomalyGuardToggle')await mutate('settings',{anomalyGuard:x.checked},tr(x.checked?'Spend anomaly guard enabled.':'Spend anomaly guard paused.'));
    if(x.id==='splitAutoMatchToggle')await mutate('settings',{splitAutoMatch:x.checked},tr(x.checked?'Auto-reconcile for split transfers enabled.':'Auto-reconcile for split transfers paused.'));
    if(x.id==='backupNudgeNotifyToggle')await mutate('settings',{backupNudgeNotify:x.checked},tr(x.checked?'20-transaction backup reminder notification enabled.':'20-transaction backup reminder notification disabled.'));
    if(x.id==='autoRecordToggle')await mutate('settings',{autoRecord:x.checked},x.checked?'Auto-record enabled.':'Auto-record paused.');
    if(x.id==='captureAllToggle')await mutate('settings',{captureAllSources:x.checked},x.checked?'Reading all bank & wallet apps.':'Restricted to selected apps.');
    if(x.id==='ecoCaptureToggle')await mutate('settings',{ecoCapture:x.checked},x.checked?tr('Eco notification engine enabled.'):tr('Eco notification engine disabled.'));
    if(x.id==='alwaysOnToggle')await mutate('settings',{alwaysOnScheduler:x.checked},x.checked?'Always-on task scheduler active.':'Always-on task scheduler paused.');
    if(x.id==='autoOpenToggle')await mutate('settings',{autoOpenOnBoot:x.checked},x.checked?'Will auto-open on phone restart.':'Auto-open on restart disabled.');
    if(x.matches('.source-account')){await mutate('source',{package:x.dataset.package,name:x.dataset.name,enabled:true,account:x.value},'Linked account saved.');}
    if(x.matches('.source-toggle')){
      await mutate('source',{package:x.dataset.package,name:x.dataset.name,enabled:x.checked});
      toast(x.checked?'Source enabled.':'Source disabled.');
      if($('#source-search'))setTimeout(()=>renderSources($('#source-search').value),80);
    }
  }catch(err){if(x.type==='checkbox')x.checked=!x.checked;toast(err.message)}
});

document.addEventListener('keydown',e=>{
  if(e.key==='Enter'&&e.target?.id==='quick-parse-input'){e.preventDefault();$('[data-action="quickParseFill"]')?.click();return;}
  if(e.key==='Escape')close();
  if(e.key==='Tab'&&$('.modal')){
    const nodes=[...document.querySelectorAll('.modal button:not([disabled]),.modal input,.modal select,.modal textarea')];
    const first=nodes[0],last=nodes.at(-1);
    if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus()}
    else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus()}
  }
});
