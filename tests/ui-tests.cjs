// Pure rendering checks without a browser. Does not claim layout/device verification.
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const elements=new Map();function el(){return{innerHTML:'',textContent:'',classList:{add(){},remove(){},toggle(){}},focus(){},querySelector(){return null}}}
const document={body:el(),querySelector(s){if(!elements.has(s))elements.set(s,el());return elements.get(s)},addEventListener(){},querySelectorAll(){return[]}};
const fixture=JSON.parse(fs.readFileSync('build/preview-fixture.json','utf8'));
const ctx=vm.createContext({document,window:{MONEYLENS_PREVIEW:fixture,scrollTo(){}},console,Date,Math,Map,Set,JSON,Promise,setTimeout(){},clearTimeout(){},crypto:{randomUUID(){return'test-key'}},FormData:class{}});
vm.runInContext(fs.readFileSync('app/src/main/assets/app.js','utf8'),ctx);
let passed=0;function check(name,fn){fn();passed++;console.log('PASS '+name)}
for(const page of ['home','transactions','inbox','plan','accounts','settings'])check('Render '+page,()=>{vm.runInContext(`page='${page}';render()`,ctx);const html=elements.get('#app').innerHTML;assert(html.includes('<main'));assert(!html.includes('NaN'));assert(!html.includes('undefined'));assert(html.length>2500)});
for(const tab of ['budgets','bills','goals'])check('Render plan '+tab,()=>{vm.runInContext(`page='plan';planTab='${tab}';render()`,ctx);assert(elements.get('#app').innerHTML.includes('section'))});
check('Income and expenses use ledger values',()=>{const totals=vm.runInContext('totals()',ctx);assert.equal(totals.income,350000);assert.equal(totals.spent,57960);assert.equal(totals.net,435075)});
check('Escapes user supplied HTML',()=>{vm.runInContext(`state.settings.name='<img src=x onerror=alert(1)>';page='home';render()`,ctx);assert(!elements.get('#app').innerHTML.includes('<img src=x'));assert(elements.get('#app').innerHTML.includes('&lt;img'));});
for(const kind of ['expense','income','transfer','refund'])check('Render '+kind+' form',()=>{vm.runInContext(`txForm({kind:'${kind}'})`,ctx);assert(elements.get('#modal-root').innerHTML.includes('transaction-form'));assert(elements.get('#modal-root').innerHTML.includes('Record transaction'))});
check('Payment review forces account choice',()=>{vm.runInContext(`txForm({draftId:'a',kind:'expense',amount:1890})`,ctx);assert(elements.get('#modal-root').innerHTML.includes('Choose account'))});
for(const call of ['accountForm()','budgetForm()','billForm()','goalForm()'])check('Render '+call,()=>{vm.runInContext(call,ctx);assert(elements.get('#modal-root').innerHTML.includes('role="dialog"'))});
check('Empty initial state renders every screen',()=>{vm.runInContext(`state.transactions=[];state.budgets=[];state.drafts=[];state.bills=[];state.goals=[]`,ctx);for(const p of ['home','transactions','inbox','plan','accounts','settings']){vm.runInContext(`page='${p}';render()`,ctx);assert(!elements.get('#app').innerHTML.includes('NaN'))}});
console.log(`RESULT: ${passed} UI rendering checks passed (no browser/device)`);
