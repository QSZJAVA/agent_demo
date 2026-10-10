async (page) => {
  const requests = [];
  const record = request => {
    const url = new URL(request.url());
    if (request.method() === "POST" && url.pathname.startsWith("/api/dispatch/")) {
      requests.push({path:url.pathname, body:request.postData()});
    }
  };
  page.on("request", record);
  const steps = [];
  try {
    const pending = page.locator(".plan-card.pending");
    if (await pending.count() !== 1 || !(await pending.innerText()).includes("SO2026001")) throw new Error("确认前没有唯一的服务器订单清单");
    await pending.getByRole("button", {name:"确认派单",exact:true}).click();
    await page.locator(".plan-card.executed").last().waitFor({state:"visible",timeout:60000});
    const executed = await page.locator(".plan-card.executed").last().innerText();
    if (!executed.includes("SO2026001") || !/成功\s*1/.test(executed)) throw new Error("执行结果未显示服务器订单成功一条");
    await page.screenshot({path:"output/playwright/r15-08-executed.png",fullPage:true,scale:"css"});
    steps.push({id:"08-executed",text:executed,screenshot:"output/playwright/r15-08-executed.png"});
    const requestsAfterConfirm = requests.length;
    await page.reload();
    await page.getByRole("button",{name:/业务助手/}).waitFor({state:"visible",timeout:20000});
    const box=page.getByRole("textbox",{name:"例如：查询销售报表 / 查询失败的派单记录 / 总结A公司工单（Enter 发送，Shift+Enter 换行）",exact:true});
    if (!await box.isVisible()) await page.getByRole("button",{name:/业务助手/}).click();
    await page.locator(".plan-card.executed").last().waitFor({state:"visible",timeout:20000});
    const restored = await page.locator(".plan-card.executed").last().innerText();
    if (!restored.includes("SO2026001") || await page.locator(".plan-card.pending").count() !== 0) throw new Error("刷新后清单未恢复执行终态");
    if (requests.length !== requestsAfterConfirm) throw new Error("刷新恢复触发了额外派单写入");
    await page.screenshot({path:"output/playwright/r15-09-refreshed.png",fullPage:true,scale:"css"});
    steps.push({id:"09-refreshed",text:restored,screenshot:"output/playwright/r15-09-refreshed.png"});
    return {version:"r15",phase:"confirm-and-refresh",passed:true,requests,steps};
  } catch(error) {
    await page.screenshot({path:"output/playwright/r15-confirm-failure.png",fullPage:true,scale:"css"});
    return {version:"r15",phase:"confirm-and-refresh",passed:false,error:String(error),requests,steps};
  } finally {page.off("request",record);}
}
