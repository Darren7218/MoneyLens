'use strict';
// Loads the real WebView scripts into a stubbed DOM context and checks pure rendering/calculation helpers.
const fs=require('fs'),path=require('path'),vm=require('vm'),assert=require('assert');
const stubEl=()=>({innerHTML:'',textContent:'',classList:{add(){},remove(){},toggle(){}},insertAdjacentHTML(){},querySelector:()=>null,focus(){}});
const ctx={console,setTimeout,clearTimeout,Intl,Date,Math,JSON,Promise,Map,Set,globalThis:null};
ctx.window=ctx;ctx.globalThis=ctx;
ctx.document={querySelector:()=>stubEl(),querySelectorAll:()=>[],addEventListener(){},body:{classList:{toggle(){},add(){},remove(){}},dataset:{}}};
vm.createContext(ctx);
for(const f of ['i18n.js','format.js','views/home.js','views/plan.js','views/transactions.js','views/settings.js','actions.js','app.js'])vm.runInContext(fs.readFileSync(path.join(__dirname,'..','app','src','main','assets',f),'utf8'),ctx,{filename:f});
const run=code=>vm.runInContext(code,ctx);
let passed=0;const test=(name,fn)=>{fn();passed++;console.log('PASS '+name);};

test('brandBadge picks wallet/bank brand styles',()=>{
  assert(run(`brandBadge('TNG eWallet')`).includes('brand-tng'));
  assert(run(`brandBadge('Maybank MAE')`).includes('brand-maybank'));
  assert(run(`brandBadge('Shopee SPayLater')`).includes('brand-shopee'));
  assert(run(`brandBadge('<script>')`).includes('&lt;script&gt;'));
});
test('dayTitleFor labels today and yesterday',()=>{
  assert.strictEqual(run(`dayTitleFor(today())`),'Today');
  assert.strictEqual(run(`(()=>{const y=new Date();y.setDate(y.getDate()-1);return dayTitleFor(y.getFullYear()+'-'+String(y.getMonth()+1).padStart(2,'0')+'-'+String(y.getDate()).padStart(2,'0'))})()`),'Yesterday');
});
test('computeLocalTax monthly paycheck deducts EPF, SOCSO and PCB',()=>{
  const r=run(`computeLocalTax({monthlyBaseSalary:5000,epfRate:11,monthlyPcb:100})`);
  assert.strictEqual(r.monthlyPaycheck.grossMonthly,500000);
  assert.strictEqual(r.monthlyPaycheck.epfMonthly,55000);
  assert.strictEqual(r.monthlyPaycheck.pcbMonthly,10000);
  assert.strictEqual(r.monthlyPaycheck.netTakeHomeMonthly,500000-55000-Math.round(35000/12)-10000);
});
run(`state={settings:{cycleDay:1},accounts:[{id:'cash',name:'Cash',type:'cash'}],bills:[{id:'b1',name:'SPayLater',amount:5000,date:today()}],budgets:[],goals:[],drafts:[],sources:[],favorites:[],rules:[],customCategories:[],receipts:[],imports:[],reconciliations:[],installments:[],
 transactions:[
  {id:'t1',kind:'expense',amount:1250,account:'cash',category:'Food & drinks',subcategory:'Hawker & Mamak',merchant:'Nasi Lemak',date:today(),created:2,postings:[{account:'cash',amount:-1250},{account:'expense:Food & drinks',amount:1250}]},
  {id:'t2',kind:'expense',amount:5000,account:'cash',category:'Loans, BNPL & Credit',merchant:'SPayLater',billId:'b1',alsoSeenFrom:'Maybank',date:today(),created:1,postings:[{account:'cash',amount:-5000},{account:'expense:Loans, BNPL & Credit',amount:5000}]}
 ]};page='transactions';advancedFilters={};`);
test('transactionList groups by day with a net subtotal and link pills',()=>{
  const html=run(`transactionList(validTx(),true)`);
  assert(html.includes('date-group-head'));
  assert(html.includes('Today'));
  assert(html.includes('RM 62.50'),'daily net subtotal');
  assert(html.includes('Linked · SPayLater'));
  assert(html.includes('Also from Maybank'));
});
test('spending calendar shades days with spend and marks the selected day',()=>{
  run(`advancedFilters={exactDate:today()}`);
  const html=run(`spendingCalendarCard()`);
  assert(html.includes('cal-grid'));
  assert(/cal-cell lvl-4 selected/.test(html));
  run(`advancedFilters={}`);
});
test('transaction filters by exact date and needs-note',()=>{
  run(`advancedFilters={exactDate:'1999-01-01'}`);
  assert(run(`transactionList(validTx(),true)`).includes('Your story starts here'));
  run(`advancedFilters={}`);
});
test('debt planner tab renders Avalanche vs Snowball comparison',()=>{
  const prevReport=run(`reportData`);
  run(`reportData={debtPlan:{activeCount:2,totalBalance:260000,totalMinPayment:20000,extraMonthly:30000,interestSavedByAvalanche:14500,avalanche:{months:6,payoffDate:'2026-07-01',totalInterest:11000,schedule:[{month:1,date:'2026-02-01',paid:50000,remaining:213000,paidOff:[]}]},snowball:{months:7,payoffDate:'2026-08-01',totalInterest:25500,schedule:[]},debts:[{id:'d1',name:'Credit Card',type:'credit',balance:200000,minPayment:10000,aprBps:1800,rateType:'reducing',dueDay:15,avalanche:{payoffMonth:5,payoffDate:'2026-06-01',interest:11000}}]},budgets:[],receivables:[],months:[]};planTab='debts'`);
  const html=run(`plan()`);
  assert(html.includes('DEBT PAYOFF PLANNER')&&html.includes('Avalanche saves')&&html.includes('Credit Card'));
  run(`reportData=${JSON.stringify(prevReport)};planTab='budgets'`);
});
test('home forecast card displays payday detection and low cash warning',()=>{
  const prevReport=run(`reportData`);
  run(`reportData={availableToSpend:100000,dailyAllowance:3000,spent:5000,previousComparable:4000,budgets:[],receivables:[],months:[],recurringIncome:[{name:'ACME SDN BHD',amount:500000,frequency:'monthly',dayOfMonth:25,isCurrentPayday:false}],forecast:{startingBalance:40000,endingBalance:-20000,totalInflows:500000,totalOutflows:560000,lowestBalance:-120000,lowestDate:'2026-04-10',hasNegativeWarning:true,firstNegativeDate:'2026-04-10',hasConfiguredPayday:false,events:[],daily:[{date:'2026-04-01',balance:40000},{date:'2026-04-10',balance:-120000},{date:'2026-04-25',balance:-20000}]}}`);
  const html=run(`home()`);
  assert(html.includes('30-DAY CASH-FLOW FORECAST')&&html.includes('Make this my payday')&&html.includes('Cash shortfall alert'));
  run(`reportData=${JSON.stringify(prevReport)}`);
});
test('budgets view shows subcategory chip and elapsed pace indicator',()=>{
  const prevReport=run(`reportData`);
  run(`reportData={budgets:[{id:'b1',category:'Food & drinks',subcategory:'Hawker & Mamak',limit:10000,available:10000,spent:6000,carry:0,remaining:4000,pacePct:16,spentPct:60,paceStatus:'fast'}]}`);
  const html=run(`budgets()`);
  assert(html.includes('Hawker &amp; Mamak')&&html.includes('Ahead of pace')&&html.includes('pace-tick'));
  run(`reportData=${JSON.stringify(prevReport)}`);
});
test('cycle recap modal renders income, savings rate and PDF export trigger',()=>{
  const html=run(`recapMarkup({start:'2026-06-01',end:'2026-06-30',totalIncome:500000,totalSpent:150000,previousSpent:140000,spentDelta:10000,netSaved:350000,savingsRatePct:70,underBudgetCount:2,overBudgetCount:0,claimableCount:3,topCategories:[{category:'Food & drinks',amount:120000,sharePct:80}],topMerchants:[{merchant:'Village Grocer',amount:120000,count:4}]})`);
  assert(html.includes('2026-06-01')&&html.includes('70%')&&html.includes('Export PDF Report')&&html.includes('Village Grocer'));
});
test('goals view renders quick-contribute buttons and target date pace',()=>{
  const future=new Date(Date.now()+90*86400000).toISOString().slice(0,10);
  run(`state.goals=[{id:'g1',name:'Emergency Fund',target:300000,saved:50000,targetDate:'${future}'}]`);
  const html=run(`goals()`);
  assert(html.includes('+ RM 50')&&html.includes('+ RM 100')&&html.includes('− RM 50')&&html.includes('data-action="goalDelta"')&&html.includes('/mo needed'));
  run(`state.goals=[]`);
});
test('Bahasa Melayu toggle localizes nav, home, plan, budgets, settings, goals, accounts, inbox and day titles',()=>{
  run(`state.settings.lang='ms'`);
  assert.strictEqual(run(`dayTitleFor(today())`),'Hari ini');
  const navHtml=run(`nav()`);
  assert(navHtml.includes('Utama')&&navHtml.includes('Transaksi')&&navHtml.includes('Rancang')&&navHtml.includes('Akaun'));
  const homeHtml=run(`home()`);
  assert(homeHtml.includes('BAKI BERSIH DIREKOD')&&homeHtml.includes('Pendapatan kitaran ini'));
  const planHtml=run(`plan()`);
  assert(planHtml.includes('Bajet')&&planHtml.includes('Hutang')&&planHtml.includes('Cukai LHDN'));
  const setHtml=run(`settings()`);
  assert(setHtml.includes('id="langToggle"')&&setHtml.includes('value="ms" selected')&&setHtml.includes('Rekod automatik notifikasi')&&setHtml.includes('id="ecoCaptureToggle"')&&setHtml.includes('Enjin notifikasi jimat kuasa (Mod Eko)')&&setHtml.includes('Kunci aplikasi cap jari &amp; biometrik')&&setHtml.includes('0.9.0'));
  run(`state.settings.appLock=true`);
  const setLockedHtml=run(`settings()`);
  assert(setLockedHtml.includes('id="lockTimeoutSelect"')&&setLockedHtml.includes('data-action="lockNow"')&&setLockedHtml.includes('Kunci sekarang'));
  run(`state.settings.appLock=false`);
  run(`reportData={priceHikes:[{merchant:'Netflix Malaysia',previousAvg:4500,latestAmount:5500,deltaAmount:1000,deltaPct:22,billId:'b1'}],subscriptions:[]}`);
  const billsHtml=run(`bills()`);
  assert(billsHtml.includes('Pengesan Kenaikan Harga Langganan &amp; Bil')&&billsHtml.includes('Netflix Malaysia')&&billsHtml.includes('+22%'));
  run(`state.settings.taxProfile={monthlyBaseSalary:8000,epfRate:11,lifestyle:500};reportData=null`);
  const taxHtml=run(`taxView()`);
  assert(taxHtml.includes('Pengoptimum Pelepasan Cukai Akhir Tahun LHDN')&&taxHtml.includes('Jimat sehingga'));
  const acctHtml=run(`accounts()`);
  assert(acctHtml.includes('Baki direkodkan')&&acctHtml.includes('Sejarah pelarasan baki'));
  const inboxHtml=run(`inbox()`);
  assert(inboxHtml.includes('Pembayaran Direkod Auto Terkini'));
  run(`state.settings.lang='en'`);
  assert.strictEqual(run(`dayTitleFor(today())`),'Today');
});
test('home smart insights strip, hero delta pill and digital bank badges render accurately',()=>{
  const html=run(`home()`);
  assert(html.includes('insights-grid')&&html.includes('insight-chip')&&html.includes('Cycle cashflow'));
  assert(html.includes('hero-delta-pill'));
  assert(run(`brandBadge('GXBank')`).includes('brand-gxbank'));
  assert(run(`brandBadge('AEON Bank')`).includes('brand-aeon'));
  assert(run(`brandBadge('Setel')`).includes('brand-setel'));
  assert(run(`settings()`).includes('MoneyLens 0.9.0'));
  run(`state.settings.lang='ms'`);
  const bmHome=run(`home()`);
  assert(bmHome.includes('Aliran tunai kitaran'));
  run(`state.settings.lang='en'`);
});
test('transactions FX pill and goal pot progress bars render cleanly',()=>{
  const fxHtml=run(`renderTxRow({id:'fx1',kind:'expense',amount:6675,account:'cash',category:'Bills & utilities',subcategory:'Streaming & App Subscriptions',merchant:'ChatGPT OpenAI',originalCurrency:'USD',originalAmount:'15.00',exchangeRate:'4.45',date:today(),postings:[{account:'cash',amount:-6675},{account:'expense:Bills & utilities',amount:6675}]})`);
  assert(fxHtml.includes('tx-pill fx')&&fxHtml.includes('USD 15.00')&&fxHtml.includes('@4.45'));
  run(`state.goals=[{id:'g9',name:'Japan Autumn',target:500000,saved:150000}]`);
  const gHtml=run(`goals()`);
  assert(gHtml.includes('goal-pot')&&gHtml.includes('goal-pot-pill')&&gHtml.includes('goal-pot-track')&&gHtml.includes('30% there'));
  run(`state.goals=[]`);
});
test('Phase 10 BNPL/app brand badges, quick category chips, source group filters & templates',()=>{
  assert(run(`brandBadge('Atome BNPL')`).includes('brand-atome'));
  assert(run(`brandBadge('Ryt Bank')`).includes('brand-ryt'));
  assert(run(`brandBadge('Lazada')`).includes('brand-lazada'));
  assert(run(`brandBadge('foodpanda')`).includes('brand-foodpanda'));
  assert(run(`brandBadge('BigPay')`).includes('brand-wallet'));
  assert(run(`brandBadge('MyPB by Public Bank')`).includes('brand-bank'));
  assert(run(`brandBadge('Boost PayFlex')`).includes('brand-boost'));

  let modalBody='';
  const prevModal=ctx.modal;
  ctx.modal=(t,b)=>{modalBody=b;};
  run(`quickNoteForm('t1')`);
  assert(modalBody.includes('quick-cat-chips')&&modalBody.includes('Hawker &amp; Mamak')&&modalBody.includes('BNPL (SPayLater / Atome)'));

  run(`apps=[{package:'com.shopee.pay.my',name:'ShopeePay MY',group:'bnpl',installed:true},{package:'com.atome.my',name:'Atome BNPL',group:'bnpl',installed:false},{package:'my.com.rytbank',name:'Ryt Bank',group:'bank',installed:false}];sourceGroup='bnpl';renderSources('');`);
  assert(modalBody.includes('ShopeePay MY')&&modalBody.includes('Atome BNPL')&&!modalBody.includes('Ryt Bank')&&modalBody.includes('Not installed'));
  run(`sourceGroup='all'`);
  ctx.modal=prevModal;

  const billsHtml=run(`bills()`);
  assert(billsHtml.includes('Boost PayFlex')&&billsHtml.includes('LazPayLater'));
});
console.log('RESULT: '+passed+' UI tests passed');
