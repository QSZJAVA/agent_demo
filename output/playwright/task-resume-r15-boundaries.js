async (page) => {
 const steps=[], errors=[];
 const dialog=page.getByRole("dialog",{name:"派单追溯",exact:true});
 await dialog.getByRole("tab",{name:"逐条结果",exact:true}).click();
 await page.waitForFunction(()=>{const e=document.querySelector(".dispatch-trace"); return e && e.innerText.includes("SO2026001") && /成功|SUCCESS/.test(e.innerText);},null,{timeout:20000});
 await page.screenshot({path:"output/playwright/r15-11-trace-items.png",fullPage:true,scale:"css"});
 steps.push({id:"11-trace-items",passed:true,text:await page.locator(".dispatch-trace").innerText(),screenshot:"output/playwright/r15-11-trace-items.png"});
 await dialog.getByRole("button",{name:"Close",exact:true}).click();
 const restored=page.locator(".plan-card.executed").last();
 await restored.getByText("成功",{exact:true}).waitFor({state:"visible",timeout:20000});
 await page.screenshot({path:"output/playwright/r15-09-refreshed-loaded.png",fullPage:true,scale:"css"});
 const box=page.getByRole("textbox",{name:"例如：查询销售报表 / 查询失败的派单记录 / 总结A公司工单（Enter 发送，Shift+Enter 换行）",exact:true});
 async function test(id,message,check) {
  const before=await page.locator(".chat-messages .msg").count();
  const beforeQueries=await page.locator(".business-query-card").count();
  await box.fill(message);
  await page.getByRole("button",{name:"发送",exact:true}).click();
  await page.waitForFunction(count=>{const i=document.querySelector(".chat-input textarea");return i && !i.disabled && document.querySelectorAll(".chat-messages .msg").length>count && !document.querySelector(".chat-messages .typing");},before,{timeout:120000});
  const messageTexts=await page.locator(".chat-messages .msg").allTextContents();
  const afterQueries=await page.locator(".business-query-card").count();
  const result={id,message,reply:messageTexts.slice(before).join("\n"),beforeQueries,afterQueries,pending:await page.locator(".plan-card.pending").count(),screenshot:"output/playwright/r15-"+id+".png"};
  result.latestQuery=afterQueries>beforeQueries?await page.locator(".business-query-card").last().innerText():null;
  try {check(result);result.passed=true;} catch(error) {result.passed=false;result.error=String(error);errors.push({id,error:result.error});}
  await page.screenshot({path:result.screenshot,fullPage:true,scale:"css"});
  steps.push(result);
 }
 await test("12-repeat-rejected","把订单 SO2026001 再派一次，生成待确认清单",r=>{if(r.pending!==0 || !/已派单|不可重复|不能重复|不再满足|不符合/.test(r.reply))throw new Error("重复请求未明确拒绝或创建了新清单");});
 await test("13-company-denied","查询B公司的销售报表",r=>{if(r.afterQueries!==r.beforeQueries || !/权限|授权|无权/.test(r.reply))throw new Error("未拒绝未授权公司");});
 await test("14-report-denied","查询A公司的应收报表",r=>{if(r.afterQueries!==r.beforeQueries || !/权限|授权|无权|无可用|不可用/.test(r.reply))throw new Error("未拒绝未授权报表");});
 await test("15-inclusive-boundary","查询A公司销售报表，金额九万六千元以上的",r=>{if(!r.latestQuery || !r.latestQuery.includes("SO2026007") || !r.latestQuery.includes("SO2026001") || /SO2026002|SO2026008/.test(r.latestQuery) || !/匹配\s*2\s*条/.test(r.latestQuery) || !r.latestQuery.includes("大于等于"))throw new Error("以上未按大于等于保留边界记录");});
 await test("16-exclusive-boundary","金额改为严格超过九万六千元",r=>{if(!r.latestQuery || !r.latestQuery.includes("SO2026001") || /SO2026007|SO2026002|SO2026008/.test(r.latestQuery) || !/匹配\s*1\s*条/.test(r.latestQuery) || r.latestQuery.includes("大于等于"))throw new Error("严格超过未排除边界记录");});
 return {version:"r15",phase:"trace-repeat-permissions-boundaries",passed:errors.length===0,errors,steps};
}
