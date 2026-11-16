'use strict';
const $=s=>document.querySelector(s);
const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const today=()=>{const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`};
const money=n=>`RM ${(Math.abs(n||0)/100).toLocaleString(appLocale(),{minimumFractionDigits:2,maximumFractionDigits:2})}`;
const signedMoney=n=>(n<0?'−':'')+money(n);
const amount=n=>(Math.abs(n||0)/100).toFixed(2);
const dateLabel=d=>new Date(d+'T12:00:00').toLocaleDateString(appLocale(),{day:'numeric',month:'short'});
function dayTitleFor(d){
  const t=today();
  if(d===t)return tr('Today');
  const y=new Date();y.setDate(y.getDate()-1);
  const yIso=`${y.getFullYear()}-${String(y.getMonth()+1).padStart(2,'0')}-${String(y.getDate()).padStart(2,'0')}`;
  if(d===yIso)return tr('Yesterday');
  return new Date(d+'T12:00:00').toLocaleDateString(appLocale(),{weekday:'short',day:'numeric',month:'short'});
}
function cryptoId(){return globalThis.crypto?.randomUUID?.()||'save-'+Date.now()+'-'+Math.random().toString(16).slice(2)}

const paths={home:'M3 10l9-7 9 7v10H3z M9 20v-7h6v7',list:'M8 6h12 M8 12h12 M8 18h12 M3 6h.1 M3 12h.1 M3 18h.1',inbox:'M4 4h16l2 12v4H2v-4z M2 15h6l2 3h4l2-3h6',plan:'M5 5h14v16H5z M8 3v4 M16 3v4 M8 11h8 M8 15h5',wallet:'M3 6h16v4 M3 6v14h18V10H3 M3 6l13-3v3 M16 14h5 M16 14v3h5',plus:'M12 5v14 M5 12h14',arrow:'M5 12h14 M14 7l5 5-5 5',down:'M12 4v15 M6 13l6 6 6-6',up:'M12 20V5 M6 11l6-6 6 6',chevron:'M9 5l7 7-7 7',settings:'M12 8a4 4 0 1 0 .1 0 M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z',shield:'M12 3l8 3v6c0 4-5 8-8 9-3-1-8-5-8-9V6z M8 12l3 3 5-6',lock:'M7 11V7a5 5 0 0 1 10 0v4 M5 11h14v10H5z M12 15v3',food:'M4 3v7 M7 3v7 M10 3v7 M4 7h6 M7 10v11 M18 3c-3 3-3 8 0 8h2 M20 3v18',cart:'M3 4h3l3 11h10l3-8H7 M10 20h.1 M18 20h.1',car:'M4 10l2-5h12l2 5 M3 10h18v8H3z M6 14h2 M16 14h2 M5 18v3 M19 18v3',heart:'M12 20S2 14 2 8a5 5 0 0 1 10-1 5 5 0 0 1 10 1c0 6-10 12-10 12z',bolt:'M13 2L4 14h7l-1 8 10-13h-8z',bag:'M5 7h14l2 14H3z M8 7V5a4 4 0 0 1 8 0v2',transfer:'M3 7h17 M16 3l4 4-4 4 M21 17H4 M8 13l-4 4 4 4',search:'M10 3a7 7 0 1 0 .1 0 M15 15l6 6',close:'M6 6l12 12 M18 6L6 18',check:'M4 12l5 5L20 6',more:'M5 12h.1 M12 12h.1 M19 12h.1',moon:'M20 15a9 9 0 0 1-11-11 9 9 0 1 0 11 11',target:'M12 3a9 9 0 1 0 .1 0 M12 7a5 5 0 1 0 .1 0 M12 11v2',cloud:'M6 18a5 5 0 1 1 0-10 7 7 0 0 1 13-1 5 5 0 0 1 0 11 M12 11v10 M8 17l4 4 4-4',bell:'M5 16V9a7 7 0 0 1 14 0v7l2 2H3z M9 21h6',cash:'M2 5h20v14H2z M12 8a4 4 0 1 0 .1 0 M5 9h.1 M19 15h.1',edit:'M14 5l5 5 M4 20l5-1L21 7l-5-5L4 14z',trash:'M3 6h18 M8 6V3h8v3 M6 6l1 15h10l1-15 M10 10v7 M14 10v7',eye:'M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z M12 9a3 3 0 1 0 .1 0',eyeOff:'M17.9 17.9A10 10 0 0 1 12 19c-7 0-11-7-11-7a18 18 0 0 1 5-5.5 M9.9 5.2A9 9 0 0 1 12 5c7 0 11 7 11 7a18 18 0 0 1-2.2 3.2 M3 3l18 18',scan:'M3 7V5a2 2 0 0 1 2-2h2 M17 3h2a2 2 0 0 1 2 2v2 M21 17v2a2 2 0 0 1-2 2h-2 M7 21H5a2 2 0 0 1-2-2v-2 M7 12h10',paste:'M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2 M9 2h6v4H9z',chart:'M18 20V10 M12 20V4 M6 20v-6'};
const icon=(name,cl='')=>`<svg class="${cl}" aria-hidden="true" viewBox="0 0 24 24"><path d="${paths[name]||paths.wallet}"/></svg>`;

const categories=[['Food & drinks','food','sage'],['Groceries','cart','sage'],['Transport','car','blue'],['Bills & utilities','bolt','purple'],['Loans, BNPL & Credit','wallet','peach'],['Housing & property','home','sage'],['Shopping','bag','peach'],['Health & medical','heart','peach'],['Insurance & Takaful','shield','blue'],['Education','plan','purple'],['Family & gifts','heart','sage'],['Entertainment & travel','target','purple'],['Investments & savings','target','sage'],['Taxes & government','shield','peach'],['Fees & charges','wallet','blue'],['Miscellaneous','wallet','blue'],['Health','heart','peach'],['Entertainment','target','purple'],['Other','wallet','blue']];
const cat=c=>categories.find(x=>x[0]===c)||[c||'Miscellaneous','wallet','sage'];
const colors=['#146c43','#3f51b5','#006d77','#b84a27','#6a3d9a','#c97a16','#2b6cb0','#d53f8c'];
const defaultSubcategories={
  'Food & drinks':['Hawker & Mamak','Restaurant & Cafe','Fast Food','Food Delivery (GrabFood / Foodpanda)','Coffee, Tea & Drinks','Groceries & Snacks'],
  'Groceries':['Supermarket & Hypermarket','Convenience Store (99 Speedmart / 7-Eleven / FamilyMart)','Wet Market & Fresh Produce','Household & Cleaning Supplies'],
  'Transport':['Toll (TNG / RFID / PayDirect)','Petrol & EV Charging','Parking','Public Transit (MRT / LRT / Bus)','E-Hailing (Grab / AirAsia Ride)','Vehicle Maintenance & Service','Road Tax & Motor Insurance','Car Loan Installment'],
  'Bills & utilities':['Electricity (TNB)','Water & Sewerage (Air Selangor / IWK)','Home Internet & WiFi (Unifi / Time)','Mobile Postpaid & Prepaid','Streaming & App Subscriptions','Condo Maintenance & Sinking Fund'],
  'Loans, BNPL & Credit':['PTPTN Study Loan','BNPL (SPayLater / GrabPayLater / Atome)','Credit Card Bill','Personal Loan','Hire Purchase / Installment'],
  'Housing & property':['Rent','Home Loan / Mortgage','Home Repair & Maintenance','Furniture & Appliances','Cukai Pintu & Cukai Tanah'],
  'Shopping':['Online Marketplace (Shopee / Lazada / TikTok)','Clothing, Bags & Footwear','Electronics & Gadgets','Beauty, Skincare & Grooming','Home & Living'],
  'Health & medical':['Clinic & GP','Pharmacy & Medicine','Hospital & Specialist','Dental & Optical','Full Medical Checkup (LHDN)','Gym, Sports & Fitness'],
  'Health':['Clinic & GP','Pharmacy & Medicine','Hospital & Specialist','Dental & Optical','Full Medical Checkup (LHDN)','Gym, Sports & Fitness'],
  'Insurance & Takaful':['Life Insurance / Takaful','Medical & Health Card','Education Insurance','Personal Accident'],
  'Education':['Higher Education & Tuition','Upskilling & Certification','School & Childcare Fees','Books & Stationery'],
  'Family & gifts':['Parents Allowance','Childcare & Family Support','Festive (Angpao / Duit Raya) & Gifts','Zakat & Fitrah','Charity & Donations'],
  'Entertainment & travel':['Movies, Concerts & Events','Games & Hobbies','Flights & Transport','Hotel & Holiday Stay'],
  'Entertainment':['Movies, Concerts & Events','Games & Hobbies','Flights & Transport','Hotel & Holiday Stay'],
  'Investments & savings':['EPF Voluntary (i-Saraan)','ASB / Tabung Haji','PRS (Private Retirement Scheme)','SSPN Savings','Stocks, ETF & Crypto'],
  'Taxes & government':['LHDN Income Tax / PCB','JPJ / Renewals / Summons','Government Service & Stamp Duty'],
  'Fees & charges':['Bank & Transfer Fee','Foreign Currency Fee','Late Payment Fee','Card Annual Fee'],
  'Miscellaneous':['Auto-Captured (Uncategorized)','General Expense','Other'],
  'Other':['General Expense','Other']
};

let state=null,status={},page='home',planTab='budgets',filter='all',query='',monthOffset=0,modalContext={},pending=new Map(),seq=0,busy=false,apps=[];
let reportData=window.MONEYLENS_REPORT||null,importPreview=[],importInfo=null,advancedFilters={},debtStrategy='avalanche';

function activeCategories(){
  const hidden=new Set(((state?.settings?.hiddenCategories)||[]).map(x=>String(x).toLowerCase()));
  return categories.filter(c=>!hidden.has(c[0].toLowerCase()));
}
function subcategoriesFor(catName){
  const base=defaultSubcategories[catName]||['General'];
  const custom=(state?.settings?.customSubcategories?.[catName])||[];
  const hidden=new Set(((state?.settings?.hiddenSubcategories?.[catName])||[]).map(x=>String(x).toLowerCase()));
  const out=[];
  for(const s of [...base,...custom]){
    if(!hidden.has(String(s).toLowerCase())&&!out.includes(s))out.push(s);
  }
  return out;
}
function subcatOptions(catName,selected=''){
  const list=subcategoriesFor(catName);
  const rows=[['','None / General'],...list.map(s=>[s,s])];
  if(selected&&!rows.some(r=>r[0]===selected))rows.push([selected,selected]);
  return options(rows,selected);
}

const field=(label,name,value='',type='text',extra='')=>`<div><label for="f-${name}">${label}</label><input id="f-${name}" name="${name}" type="${type}" value="${esc(value)}" ${extra}></div>`;
const options=(rows,selected)=>rows.map(([id,label])=>`<option value="${esc(id)}" ${id===selected?'selected':''}>${esc(label)}</option>`).join('');
const select=(label,name,rows,selected)=>`<div><label for="f-${name}">${label}</label><select id="f-${name}" name="${name}">${options(rows,selected)}</select></div>`;
const check=(label,name,checked=false)=>`<label class="check-row"><input type="checkbox" name="${name}" ${checked?'checked':''}>${label}</label>`;
const actionButton=(label,action,id='',cls='secondary')=>`<button type="button" class="btn ${cls} small" data-action="${action}" data-id="${esc(id)}">${label}</button>`;
const dockTile=(label,action,ico,tone='sage')=>`<button type="button" class="dock-tile" data-action="${action}"><div class="icon-bg ${tone}">${icon(ico)}</div><span>${esc(label)}</span></button>`;
const acctSelect=(selected)=>select('Account','account',state.accounts.map(a=>[a.id,a.name]),selected||state.settings.lastAccount||state.accounts[0].id);
const categorySelect=(selected)=>select('Category','category',activeCategories().map(c=>[c[0],tr(c[0])]),selected||state.settings.lastCategory||'Miscellaneous');
const subcategorySelect=(catName,selected='')=>`<div><label for="f-subcategory">Subcategory</label><select id="f-subcategory" name="subcategory">${subcatOptions(catName,selected)}</select></div>`;
const formFooter=(text='Save')=>`<div class="error" role="alert"></div><button class="btn" type="submit">${text}</button>`;

function account(id){return state.accounts.find(a=>a.id===id)||{name:tr('Unknown account'),type:'bank'}}
function validTx(){return state.transactions.filter(t=>!t.voided)}
function balance(id){return validTx().reduce((sum,t)=>sum+(t.postings||[]).filter(p=>p.account===id).reduce((n,p)=>n+p.amount,0),0)}
function period(off=monthOffset){
  let now=new Date(),day=state.settings.cycleDay||1;
  const date=(y,m)=>new Date(y,m,Math.min(day,new Date(y,m+1,0).getDate()),0);
  let start=date(now.getFullYear(),now.getMonth());
  if(now<start)start=date(start.getFullYear(),start.getMonth()-1);
  start=date(start.getFullYear(),start.getMonth()+off);
  let end=date(start.getFullYear(),start.getMonth()+1);
  const iso=d=>`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
  return{start:iso(start),end:iso(end),label:start.toLocaleDateString(appLocale(),{month:'long',year:'numeric'}),day};
}
function currentTx(){let p=period();return validTx().filter(t=>t.date>=p.start&&t.date<p.end&&t.date<=today())}
function expense(t){return (t.postings||[]).filter(p=>p.account.startsWith('expense:')).reduce((s,p)=>s+p.amount,0)}
function totals(){let tx=currentTx();return{spent:tx.reduce((s,t)=>s+expense(t),0),income:tx.filter(t=>t.kind==='income').reduce((s,t)=>s+t.amount,0),net:state.accounts.reduce((s,a)=>s+balance(a.id),0)}}
function spendFor(c,sub=''){
  const needle=sub?sub.trim().toLowerCase():'';
  return currentTx().reduce((s,t)=>needle&&((t.subcategory||'').trim().toLowerCase()!==needle)?s:s+(t.postings||[]).filter(p=>p.account==='expense:'+c).reduce((n,p)=>n+p.amount,0),0);
}
function brandBadge(src){
  const s=String(src||'Auto-captured'),low=s.toLowerCase();
  let cls='brand-default';
  if(low.includes('tng')||low.includes('touch'))cls='brand-tng';
  else if(low.includes('maybank')||low.includes('mae'))cls='brand-maybank';
  else if(low.includes('spaylater')||low.includes('shopee'))cls='brand-shopee';
  else if(low.includes('atome'))cls='brand-atome';
  else if(low.includes('ryt'))cls='brand-ryt';
  else if(low.includes('lazada')||low.includes('lazpaylater'))cls='brand-lazada';
  else if(low.includes('tiktok'))cls='brand-tiktok';
  else if(low.includes('foodpanda'))cls='brand-foodpanda';
  else if(low.includes('bigpay')||low.includes('fave')||low.includes('shopback'))cls='brand-wallet';
  else if(low.includes('cimb')||low.includes('octo'))cls='brand-cimb';
  else if(low.includes('gxbank')||low.includes('gx'))cls='brand-gxbank';
  else if(low.includes('aeon'))cls='brand-aeon';
  else if(low.includes('boost')||low.includes('payflex'))cls='brand-boost';
  else if(low.includes('setel'))cls='brand-setel';
  else if(low.includes('grab'))cls='brand-grab';
  else if(/public bank|mypb|\brhb\b|hong leong|\bhlb\b|ambank|amonline|bank islam|bimb|\buob\b|ocbc|hsbc|standard chartered|sc mobile|bsn/.test(low))cls='brand-bank';
  return `<span class="brand-badge ${cls}">${icon('bell')}${esc(s)}</span>`;
}
function sameMerchantCount(merchant,excludeId,diffFromCat){
  if(!merchant)return 0;
  const m=merchant.trim().toLowerCase();
  return validTx().filter(t=>t.id!==excludeId&&['expense','refund'].includes(t.kind)&&!t.splits&&(t.merchant||'').trim().toLowerCase()===m&&(!diffFromCat||t.category!==diffFromCat)).length;
}
const CAT_META={
  'Food & drinks':{emoji:'🍲',tone:'coral'},
  'Groceries':{emoji:'🛒',tone:'sage'},
  'Transport':{emoji:'🚗',tone:'sky'},
  'Bills & utilities':{emoji:'⚡',tone:'amber'},
  'Loans, BNPL & Credit':{emoji:'💳',tone:'violet'},
  'Housing & property':{emoji:'🏠',tone:'teal'},
  'Shopping':{emoji:'🛍️',tone:'rose'},
  'Health & medical':{emoji:'🏥',tone:'mint'},
  'Insurance & Takaful':{emoji:'🛡️',tone:'indigo'},
  'Education':{emoji:'📚',tone:'blue'},
  'Family & gifts':{emoji:'🎁',tone:'pink'},
  'Entertainment & travel':{emoji:'🎬',tone:'purple'},
  'Investments & savings':{emoji:'📈',tone:'emerald'},
  'Taxes & government':{emoji:'🏛️',tone:'slate'},
  'Fees & charges':{emoji:'🧾',tone:'stone'},
  'Income & salary':{emoji:'💼',tone:'emerald'},
  'Transfers & top-up':{emoji:'🔄',tone:'sky'},
  'Miscellaneous':{emoji:'💸',tone:'neutral'}
};
function catMeta(c){
  if(CAT_META[c])return CAT_META[c];
  const low=String(c||'').toLowerCase();
  if(low.includes('food')||low.includes('makan'))return CAT_META['Food & drinks'];
  if(low.includes('grocer')||low.includes('runcit'))return CAT_META['Groceries'];
  if(low.includes('transport')||low.includes('angkut'))return CAT_META['Transport'];
  if(low.includes('bill')||low.includes('util'))return CAT_META['Bills & utilities'];
  if(low.includes('bnpl')||low.includes('loan'))return CAT_META['Loans, BNPL & Credit'];
  if(low.includes('hous')||low.includes('rent'))return CAT_META['Housing & property'];
  if(low.includes('shop'))return CAT_META['Shopping'];
  if(low.includes('health')||low.includes('clinic'))return CAT_META['Health & medical'];
  if(low.includes('insur')||low.includes('takaful'))return CAT_META['Insurance & Takaful'];
  if(low.includes('educ'))return CAT_META['Education'];
  if(low.includes('famil')||low.includes('gift')||low.includes('zakat'))return CAT_META['Family & gifts'];
  if(low.includes('entertain')||low.includes('travel'))return CAT_META['Entertainment & travel'];
  if(low.includes('invest')||low.includes('saving'))return CAT_META['Investments & savings'];
  if(low.includes('tax')||low.includes('lhdn')||low.includes('jpj'))return CAT_META['Taxes & government'];
  return CAT_META['Miscellaneous'];
}
function computeLocalDailyPacing(s,rep){
  const curState=s||state;if(!curState)return null;
  const p=period(0),onIso=today();
  const prevM=period(-1);
  const startDt=new Date(p.start+'T12:00:00'),endDt=new Date(p.end+'T12:00:00'),onDt=new Date(onIso+'T12:00:00');
  endDt.setDate(endDt.getDate()-1);
  const dayMs=86400000;
  const daysInCycle=Math.max(1,Math.round((endDt-startDt)/dayMs)+1);
  const elapsedDays=Math.max(1,Math.min(daysInCycle,Math.round((onDt-startDt)/dayMs)+1));
  const remainingDays=Math.max(1,Math.round((endDt-onDt)/dayMs)+1);
  const txList=(curState.transactions||[]).filter(t=>!t.voided&&t.date<=onIso);
  let cycleSpent=0,todaySpent=0;
  const dayMap=new Map(),currCats=new Map(),prevCats=new Map();
  const seriesStartDt=new Date(onDt);seriesStartDt.setDate(seriesStartDt.getDate()-13);
  const seriesIso=`${seriesStartDt.getFullYear()}-${String(seriesStartDt.getMonth()+1).padStart(2,'0')}-${String(seriesStartDt.getDate()).padStart(2,'0')}`;
  for(const t of txList){
    const inCurr=t.date>=p.start&&t.date<p.end;
    const inPrev=t.date>=prevM.start&&t.date<prevM.end;
    const inSer=t.date>=seriesIso;
    if(!inCurr&&!inPrev&&!inSer)continue;
    let txExp=0;
    for(const e of (t.postings||[])){
      if(e.account?.startsWith('expense:')){
        const cat=e.account.slice(8),amt=e.amount||0;
        if(inCurr){currCats.set(cat,(currCats.get(cat)||0)+amt);cycleSpent+=amt;}
        else if(inPrev){prevCats.set(cat,(prevCats.get(cat)||0)+amt);}
        if(t.date===onIso)todaySpent+=amt;
        txExp+=amt;
      }
    }
    if(inSer&&txExp!==0)dayMap.set(t.date,Math.max(0,(dayMap.get(t.date)||0)+txExp));
  }
  const accts=curState.accounts||[];
  let liq=0,debt=0;
  for(const a of accts){
    const b=txList.reduce((sum,t)=>sum+(t.postings||[]).filter(e=>e.account===a.id).reduce((k,e)=>k+e.amount,0),0);
    if(a.type==='card')debt+=Math.max(0,-b);else liq+=b;
  }
  const avail=(rep&&rep.availableToSpend!=null)?rep.availableToSpend:Math.max(0,liq-debt);
  const safeDaily=avail>0?Math.floor(avail/remainingDays):0;
  const dailyAvg=Math.round(cycleSpent/elapsedDays);
  const todayRemaining=Math.max(0,safeDaily-todaySpent);
  const paceStatus=(safeDaily>0&&todaySpent>safeDaily)||(avail<=0&&todaySpent>0)?'over':(safeDaily>0&&todaySpent*100>=safeDaily*80)?'watch':'good';
  const dailySeries=[];
  for(let i=13;i>=0;i--){
    const d=new Date(onDt);d.setDate(d.getDate()-i);
    const iso=`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
    dailySeries.push({date:iso,spent:dayMap.get(iso)||0,isToday:i===0});
  }
  const categoryDeltas=[...currCats.entries()].filter(x=>x[1]>0).sort((a,b)=>b[1]-a[1]).slice(0,6).map(([cat,curr])=>{
    const prev=Math.max(0,prevCats.get(cat)||0),delta=curr-prev;
    const deltaPct=prev>0?Math.round(delta*100/prev):100;
    return{category:cat,current:curr,previous:prev,delta,deltaPct};
  });
  return{daysInCycle,elapsedDays,remainingDays,cycleSpent,todaySpent,safeDaily,todayRemaining,dailyAvg,projectedCycleSpend:dailyAvg*daysInCycle,paceStatus,dailySeries,categoryDeltas};
}
function localQuickParse(rawText){
  const raw=String(rawText||'').trim();if(!raw)return null;
  const work=raw.replace(/\b20\d{2}-\d{2}-\d{2}\b/g,' ').replace(/\s+/g,' ').trim();
  const rmM=work.match(/\b(?:rm|myr)\s*([0-9]{1,7}(?:\.[0-9]{1,2})?)/i);
  const numM=rmM||work.match(/(?:^|[^0-9.:/-])([0-9]{1,6}(?:\.[0-9]{1,2})?)(?![0-9.:/-])/);
  if(!numM)return null;
  const val=Number(numM[1]);if(!(val>0))return null;
  const amtMinor=Math.round(val*100);
  const tokenStart=work.indexOf(numM[0]);
  let rest=(work.slice(0,tokenStart)+' '+work.slice(tokenStart+numM[0].length)).replace(/\s+/g,' ').trim();
  let kind='expense',person='',ownAmount='';
  const spM=rest.match(/\b(?:split(?:\s+with)?|kongsi(?:\s+dengan)?)\s+([A-Za-z0-9][A-Za-z0-9 .'_-]{0,40})/i);
  if(spM){
    kind='shared';
    person=spM[1].replace(/\s+(?:via|by|using|with|cash|tunai|tng|mae|maybank|cimb|rhb|gxbank|shopeepay|grabpay|boost|bank|card).*$/i,'').trim();
    rest=(rest.slice(0,spM.index)+' '+rest.slice(spM.index+spM[0].length)).replace(/\s+/g,' ').trim();
    ownAmount=(Math.floor(amtMinor/2)/100).toFixed(2);
  }else if(/\b(?:received|terima|daripada|gaji|salary)\b/i.test(rest)||/\bfrom\s+[A-Za-z]/i.test(rest)){
    if(!/\b(?:transfer|pindah|top.?up|reload)\b/i.test(rest))kind='income';
  }else if(/\b(?:transfer|pindah|top.?up|reload)\b/i.test(rest))kind='transfer';
  const accts=state?.accounts||[];
  let acct=accts.find(a=>new RegExp(`\\b${a.name.replace(/[.*+?^${}()|[\]\\]/g,'\\$&')}\\b`,'i').test(rest))?.id||'';
  if(!acct){
    const aliases=[[/\b(?:cash|tunai)\b/i,'cash'],[/\b(?:tng|touch\s*'?n\s*go)\b/i,'tng'],[/\bmae\b/i,'mae'],[/\bmaybank\b/i,'maybank'],[/\bcimb\b/i,'cimb'],[/\bgxbank\b|\bgx\b/i,'gx'],[/\bshopeepay\b|\bspay\b/i,'shopee'],[/\bgrabpay\b/i,'grab']];
    for(const[re,needle] of aliases){
      if(re.test(rest)){
        const hit=accts.find(a=>a.type===needle||a.name.toLowerCase().includes(needle));
        if(hit){acct=hit.id;rest=rest.replace(re,' ');break;}
      }
    }
  }
  if(!acct)acct=state?.settings?.lastAccount||accts[0]?.id||'cash';
  let merchant=rest.replace(/\b(?:paid|pay|spent|bought|bayar|beli|received|terima|duitnow|fpx|qr|via|melalui|from|daripada|at|di|for|untuk|to|kepada)\b/ig,' ').replace(/\s+/g,' ').trim();
  if(kind==='income'){const fm=rest.match(/\b(?:from|daripada)\s+([A-Za-z0-9 .'_-]{2,40})/i);if(fm)merchant=fm[1].replace(/\b(?:via|duitnow|mae|tng|cash|tunai|bank).*/i,'').trim();}
  if(!merchant)merchant=kind==='shared'?'Shared expense':kind==='income'?'Incoming transfer':'Quick expense';
  else merchant=merchant[0].toUpperCase()+merchant.slice(1);
  const low=(merchant+' '+raw).toLowerCase();
  let category=kind==='income'?'Other income':'Miscellaneous',subcategory='';
  if(kind!=='income'){
    if(/\b(nasi|kopitiam|mamak|laksa|mee|food|lunch|dinner|breakfast|makan|teh|coffee|cafe|starbucks|zus|mixue|mcd|kfc)\b/.test(low)){category='Food & drinks';subcategory=/grabfood|foodpanda/.test(low)?'Food Delivery (GrabFood / Foodpanda)':'Hawker & Mamak';}
    else if(/\b(grab|mrt|lrt|toll|petrol|shell|petronas|setel|parking)\b/.test(low)){category='Transport';subcategory=/grab/.test(low)?'E-Hailing (Grab / AirAsia Ride)':'';}
    else if(/\b(speedmart|lotus|mydin|jaya|village grocer|aeon|groceries|market)\b/.test(low)){category='Groceries';}
    else if(/\b(clinic|klinik|pharmacy|farmasi|watsons|guardian|doctor)\b/.test(low)){category='Health & medical';}
    else if(/\b(shopee|lazada|uniqlo|shopping|watsons)\b/.test(low)){category='Shopping';}
    else if(/\b(tnb|unifi|maxis|celcom|water|wifi|electric)\b/.test(low)){category='Bills & utilities';}
  }
  return{kind,amount:(amtMinor/100).toFixed(2),amountMinor:amtMinor,merchant,category,subcategory,account:acct,ownAmount,person,date:today()};
}
const CAT_NOTIFICATION_PRESETS=[
  {key:'hawker',category:'Food & drinks',sub:'Hawker & Mamak',source:'ShopeePay MY',sample:'You have paid RM 8.50 at Nasi Lemak Wanjo Kampung Baru.'},
  {key:'food',category:'Food & drinks',sub:'Restaurant & Cafe',source:'MAE by Maybank2u',sample:'RM 15.50 at RESTORAN PELITA via MAE QRPay.'},
  {key:'groceries',category:'Groceries',sub:'Convenience / Supermarket',source:'GXBank',sample:'You paid RM 34.80 at 99 Speedmart via DuitNow QR.'},
  {key:'petrol',category:'Transport',sub:'Petrol & EV Charging',source:'Setel',sample:'RM 50.00 paid at PETRONAS Sri Hartamas via Setel.'},
  {key:'toll',category:'Transport',sub:'Toll (TNG / RFID / PayDirect)',source:"Touch 'n Go eWallet",sample:'You have paid RM2.50 at DUKE TOLL PLAZA.'},
  {key:'bnpl',category:'Loans, BNPL & Credit',sub:'BNPL (SPayLater / Atome)',source:'Shopee SPayLater',sample:'Payment of RM 49.90 for SPayLater installment completed.'},
  {key:'ptptn',category:'Loans, BNPL & Credit',sub:'PTPTN Study Loan',source:'Maybank2u',sample:'You have paid RM 150.00 to PTPTN Direct Debit.'},
  {key:'bills',category:'Bills & utilities',sub:'Electricity (TNB)',source:'MAE by Maybank2u',sample:'RM 142.30 paid to Tenaga Nasional (myTNB).'},
  {key:'health',category:'Health & medical',sub:'Clinic & GP',source:"Touch 'n Go eWallet",sample:'You paid RM 45.00 at Klinik Mediviron Bangsar.'},
  {key:'insurance',category:'Insurance & Takaful',sub:'Medical & Health Card',source:'CIMB OCTO',sample:'RM 220.00 debited for Prudential PruBSN Medical Card.'},
  {key:'rent',category:'Housing & property',sub:'Rent',source:'MAE by Maybank2u',sample:'You transferred RM 1,250.00 for Monthly Rent October.'},
  {key:'family',category:'Family & gifts',sub:'Parents Allowance',source:'MAE by Maybank2u',sample:'RM 300.00 transferred for Parents Allowance.'},
  {key:'shopping',category:'Shopping',sub:'Clothing, Bags & Footwear',source:'Shopee MY',sample:'RM 68.00 paid at UNIQLO Malaysia via ShopeePay.'},
  {key:'education',category:'Education',sub:'Books & Stationery',source:"Touch 'n Go eWallet",sample:'You have paid RM 39.90 at POPULAR Bookstore.'},
  {key:'entertainment',category:'Entertainment & travel',sub:'Movies, Concerts & Events',source:"Touch 'n Go eWallet",sample:'You paid RM 42.00 at GSC Mid Valley.'},
  {key:'refund',category:'Shopping',sub:'Refund & Reversal',source:'ShopeePay MY',sample:'Refund of RM 68.00 from UNIQLO Malaysia credited to your ShopeePay balance.'},
  {key:'split',category:'Family & gifts',sub:'Split-Bill Reimbursement',source:'MAE by Maybank2u',sample:'You received RM 24.50 from Ali via DuitNow transfer.'},
  {key:'twin',category:'Shopping',sub:'Cross-App Duplicate (with Shopping)',source:'MAE by Maybank2u',sample:'Card transaction RM 68.00 at SHOPEEPAY approved.'}
];
