import { expect,test,type Page } from '@playwright/test';
import type { ApprovalQueueItem,NotificationPreference } from '../features/notifications/types/notification-policy';
const stageId='11111111-1111-4111-8111-111111111111',taskId='22222222-2222-4222-8222-222222222222',delegateId='33333333-3333-4333-8333-333333333333';
const at='2026-10-07T04:00:00Z';
const initial:NotificationPreference={version:0,inAppEnabled:true,emailEnabled:false,soundEnabled:true,cadence:'IMMEDIATE',zoneId:'Asia/Kolkata',quietEnabled:false,quietStart:'22:00',quietEnd:'08:00'};
const item:ApprovalQueueItem={id:stageId,kind:'WORK',resourceId:taskId,departmentId:stageId,stage:'TEAM_LEAD',enteredAt:at,entryKnown:true,deadlineAt:'2026-10-07T06:00:00Z',policyVersion:2,clock:'ELAPSED',escalationRole:'MANAGER',title:'Retained office report',detail:'Original authored delivery and retained instructions.',status:'COMPLETED',resourceVersion:7,canReview:true,canDelegate:true,delegated:false,delegationId:null,delegateId:null,delegationExpiresAt:null};
async function fixture(page:Page,role:'TEAM_LEAD'|'CEO'|'SYSTEM_ADMIN'='TEAM_LEAD') {
 const state={pref:{...initial},items:[{...item,stage:role==='CEO'?'CEO':item.stage,canDelegate:role==='TEAM_LEAD',canReview:role!=='SYSTEM_ADMIN'}],writes:[] as {path:string;body:Record<string,unknown>}[],gets:[] as string[],conflict:false,unknown:false,delay:false,queueFailure:false,empty:false,more:false};
 const profile={userId:stageId,employeeId:delegateId,email:'sprint10@example.invalid',fullName:'Current Reviewer',roles:[`ROLE_${role}`],permissions:['WORK_TASK_READ','WORK_TASK_REVIEW','WORK_INSIGHT_READ'],forcePasswordChange:false,departmentId:stageId,photoUrl:null};
 const springPage={content:[],number:0,size:50,totalElements:0,totalPages:0,last:true};
 await page.addInitScript(()=>{sessionStorage.setItem('brainserve.connect.access-token','sprint10-access');sessionStorage.setItem('brainserve.connect.refresh-token','sprint10-refresh');});
 await page.route('http://backend.invalid/api/v1/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname.replace('/api/v1',''),method=route.request().method();
  if(path==='/notification-preferences') {
   if(method==='GET')return route.fulfill({json:state.pref});
   const body=route.request().postDataJSON();state.writes.push({path,body});if(state.delay)await new Promise(resolve=>setTimeout(resolve,500));
   if(state.conflict)return route.fulfill({status:409,json:{detail:'Preferences changed'}});
   state.pref={...body,version:state.pref.version+1};if(state.unknown)return route.fulfill({status:503,json:{detail:'Response unavailable'}});return route.fulfill({json:state.pref});
  }
  if(path==='/approval-policies/queue'){state.gets.push(url.search);return state.queueFailure?route.fulfill({status:503,json:{detail:'Approval source unavailable'}}):route.fulfill({json:{items:state.empty?[]:state.items,page:Number(url.searchParams.get('page')),hasMore:state.more,generatedAt:at}});}
  if(path.endsWith('/candidates'))return route.fulfill({json:[{userId:delegateId,name:'Alternate Reviewer'}]});
  if(path.endsWith('/delegations')&&method==='POST'){const body=route.request().postDataJSON();state.writes.push({path,body});state.items=[{...state.items[0],delegationId:'grant-a',delegateId,delegationExpiresAt:body.expiresAt}];return route.fulfill({json:{id:'grant-a'}});}
  if(path.includes('/delegations/')&&method==='DELETE'){state.writes.push({path,body:{}});state.items=[{...state.items[0],delegationId:null,delegateId:null,delegationExpiresAt:null}];return route.fulfill({status:204});}
  if(path.endsWith('/decision')||path.endsWith('/review-queue-decision')){state.writes.push({path,body:route.request().postDataJSON()});return state.conflict?route.fulfill({status:409,json:{detail:'Current stage changed'}}):route.fulfill({status:204});}
  if(path==='/approval-policies')return route.fulfill({json:[{id:1,kind:'WORK',stage:'TEAM_LEAD',version:1,enabled:false,deadlineMinutes:120,reminderMinutes:60,escalationRole:'MANAGER'}]});
  if(path===`/work-tasks/${taskId}/planning`)return route.fulfill({json:{taskId,taskVersion:7,submissions:[{version:1,authorName:'Original Worker',employeeUpdate:'Original authored report.',checklist:[],evidence:[]} ]}});
  if(['/auth/me','/profile/me'].includes(path))return route.fulfill({json:profile});
  if(path==='/auth/security')return route.fulfill({json:{mfaRequired:true,mfaEnrolled:true,mfaVerified:true,stepUpRequired:false}});
  if(path==='/dashboard/summary')return route.fulfill({json:{awaitingApproval:0,activeVisits:0,totalEmployees:0,activeEmployees:0,scope:role==='CEO'?'COMPANY':'DEPARTMENT',departmentId:stageId}});
  if(path==='/dashboard/cards')return route.fulfill({status:503,json:{detail:'Dashboard source unavailable'}});
  if(['/appointments','/employees','/admin/staff-accounts'].includes(path))return route.fulfill({json:springPage});
  if(path==='/team-leads/me/workspace')return route.fulfill({json:{assignment:{departmentId:stageId,teamLeadUserId:stageId},department:{id:stageId,name:'Engineering',code:'ENG'},employees:springPage}});
  if(path.includes('unread'))return route.fulfill({json:{unreadCount:0}});
  if(path==='/realtime/stream')return route.fulfill({status:204});
  return route.fulfill({json:[]});
 });
 await page.goto('/');await page.getByRole('button',{name:'Open notifications',exact:true}).click();
 await expect(page.getByRole('button',{name:'Delivery preferences',exact:true})).toBeVisible();return state;
}
async function preferences(page:Page){await page.getByRole('button',{name:'Delivery preferences',exact:true}).click();const panel=page.getByRole('region',{name:'Delivery preferences',exact:true});await expect(panel.getByRole('button',{name:'Save delivery preferences'})).toBeVisible();return panel;}
async function queue(page:Page){await page.getByRole('button',{name:'Approval deadlines & delegation',exact:true}).click();const panel=page.getByRole('region',{name:'Approval deadlines & delegation',exact:true});await expect(panel.getByRole('heading',{name:'Approval deadlines & delegation',exact:true})).toBeVisible();return panel;}
for(const width of [360,768,1440])test(`delivery choices and approval disclosures are usable at ${width}px`,async({page},testInfo)=>{
 await page.setViewportSize({width,height:900});const errors:string[]=[];page.on('pageerror',e=>errors.push(e.message));const state=await fixture(page);const pref=await preferences(page);
 await pref.getByRole('combobox',{name:'Routine cadence',exact:true}).selectOption('DAILY');await pref.getByLabel('Use quiet hours for routine messages').check();await pref.getByLabel('Routine email copies').check();await pref.getByRole('button',{name:'Save delivery preferences'}).click();await expect(pref).toContainText('Delivery preferences saved');
 expect(state.writes[0].body).toMatchObject({version:0,cadence:'DAILY',emailEnabled:true,quietEnabled:true});await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);await page.screenshot({path:testInfo.outputPath(`sprint10-preferences-${width}.png`)});
 const approvals=await queue(page);await approvals.locator('summary').filter({hasText:'Retained office report'}).click();await approvals.getByRole('button',{name:'Load retained review evidence'}).click();await expect(approvals).toContainText('Submission 1 · Original Worker');await expect(approvals).toContainText('Policy version 2');
 await approvals.getByLabel('Review remarks',{exact:true}).fill('Reviewed authored evidence.');await expect(approvals.getByRole('button',{name:'Approve current stage'})).toBeEnabled();await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);await page.screenshot({path:testInfo.outputPath(`sprint10-approval-${width}.png`)});expect(errors).toEqual([]);
});
test('quiet-hour validation and preference conflicts require an explicit reload',async({page})=>{
 const state=await fixture(page);const panel=await preferences(page);await panel.getByLabel('Use quiet hours for routine messages').check();await panel.getByLabel('Quiet hours end',{exact:true}).fill('22:00');await expect(panel.getByRole('alert')).toContainText('different start and end');await expect(panel.getByRole('button',{name:'Save delivery preferences'})).toBeDisabled();expect(state.writes).toHaveLength(0);
 await panel.getByLabel('Quiet hours end',{exact:true}).fill('08:00');state.conflict=true;await panel.getByRole('button',{name:'Save delivery preferences'}).click();await expect(panel.getByRole('alert')).toContainText('another session');await expect(panel.getByRole('alert')).toBeFocused();await expect(panel.getByRole('button',{name:'Save delivery preferences'})).toBeDisabled();expect(state.writes).toHaveLength(1);
 state.conflict=false;await panel.getByRole('button',{name:'Reload saved preferences'}).click();await expect(panel.getByRole('button',{name:'Save delivery preferences'})).toBeEnabled();expect(state.writes).toHaveLength(1);
});
test('a saved preference with an unconfirmed response cannot automatically replay',async({page})=>{
 const state=await fixture(page);state.unknown=true;const panel=await preferences(page);await panel.getByLabel('Routine email copies').check();await panel.getByRole('button',{name:'Save delivery preferences'}).click();await expect(panel.getByRole('alert')).toContainText('save was not confirmed');await expect(panel.getByRole('button',{name:'Save delivery preferences'})).toBeDisabled();expect(state.writes).toHaveLength(1);
 await panel.getByRole('button',{name:'Reload saved preferences'}).click();await expect(panel.getByLabel('Routine email copies')).toBeChecked();await expect(panel).toContainText('Saved version 1');expect(state.writes).toHaveLength(1);
});
test('delegation is explicit, revocable, and decision conflicts preserve remarks',async({page})=>{
 const state=await fixture(page);const panel=await queue(page);await panel.locator('summary').filter({hasText:'Retained office report'}).click();await panel.locator('summary').filter({hasText:'Delegate this review stage'}).click();await panel.getByRole('button',{name:'Load eligible reviewers'}).click();await panel.getByRole('combobox',{name:'Delegate reviewer',exact:true}).selectOption(delegateId);await panel.getByLabel('Delegation expiry').fill('2026-10-08T16:00');await panel.getByLabel('Delegation reason',{exact:true}).fill('Cover an approved reviewer absence.');await panel.getByRole('button',{name:'Confirm reviewer delegation'}).click();
 await panel.locator('summary').filter({hasText:'Retained office report'}).click();await expect(panel).toContainText('Delegation expires');await panel.getByRole('button',{name:'Revoke delegation'}).click();await panel.locator('summary').filter({hasText:'Retained office report'}).click();await expect(panel.getByRole('button',{name:'Revoke delegation'})).toHaveCount(0);expect(state.writes).toHaveLength(2);
 state.conflict=true;await panel.getByRole('textbox',{name:'Review remarks',exact:true}).fill('Preserved review rationale.');await panel.getByRole('button',{name:'Approve current stage'}).click();await expect(panel.getByRole('alert')).toContainText('Reload the approval queue');await expect(panel.getByRole('textbox',{name:'Review remarks',exact:true})).toHaveValue('Preserved review rationale.');await expect(panel.getByRole('button',{name:'Approve current stage'})).toBeDisabled();expect(state.writes[2].body.expectedVersion).toBe(7);
});
test('failed sources, empty source pages and CEO delegation restrictions are distinct',async({page})=>{
 const state=await fixture(page,'CEO');state.queueFailure=true;const panel=await queue(page);await expect(panel.getByRole('alert')).toContainText('Approval source unavailable');state.queueFailure=false;await panel.getByRole('button',{name:'Reload approval queue'}).click();await panel.locator('summary').filter({hasText:'Retained office report'}).click();await expect(panel).toContainText('Final CEO approval cannot be delegated');await expect(panel.locator('summary').filter({hasText:'Delegate this review stage'})).toHaveCount(0);
 state.empty=true;state.more=true;await panel.getByLabel('Overdue stages only').check();await expect(panel).toContainText('No eligible overdue stages on this page');await panel.getByRole('button',{name:'Next approval page'}).click();await expect(panel).toContainText('Page 2');await expect.poll(()=>state.gets.at(-1)).toBe('?overdue=true&page=1');
});
test('account changes clear preferences, private queue details and late save completions',async({page})=>{
 const state=await fixture(page);state.delay=true;const panel=await preferences(page);await panel.getByRole('button',{name:'Save delivery preferences'}).click();await page.evaluate(()=>window.dispatchEvent(new Event('brainserve:auth-session-changed')));await expect(panel).toContainText('account changed');await expect(panel.getByLabel('Notification time zone')).toHaveCount(0);await page.waitForTimeout(650);await expect(panel).not.toContainText('Delivery preferences saved');
});
test('system admin deadline editor declares captured versions and inactive policies',async({page})=>{
 await fixture(page,'SYSTEM_ADMIN');const panel=await queue(page);await panel.locator('summary').filter({hasText:'Configure stage deadline policies'}).click();await panel.getByRole('button',{name:'Reload deadline policies'}).click();await expect(panel).toContainText('Policies start disabled');await expect(panel).toContainText('Existing stages retain their captured policy');await expect(panel.getByLabel('Enable reminders for work team lead')).not.toBeChecked();
});
