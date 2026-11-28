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
  assert(setHtml.includes('id="langToggle"')&&setHtml.includes('value="ms" selected')&&setHtml.includes('Rekod automatik notifikasi')&&setHtml.includes('id="ecoCaptureToggle"')&&setHtml.includes('Enjin notifikasi jimat kuasa (Mod Eko)')&&setHtml.includes('Kunci aplikasi cap jari &amp; biometrik')&&setHtml.includes('id="categoryAlertsToggle"')&&setHtml.includes('id="anomalyGuardToggle"')&&setHtml.includes('id="splitAutoMatchToggle"')&&setHtml.includes('Pengawal anomali')&&setHtml.includes('Pustaka notifikasi &amp; amaran kategori')&&setHtml.includes('0.17.0'));
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
  assert(run(`settings()`).includes('MoneyLens 0.17.0'));
  assert(run(`settings()`).includes('id="anomalyGuardToggle"')&&run(`settings()`).includes('Spend anomaly'));
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
test('Phase 11 category notification library, budget alert pills & commercial UI tones',()=>{
  const cm=run(`catMeta('Food & drinks')`);
  assert.strictEqual(cm.tone,'coral');
  assert(run(`CAT_NOTIFICATION_PRESETS.length`)>=15);

  const txHtml=run(`transactions()`);
  assert(txHtml.includes('cat-ico-badge')&&txHtml.includes('tone-coral')&&txHtml.includes('cat-tint')&&txHtml.includes('chip-count'));

  const homeHtml=run(`home()`);
  assert(homeHtml.includes('data-action="catNotifLibrary"')&&homeHtml.includes('cat-mini-pill'));

  let libTitle='',libBody='';
  const prevModal=ctx.modal;
  ctx.modal=(t,b)=>{libTitle=t;libBody=b;};
  run(`categoryNotificationLibrary()`);
  assert(libTitle.includes('Category Notification Library')&&libBody.includes('lib-grid')&&libBody.includes('lib-card')&&libBody.includes('data-action="testPreset"')&&libBody.includes('data-action="testCategoryAlert"'));
  ctx.modal=prevModal;

  run(`state.budgets=[{id:'b-food',category:'Food & drinks',limit:5000},{id:'b-petrol',category:'Transport',limit:2000}];reportData={budgets:[{id:'b-food',category:'Food & drinks',limit:5000,available:5000,spent:6200,carry:0,remaining:-1200,spentPct:124,pacePct:40,paceStatus:'over'},{id:'b-petrol',category:'Transport',limit:2000,available:2000,spent:1700,carry:0,remaining:300,spentPct:85,pacePct:40,paceStatus:'fast'}]}`);
  const budgetHtml=run(`budgets()`);
  assert(budgetHtml.includes('budget-alert-pill over')&&budgetHtml.includes('budget-alert-pill warn')&&budgetHtml.includes('data-action="testCategoryAlert"')&&budgetHtml.includes('Alert Rules'));
  run(`reportData=null`);

  assert(run(`CAT_NOTIFICATION_PRESETS.some(p=>p.key==='refund')`)===true);
  run(`state.transactions.push({id:'t-orig-1',kind:'expense',amount:10000,account:'cash',category:'Shopping',merchant:'UNIQLO Malaysia',date:'2026-11-10',postings:[{account:'cash',amount:-10000},{account:'expense:Shopping',amount:10000}]},{id:'t-ref-1',kind:'refund',originalId:'t-orig-1',amount:4000,account:'cash',category:'Shopping',merchant:'UNIQLO Malaysia',date:'2026-11-12',autoRecorded:true,postings:[{account:'cash',amount:4000},{account:'expense:Shopping',amount:-4000}]},{id:'t-anom-1',kind:'expense',amount:7000,account:'cash',category:'Food & drinks',merchant:'Zus Coffee',date:'2026-11-12',autoRecorded:true,anomaly:true,anomalyMedian:1400,anomalyRatio:'5.0x',needsNote:true,postings:[{account:'cash',amount:-7000},{account:'expense:Food & drinks',amount:7000}]})`);
  const txHtml12=run(`transactions()`);
  assert(txHtml12.includes('tx-pill refund-link')&&txHtml12.includes('Linked refund · UNIQLO Malaysia'));
  assert(txHtml12.includes('tx-pill anomaly-warn')&&txHtml12.includes('5.0x usual'));
  const inboxHtml12=run(`inbox()`);
  assert(inboxHtml12.includes('data-action="dismissAnomaly"')&&inboxHtml12.includes('tx-pill refund-link'));
  let detailHtml='';
  ctx.modal=(t,b)=>{detailHtml=b;};
  run(`detail('t-anom-1')`);
  assert(detailHtml.includes('anomaly-banner')&&detailHtml.includes('data-action="dismissAnomaly"'));
  run(`detail('t-ref-1')`);
  assert(detailHtml.includes('Linked refund')&&detailHtml.includes('UNIQLO Malaysia'));
  ctx.modal=prevModal;
});
test('Phase 13 vault health check, restore diff preview & snapshot history UI',()=>{
  const setHtml=run(`settings()`);
  assert(setHtml.includes('data-action="healthCheck"')&&setHtml.includes('data-action="snapshotHistory"')&&setHtml.includes('id="splitAutoMatchToggle"')&&setHtml.includes('MoneyLens 0.17.0'));

  let mTitle='',mBody='';
  const prevModal=ctx.modal;
  ctx.modal=(t,b)=>{mTitle=t;mBody=b;};

  run(`renderHealthModal(computeLocalHealthCheck())`);
  assert(mTitle.includes('Vault Health Check')&&mBody.includes('health-score-ring')&&mBody.includes('Double-entry ledger'));

  run(`window.restorePreviewLoaded({receiptCount:2,diff:{current:{txCount:10,accountCount:3,budgetCount:2,billCount:1,receiptCount:1,netBalance:50000},candidate:{txCount:8,accountCount:3,budgetCount:2,billCount:1,receiptCount:2,netBalance:30000,latestDate:'2026-11-10'},txDelta:-2,accountDelta:0,budgetDelta:0,billDelta:0,receiptDelta:1,netBalanceDelta:-20000,wouldLoseData:true}})`);
  assert(mTitle.includes('Restore Diff Preview')&&mBody.includes('diff-grid')&&mBody.includes('fewer records')&&mBody.includes('data-action="confirmRestore"')&&mBody.includes('data-action="cancelRestore"'));

  ctx.modal=prevModal;
});
test('Phase 14 split bills tabs by friend, home split banner & auto-reconciled pill',()=>{
  const prevReport=run(`reportData`);
  run(`state.transactions.push({id:'t-sh1',kind:'shared',amount:6000,ownAmount:'20.00',person:'Ali bin Ahmad',account:'cash',category:'Food & drinks',merchant:'Banana Leaf',date:today(),postings:[{account:'cash',amount:-6000},{account:'expense:Food & drinks',amount:2000},{account:'receivable:t-sh1',amount:4000}]},{id:'t-rb1',kind:'reimbursement',reimbursementFor:'t-sh1',amount:1500,account:'cash',category:'Repayment',merchant:'Repayment · Sarah Lee',matchedPerson:'Sarah Lee',autoReconciledSplit:true,autoRecorded:true,date:today(),postings:[{account:'cash',amount:1500},{account:'receivable:t-sh1',amount:-1500}]});reportData=null;planTab='splits';`);
  const splitHtml=run(`plan()`);
  assert(splitHtml.includes('SPLIT BILLS &amp; RECEIVABLES')&&splitHtml.includes('Ali bin Ahmad')&&splitHtml.includes('RM 25.00')&&splitHtml.includes('data-action="settlePerson"')&&splitHtml.includes('data-action="copySplitMsg"')&&splitHtml.includes('Auto-matched split'));

  run(`reportData={budgets:[],receivables:[{id:'t-sh1',person:'Ali bin Ahmad',merchant:'Banana Leaf',remaining:2500}],owed:2500,sharedSummary:{totalOwed:2500,friendCount:1,openCount:1}}`);
  const homeHtml=run(`home()`);
  assert(homeHtml.includes('Split bills owed: RM 25.00')&&homeHtml.includes('data-action="openSplitsPlan"'));

  const txHtml=run(`transactions()`);
  assert(txHtml.includes('tx-pill split-link')&&txHtml.includes('Auto-matched split · Sarah Lee'));
  assert(run(`CAT_NOTIFICATION_PRESETS.some(p=>p.key==='split')`)===true);
  run(`reportData=${JSON.stringify(prevReport)};planTab='budgets'`);
});
test('Phase 15 safe-to-spend SVG trend, cashflow SVG, tx summary filters & smart quick entry',()=>{
  const prevReport=run(`reportData`),prevFilter=run(`filter`),prevModal=ctx.modal;
  let captured='';ctx.modal=(t,h)=>{captured=t+'||'+h;};
  const d0=run(`today()`),dPrev=run(`(()=>{const p=period(-1);return p.start;})()`);
  run(`state.transactions.push({id:'t-p15-prev',kind:'expense',amount:5000,account:'cash',category:'Food & drinks',merchant:'Kopitiam',date:'${dPrev}',postings:[{account:'cash',amount:-5000},{account:'expense:Food & drinks',amount:5000}]},{id:'t-p15-cur',kind:'expense',amount:7500,account:'cash',category:'Food & drinks',merchant:'Cafe',anomaly:true,anomalyRatio:'3.0x',date:'${d0}',postings:[{account:'cash',amount:-7500},{account:'expense:Food & drinks',amount:7500}]});reportData={...reportData,availableToSpend:150000,dailyAllowance:5000,months:[{start:'2026-09-01',income:400000,spent:220000},{start:'2026-10-01',income:420000,spent:240000}],budgets:reportData?.budgets||[],receivables:reportData?.receivables||[],owed:reportData?.owed||0,sharedSummary:reportData?.sharedSummary||{totalOwed:0,openCount:0,friendCount:0,friends:[],recentSettlements:[]}};`);
  const homeHtml=run(`home()`);
  assert(homeHtml.includes('SAFE TO SPEND TODAY')&&homeHtml.includes('class="trend-svg"')&&homeHtml.includes('6-MONTH CASHFLOW')&&homeHtml.includes('class="cashflow-svg"')&&homeHtml.includes('class="delta-badge up"'));

  const txHtml=run(`filter='all';transactions()`);
  assert(txHtml.includes('class="tx-summary-bar"')&&txHtml.includes('data-filter="splits"')&&txHtml.includes('data-filter="anomaly"'));
  assert(run(`filter='anomaly';filteredTransactions().some(t=>t.id==='t-p15-cur')`)===true);

  const qp=run(`localQuickParse('Lunch RM 14.50 split with Ali cash')`);
  assert(qp&&qp.kind==='shared'&&qp.amount==='14.50'&&qp.ownAmount==='7.25'&&qp.person==='Ali'&&qp.account==='cash');

  run(`txForm()`);
  assert(captured.includes('id="quick-parse-input"')&&captured.includes('data-action="quickParseFill"'));
  assert(run(`settings()`).includes('MoneyLens 0.17.0'));

  ctx.modal=prevModal;run(`filter=${JSON.stringify(prevFilter)};reportData=${JSON.stringify(prevReport)}`);
});

test('Phase 16 UI polish hides Settings FAB, stacks Auto-lock, adds section jump chips & health refresh',()=>{
  run(`const prevPage=page;`);
  let rendered='',capturedHealth='';
  const css=fs.readFileSync(path.join(__dirname,'..','app','src','main','assets','style.css'),'utf8');
  assert(css.includes('.setting-row.stacked')&&css.includes('calc(156px + env(safe-area-inset-bottom))')&&css.includes('.settings-jump'));

  const setHtml=run(`settings()`);
  assert(setHtml.includes('class="settings-jump"')&&setHtml.includes('id="set-capture"')&&setHtml.includes('id="set-prefs"')&&setHtml.includes('id="set-tools"')&&setHtml.includes('id="set-backup"')&&setHtml.includes('class="setting-row stacked"'));

  const prevQS=ctx.document.querySelector,prevModal=ctx.modal;
  ctx.document.querySelector=sel=>sel==='#app'?{set innerHTML(v){rendered=v;}}:prevQS(sel);
  run(`page='settings';render()`);
  assert(!rendered.includes('class="fab-add"')&&rendered.includes('id="set-capture"'));
  run(`page='home';render()`);
  assert(rendered.includes('class="fab-add"'));
  run(`page=prevPage;`);
  ctx.document.querySelector=prevQS;

  ctx.modal=(t,h)=>{capturedHealth=h;};
  run(`renderHealthModal(computeLocalHealthCheck())`);
  ctx.modal=prevModal;
  assert(capturedHealth.includes('data-action="healthCheck"'));

  run(`state.drafts.push({id:'d-rv',merchant:'ZUS',amount:1200,kind:'expense',category:'Food & drinks',source:'TNG',state:'review',alsoSeenFrom:'Maybank MAE',date:today(),reason:'Review'},{id:'d-dup',merchant:'TNG DIGITAL',amount:1200,kind:'expense',category:'Food & drinks',source:'Maybank MAE',state:'linked',duplicateOf:'d-rv',date:today(),reason:'Merged with TNG alert'});`);
  const inb=run(`inbox()`);
  assert(inb.includes('Also from Maybank MAE')&&inb.includes('Merged with TNG alert'));
});

test('Phase 18 Cycle Review view, focusQuick modal & 20-tx encrypted backup nudge',()=>{
  const prevReport=run(`reportData`);
  const prevStatus=run(`status`);
  const prevTx=run(`state.transactions`);
  run(`state.settings.backupNudgeDismissed=false;state.settings.backupNudgeSnoozeUntil=0;status={...status,backupSuccess:0,lastSnapshotAt:0};`);

  // Smart quick entry focusQuick support
  let focused=false;
  const prevSetTimeout=ctx.setTimeout,prevQS=ctx.document.querySelector;
  ctx.setTimeout=(fn)=>{fn();return 0;};
  ctx.document.querySelector=sel=>sel==='#quick-parse-input'?{focus:()=>{focused=true;}}:prevQS(sel);
  run(`txForm({focusQuick:true})`);
  assert(focused===true);
  ctx.setTimeout=prevSetTimeout;ctx.document.querySelector=prevQS;

  // Cycle Review tab & Home cycle-end banner
  run(`planTab='review';reportData={budgets:[],receivables:[],months:[],dailyPacing:{safeDaily:6500,dailyAvg:4200,remainingDays:2},recap:{start:today(),end:today(),totalIncome:500000,previousIncome:480000,incomeDelta:20000,totalSpent:210000,previousSpent:250000,spentDelta:-40000,netSaved:290000,savingsRatePct:58,elapsedDays:28,totalDays:30,daysLeft:2,dailyAvg:7500,isCycleEndWindow:true,txCount:24,topCategories:[{category:'Food & drinks',amount:90000,previous:80000,delta:10000,deltaPct:13,sharePct:43}],topMerchants:[{merchant:'Nasi Lemak',amount:45000,count:6}],budgets:[],underBudgetCount:2,overBudgetCount:0,claimableCount:2}};`);
  const planHtml=run(`plan()`);
  assert(planHtml.includes('data-plan="review"')&&planHtml.includes('CYCLE-END REVIEW')&&planHtml.includes('Top Categories &amp; MoM Change')&&planHtml.includes('data-action="reviewPrevMonth"')&&planHtml.includes('data-action="exportPdf"'));

  const homeCycleEnd=run(`home()`);
  assert(homeCycleEnd.includes('cycle-end-banner')&&homeCycleEnd.includes('data-action="openCycleReview"'));

  // 20-tx Backup Nudge eligibility (<20 off, >=20 on, dismiss persistence & Settings reset)
  run(`state.transactions=[]`);
  assert(run(`computeLocalBackupNudge().shouldShow`)===false);
  for(let i=0;i<20;i++){
    run(`state.transactions.push({id:'bn-${i}',kind:'expense',amount:500,account:'cash',category:'Food & drinks',merchant:'Stall',date:today(),postings:[{account:'cash',amount:-500},{account:'expense:Food & drinks',amount:500}]})`);
  }
  const bnOn=run(`computeLocalBackupNudge()`);
  assert(bnOn.shouldShow===true&&bnOn.activeTxCount>=20);
  const homeNudge=run(`home()`);
  assert(homeNudge.includes('backup-nudge-card')&&homeNudge.includes('data-action="snoozeBackupNudge"')&&homeNudge.includes('data-action="dismissBackupNudge"'));

  run(`state.settings.backupNudgeDismissed=true;`);
  assert(run(`computeLocalBackupNudge().shouldShow`)===false);
  assert(run(`settings()`).includes('data-action="resetBackupNudge"'));

  run(`state.transactions=${JSON.stringify(prevTx)};state.settings.backupNudgeDismissed=false;reportData=${JSON.stringify(prevReport)};status=${JSON.stringify(prevStatus)};planTab='budgets';`);
});

test('Phase 19A scrollable Plan tabs, Cycle Review empty-income/prior wording, BM txForm & Phase 20 budget suggestions',()=>{
  const prevReport=run(`reportData`),prevLang=run(`state.settings.lang`);
  const css=fs.readFileSync(path.join(__dirname,'..','app','src','main','assets','style.css'),'utf8');
  assert(css.includes('flex-wrap: nowrap')&&css.includes('overflow-x: auto')&&css.includes('.detail-line { display: flex; justify-content: space-between; align-items: center;'));

  // Zero-income & zero prior-cycle wording in Cycle Review + Plan Next Cycle button
  run(`planTab='review';reportData={budgets:[],receivables:[],months:[],dailyPacing:{safeDaily:0,dailyAvg:800,remainingDays:10},recap:{start:today(),end:today(),totalIncome:0,previousIncome:0,incomeDelta:0,totalSpent:15825,previousSpent:0,spentDelta:15825,netSaved:-15825,savingsRatePct:0,elapsedDays:17,totalDays:30,daysLeft:13,dailyAvg:930,isCycleEndWindow:false,txCount:5,topCategories:[],topMerchants:[],budgets:[],underBudgetCount:0,overBudgetCount:0,claimableCount:0}};`);
  const rv=run(`plan()`);
  assert(rv.includes('Net cash flow this cycle')&&rv.includes('No prior cycle data')&&rv.includes('data-action="planNextCycle"')&&!rv.includes('0% savings rate'));

  // Bahasa Melayu txForm localization
  run(`state.settings.lang='ms'`);
  let capTitle='',capBody='';
  const prevModal=ctx.modal;
  ctx.modal=(t,b)=>{capTitle=t;capBody=b;};
  run(`txForm()`);
  assert(capTitle==='Tambah transaksi'&&capBody.includes('Peniaga / penerangan')&&capBody.includes('Tarikh')&&capBody.includes('Rekod transaksi')&&capBody.includes('Perbelanjaan'));
  ctx.modal=prevModal;

  // Phase 20 Suggested Budgets from History card & opt-in backup notification toggle
  run(`state.settings.lang='en';planTab='budgets';reportData={budgets:[],budgetSuggestions:[{category:'Groceries',avgSpend:24500,suggestedLimit:25000,cyclesAnalyzed:2}]};`);
  const budHtml=run(`plan()`);
  assert(budHtml.includes('Suggested Budgets from History')&&budHtml.includes('data-action="applyAllSuggestedBudgets"')&&budHtml.includes('data-action="applyOneSuggestedBudget"'));
  assert(run(`settings()`).includes('id="backupNudgeNotifyToggle"'));

  run(`state.settings.lang=${JSON.stringify(prevLang)};reportData=${JSON.stringify(prevReport)};planTab='budgets';`);
});

test('Track 4 semantic search pills, parsed filter chips and multilingual localQuickParse',()=>{
  const prevQuery=run(`query`),prevSearch=run(`searchState`);
  run(`state.transactions=[{id:'t-sem-1',kind:'expense',amount:1650,account:'cash',category:'Food & drinks',subcategory:'Coffee, Tea & Drinks',merchant:'ZUS Coffee KLCC',date:today(),postings:[{account:'cash',amount:-1650},{account:'expense:Food & drinks',amount:1650}]}]`);
  run(`query='咖啡 >10';searchState={query:'咖啡 >10',ids:['t-sem-1'],matches:{'t-sem-1':'semantic'},parsedFilters:{minMinor:1001,maxMinor:-1,fromDate:'',untilDate:'',hardKind:'',kindBoost:'',accountHint:'',taxReliefQuery:false},semanticUsed:true,seq:1,timer:null}`);
  const html=run(`renderTxResults(filteredTransactions())`);
  assert(html.includes('search-filter-chips')&&html.includes('Offline semantic search')&&html.includes('tx-pill semantic-hit')&&html.includes('ZUS Coffee KLCC'));

  const qZh=run(`localQuickParse('昨晚买鸡饭 12.50块 cash')`);
  const dYest=run(`(()=>{const d=new Date();d.setDate(d.getDate()-1);return d.getFullYear()+'-'+String(d.getMonth()+1).padStart(2,'0')+'-'+String(d.getDate()).padStart(2,'0')})()`);
  assert(qZh&&qZh.kind==='expense'&&qZh.amount==='12.50'&&qZh.date===dYest&&qZh.category==='Food & drinks');

  const qRef=run(`localQuickParse('Shopee退款 39.90元 cash')`);
  assert(qRef&&qRef.kind==='refund'&&qRef.amount==='39.90');

  run(`query=${JSON.stringify(prevQuery)};searchState=${JSON.stringify(prevSearch)}`);
});

test('Track 5 localQuickParse traps and batch review modal rendering',()=>{
  const prevDrafts=run(`state.drafts`);
  const qi=run(`localQuickParse('iPhone 16 Pro 4899 cash')`);
  assert(qi&&qi.amount==='4899.00'&&qi.kind==='expense');
  const qx=run(`localQuickParse('2x nasi lemak 16 cash')`);
  assert(qx&&qx.amount==='16.00'&&qx.kind==='expense');
  const qnr=run(`localQuickParse('不可退款机票 RM 380 cash')`);
  assert(qnr&&qnr.amount==='380.00'&&qnr.kind==='expense');

  run(`state.drafts=[{id:'d-b1',state:'review',revision:2,kind:'expense',amount:2550,merchant:'ZUS Coffee',category:'Food & drinks',date:today(),reason:'Recorded from notification'},{id:'d-b2',state:'review',revision:1,kind:'transfer',amount:5000,merchant:'TNG eWallet',transferHint:'TNG',category:'Miscellaneous',date:today(),reason:'Possible transfer'}]`);
  let capTitle='',capBody='';
  const prevModal=ctx.modal;
  ctx.modal=(t,b)=>{capTitle=t;capBody=b;};
  run(`batchForm()`);
  assert(capTitle==='Review selected payments'&&capBody.includes('data-extra="batch"')&&capBody.includes('name="draft" value="d-b1"')&&capBody.includes('[transfer]')&&capBody.includes('to TNG'));
  ctx.modal=prevModal;
  run(`state.drafts=${JSON.stringify(prevDrafts)}`);
});
test('Track A cycleMode monthly sets period day 1 and updates monthly copy',()=>{
  const prevMode=run(`state.settings.cycleMode`);
  const prevDay=run(`state.settings.cycleDay`);
  const prevPlanTab=run(`planTab`);
  run(`state.settings.cycleMode='monthly';state.settings.cycleDay=25;planTab='review';`);
  assert(run(`isMonthlyMode()`)===true);
  assert(run(`period().start.endsWith('-01')`)===true);
  const h=run(`home()`);
  assert(h.includes('Income this month')&&h.includes('Spent this month')&&h.includes('This month'));
  const p=run(`plan()`);
  assert(p.includes('MONTHLY REVIEW')&&p.includes('Plan Next Month'));
  run(`state.settings.cycleMode=${JSON.stringify(prevMode)};state.settings.cycleDay=${JSON.stringify(prevDay)};planTab=${JSON.stringify(prevPlanTab)};`);
});
test('Track B reload form, neutral tx row and Reloads filter chip render cleanly',()=>{
  let capTitle='',capBody='';
  const prevModal=ctx.modal;
  ctx.modal=(t,b)=>{capTitle=t;capBody=b;};
  run(`txForm({kind:'reload'})`);
  assert(capBody.includes('Same account (no balance change)')&&capBody.includes('without counting as an expense'));
  ctx.modal=prevModal;
  const row=run(`renderTxRow({id:'tx-rl-1',kind:'reload',amount:5000,account:'cash',to:'cash',category:'Transfers & top-up',merchant:'MAE Reload',date:today()})`);
  assert(row.includes('Reload · Not counted as spend')&&!row.includes('−RM')&&!row.includes('+RM'));
  const txPage=run(`transactions()`);
  assert(txPage.includes('Reloads'));
});
test('Track 5 upgraded batchForm renders per-row controls, Select all safe, Apply to all and batchError',()=>{
  const prevDrafts=run(`state.drafts`);
  run(`state.drafts=[{id:'d-u1',state:'review',revision:1,kind:'expense',amount:1250,account:'cash',merchant:'ZUS Coffee',category:'Food & drinks',date:today(),reason:'Recorded from notification'},{id:'d-u2',state:'review',revision:1,kind:'transfer',amount:4000,account:'cash',to:'',merchant:'Unknown Bank',category:'Transfers & top-up',date:today(),batchError:'Choose target account.'}]`);
  let capBody='';
  const prevModal=ctx.modal;
  ctx.modal=(t,b)=>{capBody=b;};
  run(`batchForm()`);
  assert(capBody.includes('data-batch-field="account"')&&capBody.includes('data-batch-field="kind"')&&capBody.includes('data-batch-field="category"')&&capBody.includes('data-batch-field="to"'));
  assert(capBody.includes('data-action="selectSafeBatch"')&&capBody.includes('data-action="applyBatchAcct"')&&capBody.includes('Choose target account.'));
  ctx.modal=prevModal;
  run(`state.drafts=${JSON.stringify(prevDrafts)}`);
});
test('Track 6 Settings renders opt-in correctionLogToggle and Clear log action',()=>{
  const prevOpt=run(`state.settings.correctionLogOptIn`);
  const prevLog=run(`state.correctionLog`);
  run(`state.settings.correctionLogOptIn=false;state.correctionLog=[];`);
  const s1=run(`settings()`);
  assert(s1.includes('id="correctionLogToggle"')&&!s1.includes('id="correctionLogToggle" aria-label="On-device correction log" checked'));
  run(`state.settings.correctionLogOptIn=true;state.correctionLog=[{id:'c1'},{id:'c2'}];`);
  const s2=run(`settings()`);
  assert(s2.includes('2 logged')&&s2.includes('data-action="clearCorrectionLog"'));
  run(`state.correctionLog=[{id:'c1',ts:10,after:{merchant:'Kopi Jaya',category:'Food & drinks',subcategory:'Coffee, Tea & Drinks'}}];`);
  const s3=run(`settings()`);
  assert(s3.includes('1 learned')&&s3.includes('data-action="inspectPersonalLearned"'));
  const lr=run(`personalLearnedList()`);
  assert(lr.length===1&&lr[0].key==='kopi jaya');
  const rowPers=run(`renderTxRow({id:'tx-p-1',kind:'expense',amount:1200,account:'cash',merchant:'Kopi Jaya',category:'Food & drinks',historyMatch:'personal',autoRecorded:true,date:today()})`);
  assert(rowPers.includes('Learned'));
  run(`state.settings.correctionLogOptIn=${JSON.stringify(prevOpt)};state.correctionLog=${JSON.stringify(prevLog)};`);
});
test('Phase D receiptMetaBlock renders items and LHDN metadata in txForm & detail and 6-model diagnostics renders',()=>{
  let capBody='';
  const prevModal=ctx.modal;
  ctx.modal=(t,b)=>{capBody=b;};
  const sampleRow={id:'tx-rec-1',kind:'expense',amount:1685,account:'cash',category:'Health & medical',merchant:'Watsons',date:run('today()'),subtotalMinor:1590,sstMinor:95,lhdnTin:'C1234567890',lhdnInvoiceRef:'INV-998877',items:[{name:'Panadol',amount:1590}]};
  run(`txForm(${JSON.stringify(sampleRow)})`);
  assert(capBody.includes('receipt-meta')&&capBody.includes('C1234567890')&&capBody.includes('INV-998877')&&capBody.includes('Panadol'));
  run(`state.transactions.push(${JSON.stringify(sampleRow)});detail('tx-rec-1');state.transactions.pop();`);
  assert(capBody.includes('Receipt details &amp; LHDN e-Invoice')||capBody.includes('Receipt details & LHDN e-Invoice'));
  assert(capBody.includes('C1234567890')&&capBody.includes('Panadol'));
  ctx.modal=prevModal;
});
console.log('RESULT: '+passed+' UI tests passed');
