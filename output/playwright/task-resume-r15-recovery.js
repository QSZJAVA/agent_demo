async (page) => {
 const steps=[];
 try {
  const card=page.locator(".plan-card.executed").last();
  await card.waitFor({state:"visible",timeout:20000});
  const recovered=await card.innerText();
  if (!recovered.includes("SO2026001") || !/成功\s*1\s*条/.test(recovered)) throw new Error("恢复结果不是服务器订单成功一条");
  await page.screenshot({path:"output/playwright/r15-08-recovered-executed.png",fullPage:true,scale:"css"});
  steps.push({id:"08-recovered-executed",text:recovered,screenshot:"output/playwright/r15-08-recovered-executed.png"});
  await page.reload();
  await page.getByRole("button",{name:/业务助手/}).waitFor({state:"visible",timeout:20000});
  const box=page.getByRole("textbox",{name:"例如：查询销售报表 / 查询失败的派单记录 / 总结A公司工单（Enter 发送，Shift+Enter 换行）",exact:true});
  if (!await box.isVisible()) await page.getByRole("button",{name:/业务助手/}).click();
  if (!await card.isVisible()) await page.getByRole("button",{name:/^查一下销售报表服务器的数据/}).click();
  await card.waitFor({state:"visible",timeout:20000});
  const restored=await card.innerText();
  if (!restored.includes("SO2026001") || await page.locator(".plan-card.pending").count()!==0) throw new Error("刷新后的执行终态不符");
  await page.screenshot({path:"output/playwright/r15-09-refreshed.png",fullPage:true,scale:"css"});
  steps.push({id:"09-refreshed",text:restored,screenshot:"output/playwright/r15-09-refreshed.png"});
  await card.getByRole("button",{name:"查看完整追溯",exact:true}).click();
  const trace=page.locator(".dispatch-trace");
  await trace.getByText("证据完整",{exact:true}).waitFor({state:"visible",timeout:20000});
  await page.screenshot({path:"output/playwright/r15-10-trace.png",fullPage:true,scale:"css"});
  steps.push({id:"10-trace",text:await trace.innerText(),screenshot:"output/playwright/r15-10-trace.png"});
  return {version:"r15",phase:"recovery-refresh-trace",passed:true,steps};
 } catch(error) {return {version:"r15",phase:"recovery-refresh-trace",passed:false,error:String(error),steps};}
}
