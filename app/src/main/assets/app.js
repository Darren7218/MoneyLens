'use strict';
function call(method,args={}){
  return new Promise((resolve,reject)=>{
    const id=String(++seq);
    pending.set(id,{resolve,reject});
    if(window.MoneyLensNative)window.MoneyLensNative.request(id,method,JSON.stringify(args));
    else{pending.delete(id);reject(new Error('This preview is read-only. Install the Android APK to save records.'));}
  });
}

async function mutate(method,args,message){
  const result=await call(method,args);
  if(result&&result.schema)state=result;
  render();
  if(message)toast(message);
  if(window.MoneyLensNative){reportData=await call('report');status=(await call('snapshot')).status;render()}
  return result;
}

function toast(text){
  const tEl=$('#toast');
  tEl.textContent=text;
  tEl.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer=setTimeout(()=>tEl.classList.remove('show'),3500);
}

window.nativeResponse=(id,r)=>{const p=pending.get(id);if(!p)return;pending.delete(id);r.ok?p.resolve(r.result):p.reject(new Error(r.error));};
window.nativeRefresh=async(screen,editNoteId)=>{
  try{
    const r=await call('snapshot');state=r.state;status=r.status;
    reportData=await call('report');
    if(screen&&['home','inbox','plan','accounts','transactions','settings'].includes(screen))page=screen;
    render();
    if(editNoteId)quickNoteForm(editNoteId);
    else if(screen==='quick')txForm();
    else if(screen==='scan')scanDialog();
    else if(screen==='favorites')showFavorites();
  }catch(e){
    if(!state)$('#app').innerHTML=`<div class="loading">${icon('lock')}<h2>${esc(tr('Unlock MoneyLens'))}</h2><p>${esc(e.message)}</p><button class="btn" data-action="retry">${icon('shield')}${esc(tr('Unlock with Fingerprint'))}</button></div>`;
    else toast(e.message);
  }
};
window.openQuickNote=id=>quickNoteForm(id);
window.back=()=>{if($('#modal-root').innerHTML)close();else{page='home';render()}};

function nav(){
  const n=state.drafts.filter(d=>d.state==='review').length+validTx().filter(t=>t.autoRecorded&&t.needsNote).length;
  return [['home','home',tr('Home')],['transactions','list',tr('Transactions')],['inbox','inbox',tr('Auto & Inbox')],['plan','plan',tr('Plan')],['accounts','wallet',tr('Accounts')]].map(([id,ico,label])=>`<button class="nav-btn ${page===id?'active':''}" data-page="${id}" aria-label="${esc(label)}" ${page===id?'aria-current="page"':''}>${icon(ico)}<span>${esc(label)}</span>${id==='inbox'&&n?`<b class="badge">${n}</b>`:''}</button>`).join('');
}

function header(){
  const titles={home:tr('Hello, {name}',{name:state.settings.name||tr('there')}),transactions:tr('Your transactions'),inbox:tr('Auto-Captured & Inbox'),plan:tr('A little planning'),accounts:tr('Your accounts'),settings:tr('Make it yours')};
  const sub={home:tr('YOUR MONEY, A LITTLE CLEARER'),transactions:tr('THE EVERYDAY PICTURE'),inbox:tr('AUTO-RECORDED · 1-TAP REMOVE OR EDIT'),plan:tr('MAKE ROOM FOR WHAT MATTERS'),accounts:tr('EVERYTHING IN ONE PLACE'),settings:tr('SETTINGS & PRIVACY')};
  const curAccent=state.settings.accent||'emerald';
  const dots=['emerald','indigo','ocean','terracotta'].map(a=>`<button type="button" class="theme-dot ${curAccent===a?'active':''}" data-action="setAccent" data-accent="${a}" title="Theme: ${a}" aria-label="Accent ${a}"></button>`).join('');
  return `<header class="topbar flex between"><div><p class="eyebrow">${esc(sub[page])}</p><h1>${esc(titles[page])}${page==='home'?'<span class="muted">.</span>':''}</h1></div><div class="flex"><div class="theme-dots" title="Switch accent palette">${dots}</div>${state.settings.appLock?`<button class="round" data-action="lockNow" aria-label="${esc(tr('Lock app'))}" title="${esc(tr('Lock now'))}">${icon('lock')}</button>`:''}<button class="round" data-action="toggleBlur" aria-label="${esc(tr('Toggle privacy blur'))}" title="${esc(tr('Hide or show balances'))}">${icon(state.settings.privacyBlur?'eyeOff':'eye')}</button><button class="btn desktop-add" data-action="add">${icon('plus')}${esc(tr('Add transaction'))}</button><button class="round" data-page="${page==='settings'?'home':'settings'}" aria-label="${esc(tr(page==='settings'?'Home':'Settings'))}">${icon(page==='settings'?'home':'settings')}</button></div></header>`;
}

function cyclePicker(){
  return `<div class="flex"><button class="row-menu" data-action="prevMonth" aria-label="Previous cycle">${icon('chevron').replace('<svg','<svg style="transform:rotate(180deg)"')}</button><span style="font-size:12px">${esc(period().label)}</span><button class="row-menu" data-action="nextMonth" aria-label="Next cycle">${icon('chevron')}</button></div>`;
}

function empty(ico,title,text,button='',action=''){
  return `<div class="empty"><div class="icon-bg">${icon(ico)}</div><h3>${esc(title)}</h3><p>${esc(text)}</p>${button?`<button class="btn secondary small" data-action="${action}">${esc(button)}</button>`:''}</div>`;
}

function render(){
  if(!state)return;
  for(const key of ['rules','favorites','customCategories','receipts','imports','reconciliations','installments','debts'])state[key]??=[];
  for(const c of state.customCategories)if(!categories.some(r=>r[0]===c.name))categories.push([c.name,c.icon,c.color]);
  document.documentElement?.setAttribute?.('lang',currentLang());
  document.body.classList.toggle('dark',state.settings.theme==='dark');
  if(document.body.dataset)document.body.dataset.accent=state.settings.accent||'emerald';
  document.body.classList.toggle('privacy-blur',!!state.settings.privacyBlur);
  const navHtml=nav();
  $('#app').innerHTML=`<div class="shell"><aside class="sidebar"><div class="brand flex"><div class="brand-mark">M</div>MoneyLens</div><nav>${navHtml}</nav><div class="sidebar-foot"><button class="nav-btn ${page==='settings'?'active':''}" data-page="settings">${icon('settings')}${esc(tr('Settings'))}</button><div class="privacy-pill">${icon('shield')}${esc(tr('Private. Offline. Yours.'))}</div></div></aside><main class="screen">${header()}${({home,transactions,inbox,plan,accounts,settings}[page]||home)()}</main><button class="fab-add" data-action="add" aria-label="${esc(tr('Quick add transaction'))}" title="${esc(tr('Add transaction'))}">${icon('plus')}</button><nav class="bottomnav" aria-label="Main navigation">${navHtml}</nav></div>`;
}

if(window.MoneyLensNative)window.nativeRefresh();
else if(window.MONEYLENS_PREVIEW){state=window.MONEYLENS_PREVIEW;status={};render()}
else $('#app').innerHTML='<div class="loading"><div class="brand-mark">M</div><h1>MoneyLens</h1><p>Install the Android app to get started.</p></div>';
